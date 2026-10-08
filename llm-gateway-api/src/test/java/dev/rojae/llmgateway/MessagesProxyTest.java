package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Anthropic 네이티브 경로({@code /v1/messages}) 통합 테스트. passthrough 모드.
 *
 * <p>이 경로가 존재하는 이유가 Claude Code 이므로, Claude Code 가 실제로 보내는 것들
 * (귀속 헤더, prompt caching, 네이티브 SSE 포맷)을 중심으로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessagesProxyTest {

    static final StubUpstream UPSTREAM;

    static {
        try {
            UPSTREAM = new StubUpstream();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream-base-url", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void shutdown() {
        UPSTREAM.close();
    }

    @LocalServerPort
    int port;

    private WebClient client() {
        return WebClient.create("http://127.0.0.1:" + port);
    }

    private static final String UNARY = """
            {"model":"claude-sonnet-5","max_tokens":256,\
            "messages":[{"role":"user","content":"ping"}]}""";
    private static final String STREAM = """
            {"model":"claude-sonnet-5","max_tokens":256,"stream":true,\
            "messages":[{"role":"user","content":"ping"}]}""";

    @Test
    @DisplayName("비스트리밍: 본문을 그대로 통과시키고 캐시 단가까지 반영한 비용을 헤더에 담는다")
    void unaryPassthroughPricesCacheTokens() {
        ResponseEntity<String> response = client().post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(UNARY)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("pong");

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("x-gw-request-id")).isNotBlank();
        assertThat(headers.getFirst("x-gw-model")).isEqualTo("claude-sonnet-5");

        // 스텁 사용량: input 10 / cache write 200 / cache read 1000 / output 5
        // claude-sonnet-5 단가: input $2.00, output $10.00 (100만 토큰당)
        //   10*2.00 + 200*2.00*1.25 + 1000*2.00*0.10 + 5*10.00
        // = 20      + 500           + 200            + 50      = 770 → $0.000770
        // 캐시 배수를 무시하면 $0.002470 이 나온다. 이 숫자가 그 차이를 고정한다.
        assertThat(Double.parseDouble(headers.getFirst("x-gw-cost-usd")))
                .as("캐시 쓰기 1.25배·읽기 0.1배가 반영되어야 한다")
                .isEqualTo(0.000770);
    }

    @Test
    @DisplayName("Anthropic 경로에는 stream_options 를 주입하지 않는다 — 넣으면 400 이 난다")
    void doesNotInjectStreamOptionsOnAnthropic() {
        readStream(STREAM);

        String body = UPSTREAM.lastRequestBody.replace(" ", "");
        assertThat(body).doesNotContain("stream_options");
        assertThat(body).doesNotContain("include_usage");
    }

    @Test
    @DisplayName("anthropic-version 헤더가 없으면 채워 넣는다")
    void fillsAnthropicVersionWhenMissing() {
        client().post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(UNARY)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));

        assertThat(UPSTREAM.lastRequestHeaders.getFirst("anthropic-version"))
                .isEqualTo("2023-06-01");
    }

    @Test
    @DisplayName("호출자가 보낸 anthropic-version 은 덮어쓰지 않는다")
    void preservesCallerAnthropicVersion() {
        client().post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("anthropic-version", "2024-10-22")
                .bodyValue(UNARY)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));

        assertThat(UPSTREAM.lastRequestHeaders.getFirst("anthropic-version"))
                .isEqualTo("2024-10-22");
    }

    @Test
    @DisplayName("anthropic-beta 는 verbatim 으로 전달된다 — 빼면 Claude Code 가 401 을 받는다")
    void forwardsAnthropicBetaVerbatim() {
        String beta = "fine-grained-tool-streaming-2025-05-14,context-1m-2025-08-07";

        client().post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("anthropic-beta", beta)
                .bodyValue(UNARY)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));

        assertThat(UPSTREAM.lastRequestHeaders.getFirst("anthropic-beta")).isEqualTo(beta);
    }

    @Test
    @DisplayName("스트리밍: 버퍼링 차단 헤더가 붙는다")
    void streamingSetsNoBufferingHeaders() {
        HttpHeaders headers = readStream(STREAM);

        assertThat(headers.getFirst("x-gw-request-id")).isNotBlank();
        assertThat(headers.getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(headers.getCacheControl()).contains("no-cache");
    }

    @Test
    @DisplayName("★ 스트리밍 사용량이 두 프레임에 나뉘어 와도 접근 로그에 온전히 남는다")
    void streamingUsageSurvivesSplitFrames() throws Exception {
        try (AccessLogCapture capture = new AccessLogCapture()) {
            AtomicReference<String> requestId = new AtomicReference<>();

            // 스트림을 끝까지 읽어야 message_delta 까지 도착하고 로그가 찍힌다.
            client().post()
                    .uri("/v1/messages")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue(STREAM)
                    .exchangeToMono(response -> {
                        requestId.set(response.headers().asHttpHeaders().getFirst("x-gw-request-id"));
                        return response.bodyToFlux(DataBuffer.class)
                                .doOnNext(DataBufferUtils::release)
                                .then();
                    })
                    .block(Duration.ofSeconds(20));

            JsonNode record = capture.awaitRecord(requestId.get(), Duration.ofSeconds(10));

            assertThat(record.path("format").asText()).isEqualTo("anthropic");
            assertThat(record.path("stream").asBoolean()).isTrue();
            assertThat(record.path("model").asText()).isEqualTo("claude-sonnet-5");
            assertThat(record.path("promptTokens").asInt()).as("message_start 의 입력 토큰").isEqualTo(10);
            assertThat(record.path("completionTokens").asInt()).as("message_delta 의 출력 토큰").isEqualTo(42);
            // 캐시 쓰기 200 이 TTL 별로 쪼개져야 한다 (5분 50 / 1시간 150).
            assertThat(record.path("cacheWriteTokens").asInt()).as("5분 TTL").isEqualTo(50);
            assertThat(record.path("cacheWrite1hTokens").asInt()).as("1시간 TTL").isEqualTo(150);
            assertThat(record.path("cacheReadTokens").asInt()).isEqualTo(1000);
            // 10*2.00 + 50*2.00*1.25 + 150*2.00*2.00 + 1000*2.00*0.10 + 42*10.00
            // = 20     + 125         + 600           + 200            + 420      = 1365
            assertThat(record.path("costUsd").asDouble()).isEqualTo(0.001365);
            assertThat(record.path("clientDisconnected").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("★ Claude Code 귀속 헤더가 접근 로그로 넘어간다 — 세션·에이전트별 비용 집계의 재료")
    void claudeCodeAttributionHeadersReachAccessLog() throws Exception {
        try (AccessLogCapture capture = new AccessLogCapture()) {
            ResponseEntity<Void> response = client().post()
                    .uri("/v1/messages")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("x-claude-code-session-id", "sess-abc123")
                    .header("x-claude-code-request-class", "subagent")
                    .header("x-claude-code-agent-id", "agent-7")
                    .header("x-claude-code-agent-type", "Explore")
                    .header("x-claude-code-parent-agent-id", "agent-1")
                    .header("x-claude-code-prev-tool-durations", "Bash=742;Read=9")
                    .bodyValue(UNARY)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));

            String requestId = response.getHeaders().getFirst("x-gw-request-id");
            JsonNode record = capture.awaitRecord(requestId, Duration.ofSeconds(10));

            assertThat(record.path("sessionId").asText()).isEqualTo("sess-abc123");
            assertThat(record.path("requestClass").asText()).isEqualTo("subagent");
            assertThat(record.path("agentId").asText()).isEqualTo("agent-7");
            assertThat(record.path("agentType").asText()).isEqualTo("Explore");
            assertThat(record.path("parentAgentId").asText()).isEqualTo("agent-1");
            assertThat(record.path("toolDurations").asText()).isEqualTo("Bash=742;Read=9");
        }
    }

    @Test
    @DisplayName("귀속 헤더가 없는 일반 트래픽의 로그 줄에는 그 필드가 아예 없다")
    void omitsAttributionFieldsWhenAbsent() throws Exception {
        try (AccessLogCapture capture = new AccessLogCapture()) {
            ResponseEntity<Void> response = client().post()
                    .uri("/v1/messages")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue(UNARY)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));

            JsonNode record = capture.awaitRecord(
                    response.getHeaders().getFirst("x-gw-request-id"), Duration.ofSeconds(10));

            assertThat(record.has("sessionId")).isFalse();
            assertThat(record.has("agentType")).isFalse();
            // 고정 컬럼은 그대로 있어야 한다 — 집계가 컬럼 존재를 전제한다.
            assertThat(record.has("promptTokens")).isTrue();
            assertThat(record.has("costUsd")).isTrue();
        }
    }

    private HttpHeaders readStream(String body) {
        AtomicReference<HttpHeaders> seen = new AtomicReference<>();
        client().post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(body)
                .exchangeToMono(response -> {
                    seen.set(response.headers().asHttpHeaders());
                    return response.bodyToFlux(DataBuffer.class)
                            .doOnNext(DataBufferUtils::release)
                            .then();
                })
                .block(Duration.ofSeconds(20));
        return seen.get();
    }
}
