package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.read-timeout=250ms")
class StreamFailureTest {
    private static final ServerSocket UPSTREAM;
    private static final java.util.concurrent.ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final String START = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"provider-model\",\"usage\":{\"input_tokens\":12}}}\n\n";
    static {
        try {
            UPSTREAM = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            EXECUTOR.submit(() -> {
                while (!UPSTREAM.isClosed()) {
                    try {
                        Socket socket = UPSTREAM.accept();
                        EXECUTOR.submit(() -> respond(socket));
                    } catch (Exception ignored) { return; }
                }
            });
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void respond(Socket socket) {
        try (socket) {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            boolean afterStart = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                afterStart |= line.equalsIgnoreCase("x-test-phase: after");
            }
            var out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            if (afterStart) {
                byte[] bytes = START.getBytes(StandardCharsets.UTF_8);
                out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            Thread.sleep(1500); // Provider never completes its SSE body; the gateway read timeout must surface.
        } catch (Exception ignored) { }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getLocalPort() + "/v1");
        registry.add("gateway.upstream-api-key", () -> "");
    }
    @AfterAll static void close() throws Exception { UPSTREAM.close(); EXECUTOR.shutdownNow(); }
    @LocalServerPort int port;

    private HttpRequest request(String phase) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("x-test-phase", phase).header("x-api-key", "test-personal-key")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"requested-model\",\"stream\":true}")).build();
    }
    @Test void failureBeforeFirstByteReturns502AndOneFailureRecord() throws Exception {
        try (Records records = new Records(); HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request("before"), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body()).contains("bad_gateway");
            JsonNode record = records.awaitOne();
            assertThat(record.path("status").asInt()).isEqualTo(502);
            assertThat(record.path("upstreamError").asText()).isNotEmpty();
            assertThat(record.path("clientDisconnected").asBoolean()).isFalse();
        }
    }
    @Test void failureAfterFirstByteAbortsTransportAndPreservesUsageOnce() throws Exception {
        try (Records records = new Records(); HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request("after"), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var body = response.body()) {
                assertThat(new String(body.readNBytes(START.getBytes(StandardCharsets.UTF_8).length), StandardCharsets.UTF_8)).isEqualTo(START);
                assertThatThrownBy(body::readAllBytes).isInstanceOf(java.io.IOException.class);
            }
            JsonNode record = records.awaitOne();
            assertThat(record.path("status").asInt()).isEqualTo(200);
            assertThat(record.path("upstreamError").asText()).isNotEmpty();
            assertThat(record.path("clientDisconnected").asBoolean()).isFalse();
            assertThat(record.path("promptTokens").asInt()).isEqualTo(12);
            assertThat(record.path("model").asText()).isEqualTo("provider-model");
        }
    }
    private static class Records extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger("gateway.access");
        private final List<String> lines = new CopyOnWriteArrayList<>();
        Records() { start(); logger.addAppender(this); }
        @Override protected void append(ILoggingEvent event) { lines.add(event.getFormattedMessage()); }
        JsonNode awaitOne() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (lines.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            Thread.sleep(100); // Allow duplicate termination callbacks to run before checking cardinality.
            assertThat(lines).hasSize(1);
            return new ObjectMapper().readTree(lines.getFirst());
        }
        public void close() { logger.detachAppender(this); stop(); }
    }
}
