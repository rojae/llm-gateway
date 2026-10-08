package dev.rojae.llmgateway.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * demo 프로파일에서만 뜨는 가짜 업스트림. OpenAI API 를 흉내 낸다.
 *
 * <p>실제 API 키 없이 게이트웨이를 끝까지 돌려보기 위한 것이다.
 * {@code --spring.profiles.active=demo} 로 실행하면 게이트웨이가 자기 자신의
 * 이 엔드포인트를 업스트림으로 바라본다.
 *
 * <p>응답을 {@code ServerHttpResponse} 에 직접 쓴다. Spring 의 SSE 인코더를 거치면
 * "data:" 접두사가 한 번 더 붙어버리므로, 프레임을 우리가 통째로 만들어 넘긴다.
 */
@RestController
@Profile("demo")
@Tag(name = "Demo upstream", description = "demo 프로파일 전용 가짜 업스트림. 게이트웨이가 자기 자신의 이 경로를 바라본다.")
public class DemoUpstreamController {

    @Operation(
            summary = "가짜 chat completions (demo 프로파일 전용)",
            description = """
                    OpenAI 응답을 흉내 낸다. 직접 호출할 일은 없고 게이트웨이가 업스트림으로 호출한다.
                    `stream: true` 면 200ms 간격으로 40개 토큰(총 8초)을 흘려보내므로,
                    흐르는 도중에 `curl -N` 을 Ctrl+C 로 끊으면 취소 전파를 눈으로 볼 수 있다.""")
    @PostMapping("/stub/v1/chat/completions")
    public Mono<Void> completions(@RequestBody(required = false) String body,
                                  ServerHttpResponse response) {

        String compact = (body == null ? "" : body).replace(" ", "").replace("\n", "");
        boolean streaming = compact.contains("\"stream\":true");
        boolean wantsUsage = compact.contains("\"include_usage\":true");

        if (!streaming) {
            byte[] json = ("""
                    {"id":"chatcmpl-demo","object":"chat.completion","model":"gpt-4o-mini",\
                    "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},\
                    "finish_reason":"stop"}],\
                    "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}""")
                    .getBytes(StandardCharsets.UTF_8);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getHeaders().setContentLength(json.length);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(json)));
        }

        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);

        // 200ms 간격으로 40개의 토큰 = 총 8초.
        // 흐르는 도중에 curl 을 Ctrl+C 로 끊어보면 취소 전파를 눈으로 확인할 수 있다.
        Flux<String> tokens = Flux.interval(Duration.ofMillis(200))
                .take(40)
                .map(i -> "data: {\"id\":\"chatcmpl-demo\",\"model\":\"gpt-4o-mini\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + " \"}}]}\n\n");

        Flux<String> tail = wantsUsage
                ? Flux.just("data: {\"id\":\"chatcmpl-demo\",\"model\":\"gpt-4o-mini\",\"choices\":[],"
                                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":40,\"total_tokens\":51}}\n\n",
                        "data: [DONE]\n\n")
                : Flux.just("data: [DONE]\n\n");

        Flux<DataBuffer> frames = Flux.concat(tokens, tail)
                .map(frame -> response.bufferFactory().wrap(frame.getBytes(StandardCharsets.UTF_8)))
                // 계측은 "생성하는 쪽"인 이 Flux 에 걸어야 한다.
                // writeAndFlushWith 가 돌려주는 Mono<Void> 에 걸면 취소 신호를 놓친다.
                .doFinally(signal -> System.out.println(
                        ">>> [demo-upstream] 생성 종료: signal=" + signal
                                + (signal == SignalType.CANCEL
                                        ? "  ← 클라이언트가 끊겨 생성을 중단했다 (= GPU 절약)"
                                        : "")));

        // writeAndFlushWith: 안쪽 Publisher 하나가 끝날 때마다 flush 한다 = 청크 단위 즉시 전송.
        return response.writeAndFlushWith(frames.map(Mono::just));
    }
}
