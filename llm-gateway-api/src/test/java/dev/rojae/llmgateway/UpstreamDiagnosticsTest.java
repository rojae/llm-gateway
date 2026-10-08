package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpstreamDiagnosticsTest {
    private static final String SPEND = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"private spending detail\",\"details\":{\"error_code\":\"enforced_spend_limit_reached\"}}}";
    private static final String RATE = "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"input_token_rate_limit_exceeded\",\"message\":\"private rate detail\"}}";
    private static final String MALICIOUS = "{\"error\":{\"type\":\"secret_type\",\"code\":\"secret_code\",\"message\":\"secret_body\"}}";
    private static final HttpServer UPSTREAM;
    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String scenario = exchange.getRequestHeaders().getFirst("x-test-scenario");
                String body = "spend".equals(scenario) ? SPEND : "malicious".equals(scenario) ? MALICIOUS : RATE;
                var headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/json");
                headers.set("request-id", "diagnostics-request");
                headers.set("x-should-retry", "false");
                if (!"spend".equals(scenario)) {
                    headers.set("retry-after", "malicious".equals(scenario) ? "secret_header" : "12");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            UPSTREAM.start();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1";
        registry.add("gateway.providers.anthropic-base-url", () -> base);
        registry.add("gateway.providers.openai-base-url", () -> base);
        registry.add("gateway.key-id-secret", () -> "diagnostics-test-attribution-secret");
    }
    @AfterAll static void close() { UPSTREAM.stop(0); }
    @LocalServerPort int port;

    private ResponseEntity<String> request(String path, boolean stream, String scenario) {
        var client = WebClient.create("http://127.0.0.1:" + port);
        var request = path.endsWith("models") ? client.get().uri(path) : client.post().uri(path)
                .bodyValue("{\"model\":\"claude-haiku-4-5\",\"stream\":" + stream + "}");
        return request.header("x-api-key", "personal-test-key")
                .header("Authorization", "Bearer personal-test-key")
                .header("Content-Type", "application/json").header("x-test-scenario", scenario)
                .exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
    }

    @Test void streamHttp429CapturesSpendLimitWithoutInventingRetryOrUsage() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var result = request("/anthropic/v1/messages", true, "spend");
            assertThat(result.getStatusCode().value()).isEqualTo(429);
            assertThat(result.getBody()).isEqualTo(SPEND);
            assertThat(result.getHeaders().getFirst("x-should-retry")).isEqualTo("false");
            assertThat(result.getHeaders().getFirst("retry-after")).isNull();
            var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(log.path("upstreamError").asText()).isEqualTo("rate_limit_error");
            assertThat(log.path("upstreamErrorReason").asText()).isEqualTo("spend_limit");
            assertThat(log.path("retryAfterSeconds").isNull()).isTrue();
            assertThat(log.path("totalTokens").isNull()).isTrue();
            assertThat(log.toString()).doesNotContain("private spending detail");
        }
    }

    @Test void nonstreamAndMetadata429PreserveBodyAndRetryHeaderWithSafeDiagnostics() throws Exception {
        for (String path : new String[]{"/anthropic/v1/messages", "/openai/v1/models"}) {
            try (var logs = new AccessLogCapture()) {
                var result = request(path, false, "rate");
                assertThat(result.getStatusCode().value()).isEqualTo(429);
                assertThat(result.getBody()).isEqualTo(RATE);
                assertThat(result.getHeaders().getFirst("retry-after")).isEqualTo("12");
                var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
                assertThat(log.path("upstreamError").asText()).isEqualTo("rate_limit_error");
                assertThat(log.path("upstreamErrorReason").asText()).isEqualTo("input_token_rate");
                assertThat(log.path("retryAfterSeconds").asLong()).isEqualTo(12);
                assertThat(log.path("totalTokens").isNull()).isTrue();
                assertThat(log.toString()).doesNotContain("private rate detail");
            }
        }
    }

    @Test void untrustedErrorAndRetryStringsNeverReachAccessLog() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            try (var logs = new AccessLogCapture()) {
                var result = request("/anthropic/v1/messages", stream, "malicious");
                assertThat(result.getBody()).isEqualTo(MALICIOUS);
                assertThat(result.getHeaders().getFirst("retry-after")).isEqualTo("secret_header");
                var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
                assertThat(log.path("upstreamError").asText()).isEqualTo("upstream_error");
                assertThat(log.path("upstreamErrorReason").asText()).isEqualTo("rate_limit");
                assertThat(log.path("retryAfterSeconds").isNull()).isTrue();
                assertThat(log.toString()).doesNotContain("secret_type", "secret_code", "secret_body", "secret_header", "personal-test-key");
            }
        }
    }
}
