package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/** Real HTTP contract tests: the provider, not a gateway key registry, validates each user's key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonalKeyProxyTest {
    private static final String ALICE = "test-anthropic-key-alice";
    private static final String BOB = "test-anthropic-key-bob";
    private static final String BODY = """
            {"model":"claude-haiku-4-5","max_tokens":32,
             "system":[{"type":"text","text":"Help","cache_control":{"type":"ephemeral"}}],
             "messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool_1","content":"ok"}]}],
             "tools":[{"name":"read","description":"Read file","input_schema":{"type":"object"}}]}
            """;
    private static final String AUTH_ERROR = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
    private static final AtomicReference<String> RECEIVED_URI = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_BODY = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_KEY = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer UPSTREAM;
    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.setExecutor(EXECUTOR);
            UPSTREAM.createContext("/v1/", exchange -> {
                String uri = exchange.getRequestURI().toString();
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String key = exchange.getRequestHeaders().getFirst("x-api-key");
                RECEIVED_URI.set(uri);
                RECEIVED_BODY.set(request);
                RECEIVED_KEY.set(key);
                String body;
                int status = 200;
                String type = "application/json";
                if (!ALICE.equals(key) && !BOB.equals(key)) {
                    status = 401;
                    body = AUTH_ERROR;
                } else if (uri.contains("count_tokens")) {
                    body = "{\"input_tokens\":123}";
                } else if (uri.startsWith("/v1/models")) {
                    body = "{\"data\":[{\"id\":\"claude-haiku-4-5\",\"type\":\"model\"}],\"has_more\":false}";
                } else if (request.contains("compression-test")) {
                    type = "text/event-stream";
                    body = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-haiku-4-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n"
                            + "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":3}}\n\n"
                            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
                } else if (request.contains("\"stream\":true")) {
                    type = "text/event-stream";
                    body = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"provider unavailable\"}}\n\n";
                } else {
                    body = "{\"type\":\"message\",\"model\":\"claude-haiku-4-5\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                String encoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
                if (encoding != null && encoding.contains("gzip")) {
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes); }
                    bytes = compressed.toByteArray();
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.getResponseHeaders().set("retry-after", "2");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            UPSTREAM.start();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1");
        registry.add("gateway.upstream-api-key", () -> "");
        registry.add("gateway.key-id-secret", () -> "test-attribution-secret-32-bytes-long");
    }

    @AfterAll
    static void close() { UPSTREAM.stop(0); EXECUTOR.shutdownNow(); }

    @LocalServerPort int port;
    private WebClient client() { return WebClient.create("http://127.0.0.1:" + port); }
    private ResponseEntity<String> post(String path, String key, String body) {
        return client().post().uri(URI.create("http://127.0.0.1:" + port + path)).header("x-api-key", key)
                .header("x-gw-key-id", "forged-user")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(body).exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
    }

    @Test
    void preservesNativeBodyAndRawQuery() {
        assertThat(post("/v1/messages?beta=true&tag=a%2Fb", ALICE, BODY).getStatusCode().value()).isEqualTo(200);
        assertThat(RECEIVED_URI.get()).isEqualTo("/v1/messages?beta=true&tag=a%2Fb");
        assertThat(RECEIVED_BODY.get()).isEqualTo(BODY);
        assertThat(RECEIVED_KEY.get()).isEqualTo(ALICE);
    }

    @Test
    void forwardsClaudeCodeContextsLargerThanDefaultWebfluxBuffer() {
        String body = "{\"model\":\"claude-haiku-4-5\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":\""
                + "context ".repeat(40_000) + "\"}]}";
        assertThat(post("/v1/messages", ALICE, body).getStatusCode().value()).isEqualTo(200);
        assertThat(RECEIVED_BODY.get()).isEqualTo(body);
    }

    @Test
    void providerValidatesIndividualKeysWithoutGatewayRegistration() throws Exception {
        try (AccessLogCapture logs = new AccessLogCapture()) {
            var alice = post("/v1/messages", ALICE, BODY);
            assertThat(RECEIVED_KEY.get()).isEqualTo(ALICE);
            var bob = post("/v1/messages", BOB, BODY);
            assertThat(RECEIVED_KEY.get()).isEqualTo(BOB);
            var again = post("/v1/messages", ALICE, BODY);
            JsonNode a = logs.awaitRecord(alice.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            JsonNode b = logs.awaitRecord(bob.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            JsonNode a2 = logs.awaitRecord(again.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(a.path("keyId").asText()).startsWith("key_").isNotEqualTo("forged-user");
            assertThat(a.path("keyId").asText()).isNotEqualTo(b.path("keyId").asText());
            assertThat(a.path("keyId").asText()).isEqualTo(a2.path("keyId").asText());
            assertThat(a.toString() + b + a2).doesNotContain(ALICE, BOB);
            assertThat(alice.getHeaders().toString()).doesNotContain(ALICE, BOB);
        }
    }

    @Test
    void invalidProviderKeyReturnsProvider401Unchanged() {
        var response = post("/v1/messages", "invalid-key", BODY);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(AUTH_ERROR);
        assertThat(response.getHeaders().getFirst("retry-after")).isEqualTo("2");
    }

    @Test
    void forwardsTokenCountingWithoutBillingItAsGeneratedTokens() throws Exception {
        try (AccessLogCapture logs = new AccessLogCapture()) {
            var response = post("/v1/messages/count_tokens?beta=true", BOB, BODY);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("{\"input_tokens\":123}");
            assertThat(RECEIVED_URI.get()).isEqualTo("/v1/messages/count_tokens?beta=true");
            assertThat(RECEIVED_KEY.get()).isEqualTo(BOB);
            JsonNode record = logs.awaitRecord(response.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(record.path("costUsd").isNull()).isTrue();
            assertThat(record.path("promptTokens").isNull()).isTrue();
        }
    }

    @Test
    void forwardsModelDiscoveryWithPaginationAndPersonalKey() {
        var response = client().get().uri(URI.create("http://127.0.0.1:" + port + "/v1/models?limit=1000&after_id=model%2Fone"))
                .header("x-api-key", ALICE).exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("claude-haiku-4-5");
        assertThat(RECEIVED_URI.get()).isEqualTo("/v1/models?limit=1000&after_id=model%2Fone");
        assertThat(RECEIVED_KEY.get()).isEqualTo(ALICE);
    }

    @Test
    void providerSseErrorIsForwardedAndRecordedAsFailureDespiteHttp200() throws Exception {
        try (AccessLogCapture logs = new AccessLogCapture()) {
            var response = post("/v1/messages", ALICE, "{\"model\":\"claude-haiku-4-5\",\"stream\":true}");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("event: error", "overloaded_error");
            JsonNode record = logs.awaitRecord(response.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(record.path("upstreamError").asText()).isEqualTo("overloaded_error");
        }
    }

    @Test
    void claudeCodeCompressionNegotiationDoesNotHideUsage() throws Exception {
        try (AccessLogCapture logs = new AccessLogCapture()) {
            var response = client().post().uri("/v1/messages")
                    .header("x-api-key", ALICE)
                    .header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br, zstd")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue("{\"model\":\"claude-haiku-4-5\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"compression-test\"}]}")
                    .exchangeToMono(r -> r.toEntity(byte[].class)).block(Duration.ofSeconds(10));
            JsonNode record = logs.awaitRecord(response.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(record.path("promptTokens").asInt()).isEqualTo(12);
            assertThat(record.path("completionTokens").asInt()).isEqualTo(3);
            assertThat(record.path("costUsd").isNumber()).isTrue();
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isNull();
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("event: message_stop");
        }
    }
}
