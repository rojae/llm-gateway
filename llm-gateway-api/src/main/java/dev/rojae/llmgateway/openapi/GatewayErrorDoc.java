package dev.rojae.llmgateway.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 전용 스키마. <b>게이트웨이가 직접 만든</b> 에러 본문이다.
 *
 * <p>업스트림이 내려준 에러(401, 429 등)는 본문을 손대지 않고 그대로 전달하므로
 * 이 형태가 아니라 업스트림(OpenAI 등)의 에러 형태를 따른다.
 */
@Schema(name = "GatewayError", description = "업스트림에 닿지 못했을 때 게이트웨이가 생성하는 502 본문.")
public record GatewayErrorDoc(ErrorBody error) {

    @Schema(name = "GatewayErrorBody")
    public record ErrorBody(
            @Schema(example = "upstream request failed") String message,
            @Schema(example = "bad_gateway") String type,
            @Schema(description = "접근 로그의 같은 요청을 찾을 때 쓰는 ID.",
                    example = "01J0000000000000000000")
            @JsonProperty("request_id") String requestId) {}
}
