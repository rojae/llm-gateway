package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentLoggingIntegrationTest {
    private static final String REQUEST = """
            {"model":"claude-test","system":"SYSTEM-CONTEXT","messages":[
             {"role":"user","content":"OLD-HISTORY"},
             {"role":"assistant","content":"PREVIOUS-REPLY"},
             {"role":"user","content":"안녕하세요 sk-secret123456789"}]}
            """;
    private static final String RESPONSE = "{\"model\":\"claude-test\",\"content\":[{\"type\":\"text\",\"text\":\"반갑습니다\"}],\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}";
    private static final String FIRST = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"안\"}}\n\n";
    private static final String LAST = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"녕하세요\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
    private static final String PARALLEL_TOOLS = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"login","arguments":"{\\"password\\":\\""}},{"index":1,"function":{"name":"weather","arguments":"{\\"city\\":\\""}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"PRIVATE_PASSWORD\\"}"}},{"index":1,"function":{"arguments":"Seoul\\"}"}}]}}]}

            data: [DONE]

            """;
    private static volatile CountDownLatch release = new CountDownLatch(0);
    private static volatile String receivedBody;
    private static final HttpServer UPSTREAM;
    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if ("parallel-tools".equals(exchange.getRequestHeaders().getFirst("x-test-mode"))) {
                    byte[] tools = PARALLEL_TOOLS.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, tools.length);
                    try (var out = exchange.getResponseBody()) { out.write(tools); }
                    return;
                }
                boolean stream = "stream".equals(exchange.getRequestHeaders().getFirst("x-test-mode"));
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, stream ? 0 : bytes.length);
                try (var out = exchange.getResponseBody()) {
                    if (stream) {
                        // Split inside UTF-8 and hold completion until the client observes the first event.
                        for (byte b : FIRST.getBytes(StandardCharsets.UTF_8)) { out.write(b); out.flush(); }
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        out.write(LAST.getBytes(StandardCharsets.UTF_8));
                    } else out.write(bytes);
                }
            });
            UPSTREAM.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.anthropic-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1");
        registry.add("gateway.providers.openai-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1");
        registry.add("gateway.content-log.format", () -> "pretty");
    }
    @AfterAll static void close() { release.countDown(); UPSTREAM.stop(0); }
    @LocalServerPort int port;
    private HttpRequest request(boolean stream) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/anthropic/v1/messages"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .header("x-api-key", "HEADER-KEY-MUST-NOT-LOG")
                .header("x-test-mode", stream ? "stream" : "unary")
                .POST(HttpRequest.BodyPublishers.ofString(stream ? REQUEST.replace("\"model\":", "\"stream\":true,\"model\":") : REQUEST)).build();
    }
    @Test void debugLogsRecentPromptAndReplyWithoutAlteringForwardedBytes() throws Exception {
        try (Capture logs = new Capture(Level.DEBUG); HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request(false), HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).isEqualTo(RESPONSE);
            assertThat(receivedBody).isEqualTo(REQUEST);
            logs.await(2);
            assertThat(logs.text()).contains("phase=request", "phase=response", "안녕하세요", "반갑습니다", "[REDACTED]",
                    "===== REQUEST >>> anthropic · claude-test =====", "===== RESPONSE <<< anthropic · claude-test =====",
                    "method=POST path=/anthropic/v1/messages stream=false", "status=200 outcome=success",
                    response.headers().firstValue("x-gw-request-id").orElseThrow())
                    .doesNotContain("SYSTEM-CONTEXT", "OLD-HISTORY", "HEADER-KEY-MUST-NOT-LOG", "sk-secret123456789");
            assertThat(logs.events).allMatch(e -> e.getLevel() == Level.DEBUG);
        }
    }
    @Test void infoNeverEmitsConversationContent() throws Exception {
        try (Capture logs = new Capture(Level.INFO); HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request(false), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            Thread.sleep(100);
            assertThat(logs.events).isEmpty();
        }
    }
    @Test void traceIncludesBoundedHistoryButStillRedactsSecrets() throws Exception {
        try (Capture logs = new Capture(Level.TRACE); HttpClient client = HttpClient.newHttpClient()) {
            client.send(request(false), HttpResponse.BodyHandlers.ofString());
            logs.await(2);
            assertThat(logs.text()).contains("SYSTEM-CONTEXT", "OLD-HISTORY", "PREVIOUS-REPLY", "[REDACTED]")
                    .doesNotContain("sk-secret123456789", "HEADER-KEY-MUST-NOT-LOG");
            assertThat(logs.events).allMatch(e -> e.getLevel() == Level.TRACE);
        }
    }
    @Test void contentObservationDoesNotBufferStreamingAndLogsReassembledUtf8Once() throws Exception {
        release = new CountDownLatch(1);
        try (Capture logs = new Capture(Level.DEBUG); HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request(true), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertThat(new String(stream.readNBytes(FIRST.getBytes(StandardCharsets.UTF_8).length), StandardCharsets.UTF_8)).isEqualTo(FIRST);
                assertThat(logs.events).hasSize(1); // Request only; final output is still blocked upstream.
                release.countDown();
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(LAST);
            }
            logs.await(2);
            assertThat(logs.text()).contains("안녕하세요", "phase=response", "partial=false");
        } finally { release.countDown(); }
    }
    @Test void parallelToolSecretsAreMaskedWithoutChangingProxiedSse() throws Exception {
        try (Capture logs = new Capture(Level.TRACE); HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/openai/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer HEADER-KEY-MUST-NOT-LOG")
                    .header("x-test-mode", "parallel-tools")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"gpt-test","stream":true,"messages":[{"role":"user","content":"login and weather"}]}
                            """)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(PARALLEL_TOOLS).contains("PRIVATE_PASSWORD");
            logs.await(2);
            assertThat(logs.text()).contains("phase=response", "login", "weather", "Seoul", "[REDACTED]")
                    .doesNotContain("PRIVATE_PASSWORD", "HEADER-KEY-MUST-NOT-LOG");
        }
    }
    private static class Capture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger("gateway.content");
        private final Level previous = logger.getLevel();
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        Capture(Level level) { start(); logger.setLevel(level); logger.addAppender(this); }
        protected void append(ILoggingEvent event) { events.add(event); }
        String text() { return String.join("\n", events.stream().map(ILoggingEvent::getFormattedMessage).toList()); }
        void await(int count) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (events.size() < count && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(events).hasSize(count);
        }
        public void close() { logger.detachAppender(this); logger.setLevel(previous); stop(); }
    }
}
