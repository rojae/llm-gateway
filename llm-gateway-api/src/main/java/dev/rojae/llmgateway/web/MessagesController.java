package dev.rojae.llmgateway.web;

import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.openapi.GatewayErrorDoc;
import dev.rojae.llmgateway.openapi.MessagesRequestDoc;
import dev.rojae.llmgateway.openapi.MessagesResponseDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Anthropic 네이티브 {@code /v1/messages} 경로.
 *
 * <p><b>이 경로가 있는 이유는 Claude Code 다.</b> Claude Code 는 OpenAI 호환 레이어가 아니라
 * 네이티브 Messages API 를 쓴다. 그래서 {@code ANTHROPIC_BASE_URL} 을 이 게이트웨이로 돌려도
 * 이 경로가 없으면 전부 404 였다. 이 경로가 열리는 순간 Claude Code 의 모든 토큰이
 * 접근 로그에 남는다 — 세션·서브에이전트·compaction 단위까지 ({@link ClaudeCodeHeaders}).
 *
 * <p>프록시 로직은 {@link ProxyEngine} 이 OpenAI 경로와 공유한다. 포맷 차이는
 * {@link ApiFormat#ANTHROPIC} 이 흡수한다.
 *
 * @see <a href="https://code.claude.com/docs/en/llm-gateway-protocol">Claude Code gateway compatibility guide</a>
 */
@RestController
@Tag(name = "Proxy (Anthropic)",
        description = "Anthropic 네이티브 경로. Claude Code 가 쓰는 포맷이다")
public class MessagesController {

    private final ProxyEngine engine;

    public MessagesController(ProxyEngine engine) {
        this.engine = engine;
    }

    @Operation(
            summary = "Messages 프록시 (Anthropic 네이티브)",
            description = """
                    요청 본문을 해석하지 않고 업스트림으로 그대로 전달한다. 게이트웨이가 손대는 것은 둘뿐이다.

                    1. managed 모드에서는 `x-api-key` 를 게이트웨이 키로 덮어쓴다
                       (그리고 호출자 키가 새지 않도록 `Authorization` 을 지운다).
                    2. `anthropic-version` 헤더가 **없을 때만** `2023-06-01` 을 채워 넣는다.
                       호출자가 보낸 값은 절대 덮어쓰지 않는다.

                    OpenAI 경로와 달리 `stream_options` 를 주입하지 않는다 — Anthropic 에는 그 필드가
                    없고, 스트리밍 사용량은 `message_delta` 프레임이 언제나 알려주기 때문이다.

                    **업스트림이 Anthropic 을 가리켜야 한다.** `GW_UPSTREAM_BASE_URL=https://api.anthropic.com/v1`.
                    현재 무엇을 보고 있는지는 `GET /healthz` 의 `upstream` 으로 확인한다.""")
    @ApiResponse(responseCode = "200",
            description = """
                    업스트림 응답. `stream: false` 면 JSON 한 덩어리, `stream: true` 면 SSE 스트림이다.

                    비용·지연시간 헤더는 **비스트리밍 응답에만** 붙는다. 스트리밍 요청의 비용은
                    접근 로그에서 `x-gw-request-id` 로 찾는다.""",
            headers = {
                    @Header(name = "x-gw-request-id",
                            description = "접근 로그의 같은 요청을 찾는 ID. 항상 붙는다.",
                            schema = @Schema(type = "string")),
                    @Header(name = "x-gw-model",
                            description = "응답 본문에서 읽은 모델 이름. 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "claude-sonnet-5")),
                    @Header(name = "x-gw-cost-usd",
                            description = "토큰 사용량으로 계산한 비용(USD). 캐시 토큰 단가까지 반영한다. 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "0.001184")),
                    @Header(name = "x-gw-upstream-ms",
                            description = "업스트림 왕복 시간(ms). 비스트리밍 전용.",
                            schema = @Schema(type = "string", example = "2315.9")),
                    @Header(name = "X-Accel-Buffering",
                            description = "스트리밍 전용. 중간 프록시가 SSE 를 모아두지 못하게 막는다(`no`).",
                            schema = @Schema(type = "string", example = "no"))
            },
            content = {
                    @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MessagesResponseDoc.class)),
                    @Content(mediaType = "text/event-stream",
                            schema = @Schema(type = "string",
                                    description = """
                                            `event:` / `data:` 프레임의 연속. `message_start` → `content_block_delta` 반복
                                            → `message_delta` → `message_stop`. OpenAI 와 달리 `[DONE]` 이 없다."""))
            })
    @ApiResponse(responseCode = "4XX",
            description = "업스트림이 내려준 에러를 본문 그대로 전달한다(401, 429 등). 형태는 업스트림을 따른다.",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "502",
            description = "업스트림에 닿지 못했다. 아직 아무것도 쓰지 않았으므로 게이트웨이가 직접 만든 본문이다.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = GatewayErrorDoc.class)))
    @PostMapping("/v1/messages")
    public Mono<Void> proxy(ServerWebExchange exchange,
                            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                    required = true,
                                    description = "Anthropic 형식의 요청 본문. JSON 이 아니면 손대지 않고 그대로 흘려보낸다.",
                                    content = @Content(mediaType = "application/json",
                                            schema = @Schema(implementation = MessagesRequestDoc.class)))
                            @RequestBody(required = false) byte[] body) {

        return engine.proxy(exchange, body, ApiFormat.ANTHROPIC);
    }

    @Operation(summary = "입력 토큰 수 계산", description = "호출자의 API 키로 Anthropic에 전달한다. 생성 사용량으로 과금 집계하지 않는다.")
    @PostMapping("/v1/messages/count_tokens")
    public Mono<Void> countTokens(ServerWebExchange exchange, @RequestBody(required = false) byte[] body) {
        return engine.metadata(exchange, body, ApiFormat.ANTHROPIC, HttpMethod.POST, "/messages/count_tokens");
    }

    @Operation(summary = "모델 목록 조회", description = "호출자의 API 키로 모델 목록을 조회한다. 페이지네이션 쿼리도 전달한다.")
    @GetMapping("/v1/models")
    public Mono<Void> models(ServerWebExchange exchange) {
        return engine.metadata(exchange, null, ApiFormat.ANTHROPIC, HttpMethod.GET, "/models");
    }
}
