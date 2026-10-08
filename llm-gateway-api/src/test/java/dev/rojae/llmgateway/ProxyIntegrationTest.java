package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {

    // 컨텍스트가 뜨기 전에 확실히 준비되도록 static 초기화 블록에서 띄운다.
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

    private static final String UNARY_BODY =
            "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
    private static final String STREAM_BODY =
            "{\"model\":\"gpt-4o-mini\",\"stream\":true,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";

    @Test
    @DisplayName("비스트리밍: 본문을 그대로 통과시키고 비용/모델을 헤더에 담는다")
    void unaryPassthroughAddsCostHeaders() {
        ResponseEntity<String> response = client().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(UNARY_BODY)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("pong");

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("x-gw-request-id")).isNotBlank();
        assertThat(headers.getFirst("x-gw-model")).isEqualTo("gpt-4o-mini");
        // prompt 11 + completion 3 토큰이므로 0이 아닌 비용이 계산되어야 한다.
        assertThat(headers.getFirst("x-gw-cost-usd")).isNotNull();
        assertThat(Double.parseDouble(headers.getFirst("x-gw-cost-usd"))).isGreaterThan(0.0);
        assertThat(headers.getFirst("x-gw-upstream-ms")).isNotNull();
    }

    @Test
    @DisplayName("스트리밍: 버퍼링 차단 헤더가 붙고 usage 옵션이 주입된다")
    void streamingSetsNoBufferingHeadersAndInjectsUsageOption() {
        AtomicReference<HttpHeaders> seen = new AtomicReference<>();

        client().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(STREAM_BODY)
                .exchangeToMono(response -> {
                    seen.set(response.headers().asHttpHeaders());
                    // 앞의 두 청크만 확인하고 끊는다. 전체를 다 읽을 필요가 없다.
                    return response.bodyToFlux(DataBuffer.class)
                            .take(2)
                            .doOnNext(DataBufferUtils::release)
                            .then();
                })
                .block(Duration.ofSeconds(10));

        HttpHeaders headers = seen.get();
        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("x-gw-request-id")).isNotBlank();
        // nginx/ingress 가 SSE를 모아두지 못하게 막는 헤더들
        assertThat(headers.getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(headers.getCacheControl()).contains("no-cache");
        // 게이트웨이가 stream_options.include_usage 를 넣어줬는지 (업스트림이 실제로 받은 본문 확인)
        assertThat(UPSTREAM.lastRequestBody.replace(" ", "")).contains("\"include_usage\":true");
    }

    @Test
    @DisplayName("★ 취소 전파: 클라이언트가 끊으면 업스트림 연결도 끊긴다")
    void clientCancellationPropagatesToUpstream() throws InterruptedException {
        int before = UPSTREAM.disconnects.get();

        // 청크 두 개만 받고 구독을 취소한다 = 사용자가 브라우저를 닫은 상황.
        client().post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(STREAM_BODY)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .take(2)
                .doOnNext(DataBufferUtils::release)
                .then()
                .block(Duration.ofSeconds(10));

        // 업스트림(가짜 서버)이 소켓 쓰기에 실패해야 한다.
        // 이게 실패하면 GPU가 아무도 읽지 않을 토큰을 계속 만들고 있다는 뜻이다.
        boolean observed = UPSTREAM.awaitDisconnects(before + 1, Duration.ofSeconds(10));
        assertThat(observed)
                .as("클라이언트 취소가 업스트림까지 전파되어야 한다")
                .isTrue();
    }

}
