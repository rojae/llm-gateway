package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * managed 모드의 자격증명 교체.
 *
 * <p>포맷마다 헤더 이름이 다르다 — OpenAI 는 {@code Authorization: Bearer}, Anthropic 네이티브는
 * {@code x-api-key}. 그리고 <b>반대쪽 헤더를 지우는 것</b>이 이 테스트의 핵심이다.
 * Claude Code 는 두 헤더를 동시에 보낼 수 있으므로, 하나만 덮어쓰면 호출자의 키가
 * 게이트웨이를 통과해 업스트림까지 새어 나간다. 조용히 새기 때문에 아무도 모른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagedKeyTest {

    private static final String GATEWAY_KEY = "sk-gateway-owns-this";
    private static final String CALLER_KEY = "sk-caller-must-not-leak";

    static final StubUpstream UPSTREAM;

    static {
        try {
            UPSTREAM = new StubUpstream();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream-base-url", UPSTREAM::baseUrl);
        registry.add("gateway.upstream-api-key", () -> GATEWAY_KEY);
    }

    @AfterAll
    static void shutdown() {
        UPSTREAM.close();
    }

    @LocalServerPort
    int port;

    private void post(String path, String body, java.util.Map<String, String> headers) {
        WebClient.RequestBodySpec spec = WebClient.create("http://127.0.0.1:" + port)
                .post().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.forEach(spec::header);
        spec.bodyValue(body).retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("★ Anthropic: x-api-key 를 게이트웨이 키로 바꾸고 호출자 Authorization 을 지운다")
    void anthropicUsesApiKeyHeaderAndDropsAuthorization() {
        post("/v1/messages",
                """
                {"model":"claude-sonnet-5","max_tokens":64,\
                "messages":[{"role":"user","content":"ping"}]}""",
                java.util.Map.of(
                        // Claude Code 는 설정에 따라 둘 다 보낼 수 있다.
                        HttpHeaders.AUTHORIZATION, "Bearer " + CALLER_KEY,
                        "x-api-key", CALLER_KEY));

        assertThat(UPSTREAM.lastRequestHeaders.getFirst("x-api-key"))
                .as("Anthropic 네이티브는 x-api-key 를 읽는다")
                .isEqualTo(GATEWAY_KEY);
        assertThat(UPSTREAM.lastRequestHeaders.getFirst("Authorization"))
                .as("호출자 키가 Authorization 으로 새어 나가면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("★ OpenAI: Authorization 을 게이트웨이 키로 바꾸고 호출자 x-api-key 를 지운다")
    void openAiUsesBearerAndDropsApiKeyHeader() {
        post("/v1/chat/completions",
                """
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}""",
                java.util.Map.of(
                        HttpHeaders.AUTHORIZATION, "Bearer " + CALLER_KEY,
                        "x-api-key", CALLER_KEY));

        assertThat(UPSTREAM.lastRequestHeaders.getFirst("Authorization"))
                .isEqualTo("Bearer " + GATEWAY_KEY);
        assertThat(UPSTREAM.lastRequestHeaders.getFirst("x-api-key"))
                .as("호출자 키가 x-api-key 로 새어 나가면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("healthz 가 managed 모드를 보고한다")
    void healthReportsManagedMode() {
        String body = WebClient.create("http://127.0.0.1:" + port)
                .get().uri("/healthz")
                .retrieve().toEntity(String.class)
                .block(Duration.ofSeconds(10))
                .getBody();

        assertThat(body).contains("\"keyMode\":\"managed\"");
        // 키 자체는 절대 노출되지 않아야 한다.
        assertThat(body).doesNotContain(GATEWAY_KEY);
    }
}
