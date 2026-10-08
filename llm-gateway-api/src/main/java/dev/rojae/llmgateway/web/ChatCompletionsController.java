package dev.rojae.llmgateway.web;

import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.openapi.ChatCompletionRequestDoc;
import dev.rojae.llmgateway.openapi.ChatCompletionResponseDoc;
import dev.rojae.llmgateway.openapi.GatewayErrorDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * OpenAI 호환 {@code /v1/chat/completions} 경로.
 *
 * <p>프록시 로직은 {@link ProxyEngine} 에 있다. 이 클래스에 남은 것은 <b>OpenAPI 문서</b>와
 * 어떤 포맷으로 처리할지 지정하는 한 줄뿐이다. Anthropic 경로는 {@link MessagesController} 다.
 *
 * <p>일부러 <b>하지 않는</b> 것들: 라우팅, 캐시, 쿼터, 재시도, 폴백.
 * v0의 목표는 "아무도 눈치채지 못하게 요청 경로에 끼어들어 기록을 남기는 것" 하나뿐이다.
 *
 * <p>틀리기 쉬운 지점이 셋이고, 이 클래스가 존재하는 이유가 사실상 그 셋이다.
 * <ol>
 *   <li><b>버퍼링 금지</b> — 청크는 도착 즉시 그대로 내보낸다. 모아두면 TTFT가 죽는다.</li>
 *   <li><b>취소 전파</b> — 호출자가 끊으면 업스트림 요청도 죽어야 한다.
 *       안 그러면 아무도 읽지 않을 토큰을 GPU가 계속 만들고 요금이 계속 나간다.
 *       WebFlux에서는 이게 거의 공짜다: 아래 Flux 구독이 취소되면
 *       WebClient 의 업스트림 구독도 함께 취소되고 커넥션이 닫힌다.</li>
 *   <li><b>상태 코드 선확정</b> — 본문을 쓰기 전에 업스트림 상태를 먼저 안다.
 *       그래야 429가 "200 안에 숨은 에러"로 둔갑하지 않는다.</li>
 * </ol>
 */
@RestController
@Tag(name = "Proxy", description = "업스트림 LLM 으로 그대로 전달되는 OpenAI 호환 경로")
public class ChatCompletionsController {

    private final ProxyEngine engine;

    public ChatCompletionsController(ProxyEngine engine) {
        this.engine = engine;
    }

    @Operation(
            summary = "Chat completions 프록시",
            description = """
                    요청 본문을 해석하지 않고 업스트림으로 그대로 전달한다. 게이트웨이가 손대는 것은 두 가지뿐이다.

                    1. managed 모드에서는 `Authorization` 헤더를 게이트웨이 키로 덮어쓴다.
                    2. `stream: true` 이고 `gateway.inject-stream-usage=true` 이면                     `stream_options.include_usage` 를 주입한다.                     (이 때문에 마지막 프레임에 `choices` 가 빈 usage 이벤트가 추가된다.)

                    **`stream: true` 응답은 `text/event-stream` 이다.** Swagger UI 는 SSE 를 모아서                     한 번에 보여주므로 첫 토큰 지연이나 취소 전파는 이 화면에서 확인할 수 없다.                     그건 `curl -N` 으로 볼 것.""")
    @ApiResponse(responseCode = "200",
            description = """
                    업스트림 응답. `stream: false` 면 JSON 한 덩어리, `stream: true` 면 SSE 스트림이다.

                    비용·지연시간 헤더는 **비스트리밍 응답에만** 붙는다. 스트리밍에서는 헤더가                     첫 토큰보다 먼저 나가는데 그 값들은 스트림이 끝나야 알 수 있기 때문이다.                     스트리밍 요청의 비용은 접근 로그에서 `x-gw-request-id` 로 찾는다.""",
            headers = {
                    @Header(name = "x-gw-request-id",
                            description = "접근 로그의 같은 요청을 찾는 ID. 항상 붙는다.",
                            schema = @Schema(type = "string")),
                    @Header(name = "x-gw-model",
                            description = "응답 본문에서 읽은 모델 이름. 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "gpt-4o-mini")),
                    @Header(name = "x-gw-cost-usd",
                            description = "토큰 사용량으로 계산한 비용(USD). 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "0.000003")),
                    @Header(name = "x-gw-upstream-ms",
                            description = "업스트림 왕복 시간(ms). 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "61.2")),
                    @Header(name = "X-Accel-Buffering",
                            description = "스트리밍 전용. 중간 프록시가 SSE 를 모아두지 못하게 막는다(`no`).",
                            schema = @Schema(type = "string", example = "no"))
            },
            content = {
                    @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ChatCompletionResponseDoc.class)),
                    @Content(mediaType = "text/event-stream",
                            schema = @Schema(type = "string",
                                    description = "`data: {...}` 프레임의 연속. 마지막은 `data: [DONE]`."))
            })
    @ApiResponse(responseCode = "4XX",
            description = "업스트림이 내려준 에러를 본문 그대로 전달한다(401, 429 등). 형태는 업스트림을 따른다.",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "502",
            description = "업스트림에 닿지 못했다. 아직 아무것도 쓰지 않았으므로 게이트웨이가 직접 만든 본문이다.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = GatewayErrorDoc.class)))
    @PostMapping("/v1/chat/completions")
    public Mono<Void> proxy(ServerWebExchange exchange,
                            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                    required = true,
                                    description = "OpenAI 형식의 요청 본문. JSON 이 아니면 손대지 않고 그대로 흘려보낸다.",
                                    content = @Content(mediaType = "application/json",
                                            schema = @Schema(implementation = ChatCompletionRequestDoc.class)))
                            @RequestBody(required = false) byte[] body) {

        return engine.proxy(exchange, body, ApiFormat.OPENAI);
    }
}
