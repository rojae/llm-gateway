package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 업스트림에 닿지도 못하는 경우.
 *
 * <p>아직 본문을 한 바이트도 쓰지 않았으므로 502를 <b>제대로</b> 돌려줄 수 있다.
 * 스트리밍이 시작된 뒤에는 이게 불가능하다 — 상태 코드는 이미 나갔기 때문이다.
 * 게이트웨이에서 폴백이 "스트리밍 시작 전"에만 가능한 이유가 여기에 있다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 127.0.0.1:1 은 아무도 듣고 있지 않다.
                "gateway.upstream-base-url=http://127.0.0.1:1/v1",
                "gateway.connect-timeout=300ms"
        })
class BadGatewayTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("업스트림에 연결할 수 없으면 502와 request-id를 돌려준다")
    void unreachableUpstreamReturns502() {
        ResponseEntity<String> response = WebClient.create("http://127.0.0.1:" + port)
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"model\":\"gpt-4o-mini\",\"messages\":[]}")
                .retrieve()
                .onStatus(status -> true, r -> Mono.empty())
                .toEntity(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).contains("bad_gateway");
        assertThat(response.getHeaders().getFirst("x-gw-request-id")).isNotBlank();
    }
}
