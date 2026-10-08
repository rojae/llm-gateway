package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/** Provider URLs, credentials and native wire formats stay independent in one process. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiProviderTest {
    private static final AtomicReference<String> RECEIVED = new AtomicReference<>();
    private static final String USAGE = "\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":80},\"output_tokens\":20,\"total_tokens\":120}";
    private static final String RESPONSE = "{\"id\":\"resp_local\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o-mini\"," + USAGE + ",\"output\":[]}";
    private static final HttpServer UPSTREAM;
    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                String uri = exchange.getRequestURI().toString();
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
                RECEIVED.set(uri + "\n" + auth + "\n" + apiKey + "\n"
                        + exchange.getRequestHeaders().getFirst("OpenAI-Project") + "\n" + request);
                String body = RESPONSE;
                int status = 200;
                String type = "application/json";
                if (uri.startsWith("/anthropic/")) {
                    body = "{\"model\":\"claude-haiku-4-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}";
                    if (!"anthropic-personal".equals(apiKey) || auth != null) status = 401;
                } else if (!("Bearer openai-alice".equals(auth) || "Bearer openai-bob".equals(auth)) || apiKey != null) {
                    status = 401;
                    body = "{\"error\":{\"message\":\"invalid personal key\",\"type\":\"invalid_request_error\"}}";
                } else if (uri.contains("/models")) {
                    body = "{\"data\":[{\"id\":\"personal-model\"}]}";
                } else if (uri.contains("/chat/completions")) {
                    body = "{\"model\":\"gpt-4o-mini\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":80},\"completion_tokens\":20,\"total_tokens\":120}}";
                } else if (request.contains("\"stream\":true")) {
                    type = "text/event-stream";
                    if (request.contains("truncated-test")) {
                        body = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n";
                    } else if (request.contains("fail-test")) {
                        body = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"private text\"}}}\n\n";
                    } else {
                        body = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"안녕하세요\"}\n\n"
                                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":" + RESPONSE + "}\n\n";
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.getResponseHeaders().set("x-request-id", "provider-request");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            UPSTREAM.start();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("gateway.providers.openai-base-url", () -> base + "/openai/v1");
        registry.add("gateway.providers.anthropic-base-url", () -> base + "/anthropic/v1");
        // Even a legacy managed configuration cannot override the explicit personal-key routes.
        registry.add("gateway.upstream-api-key", () -> "legacy-server-secret");
        registry.add("gateway.key-id-secret", () -> "openai-test-attribution-secret-32-bytes");
    }

    @AfterAll static void close() { UPSTREAM.stop(0); }
    @LocalServerPort int port;

    private ResponseEntity<String> post(String path, String key, String body) {
        return WebClient.create().post().uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + key).header("x-api-key", "wrong-provider-secret")
                .header("OpenAI-Project", "project-local").header("Content-Type", "application/json")
                .bodyValue(body).exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
    }

    @Test void responsesPreserveBodyQueryAndProjectWithPersonalKey() {
        String body = "{\"model\":\"gpt-4o-mini\",\"input\":\"private prompt\",\"tools\":[],\"store\":false}";
        var result = post("/openai/v1/responses?tag=a%2Fb", "openai-alice", body);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(RECEIVED.get()).isEqualTo("/openai/v1/responses?tag=a%2Fb\nBearer openai-alice\nnull\nproject-local\n" + body);
        assertThat(result.getBody()).isEqualTo(RESPONSE);
        assertThat(result.getHeaders().getFirst("x-request-id")).isEqualTo("provider-request");
        assertThat(result.getHeaders().getFirst("x-gw-cost-usd")).isEqualTo("0.000021");
    }

    @Test void personalKeysAreSeparatelyAttributedForChatCompletions() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var a = post("/openai/v1/chat/completions", "openai-alice", "{\"model\":\"gpt-4o-mini\"}");
            var b = post("/openai/v1/chat/completions", "openai-bob", "{\"model\":\"gpt-4o-mini\"}");
            assertThat(a.getStatusCode().value()).isEqualTo(200);
            assertThat(b.getStatusCode().value()).isEqualTo(200);
            var ar = logs.awaitRecord(a.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            var br = logs.awaitRecord(b.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(ar.path("provider").asText()).isEqualTo("openai");
            assertThat(ar.path("keyId").asText()).isNotEqualTo(br.path("keyId").asText());
            assertThat(ar.path("promptTokens").asInt()).isEqualTo(20);
            assertThat(ar.path("cacheReadTokens").asInt()).isEqualTo(80);
            assertThat(ar.path("totalTokens").asInt()).isEqualTo(120);
            assertThat(ar.toString() + br).doesNotContain("openai-alice", "openai-bob", "legacy-server-secret");
        }
    }

    @Test void responsesStreamingKeepsNativeEventsAndRecordsFinalUsage() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var result = post("/openai/v1/responses", "openai-alice", "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"input\":\"hello\"}");
            assertThat(result.getBody()).contains("event: response.completed", "안녕하세요");
            assertThat(RECEIVED.get()).doesNotContain("stream_options");
            var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(log.path("totalTokens").asInt()).isEqualTo(120);
            assertThat(log.path("costUsd").asDouble()).isCloseTo(.000021, org.assertj.core.data.Offset.offset(1e-10));
            assertThat(log.path("outcome").asText()).isEqualTo("success");
        }
    }

    @Test void failedEventIsNotLoggedAsSuccessEvenWithHttp200() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var result = post("/openai/v1/responses", "openai-alice", "{\"stream\":true,\"input\":\"fail-test\"}");
            assertThat(result.getStatusCode().value()).isEqualTo(200);
            assertThat(result.getBody()).contains("private text");
            var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(log.path("outcome").asText()).isEqualTo("upstream_error");
            assertThat(log.toString()).doesNotContain("private text");
            assertThat(log.path("costUsd").isNull()).isTrue();
        }
    }

    @Test void invalidPersonalKeyIsRejectedByProvider() {
        var response = post("/openai/v1/responses", "invalid", "{}");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("invalid personal key");
    }

    @Test void cleanEofWithoutTerminalEventIsRecordedAsIncomplete() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var response = post("/openai/v1/responses", "openai-alice", "{\"stream\":true,\"input\":\"truncated-test\"}");
            assertThat(response.getBody()).contains("partial");
            var log = logs.awaitRecord(response.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(log.path("outcome").asText()).isEqualTo("upstream_error");
            assertThat(log.path("upstreamError").asText()).isEqualTo("stream_incomplete");
        }
    }

    @Test void swaggerExposesPersonalCredentialsAndBothProviderPaths() throws Exception {
        var document = WebClient.create("http://127.0.0.1:" + port).get().uri("/openapi.json")
                .retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block(Duration.ofSeconds(10));
        assertThat(document.at("/components/securitySchemes/anthropicKey/name").asText()).isEqualTo("x-api-key");
        assertThat(document.path("paths").has("/openai/v1/responses")).isTrue();
        assertThat(document.path("paths").has("/anthropic/v1/messages")).isTrue();
    }

    @Test void modelsUsesOpenaiPersonalKeyAndIsNotBilled() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var result = WebClient.create("http://127.0.0.1:" + port).get().uri("/openai/v1/models")
                    .header("Authorization", "Bearer openai-bob")
                    .exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
            assertThat(result.getStatusCode().value()).isEqualTo(200);
            assertThat(result.getBody()).contains("personal-model");
            var log = logs.awaitRecord(result.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(5));
            assertThat(log.path("usageAvailability").asText()).isEqualTo("not_applicable");
            assertThat(log.path("costUsd").isNull()).isTrue();
        }
    }

    @Test void anthropicAndOpenaiUseDifferentKeysInSameGateway() {
        var result = WebClient.create("http://127.0.0.1:" + port).post().uri("/anthropic/v1/messages")
                .header("x-api-key", "anthropic-personal").header("Authorization", "Bearer wrong-openai-key")
                .header("Content-Type", "application/json").bodyValue("{\"model\":\"claude-haiku-4-5\"}")
                .exchangeToMono(r -> r.toEntity(String.class)).block(Duration.ofSeconds(10));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(RECEIVED.get()).startsWith("/anthropic/v1/messages\nnull\nanthropic-personal\n");
        assertThat(post("/openai/v1/responses", "openai-alice", "{}").getStatusCode().value()).isEqualTo(200);
    }
}
