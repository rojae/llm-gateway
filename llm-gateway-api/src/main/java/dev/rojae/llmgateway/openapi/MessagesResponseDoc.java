package dev.rojae.llmgateway.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 문서 전용 스키마. 업스트림 응답을 그대로 돌려주며 게이트웨이는 본문을 수정하지 않는다. */
@Schema(name = "MessagesResponse",
        description = "Anthropic `/v1/messages` 응답. 게이트웨이는 본문을 수정하지 않는다.",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
public record MessagesResponseDoc(

        @Schema(example = "msg_01XFDUDYJgAACzvnptvVoYEL") String id,
        @Schema(example = "message") String type,
        @Schema(example = "assistant") String role,
        @Schema(example = "claude-sonnet-5") String model,
        @Schema(description = "콘텐츠 블록 배열. 텍스트 응답은 `type: text` 블록이다.")
        List<ContentBlockDoc> content,
        @Schema(example = "end_turn") @JsonProperty("stop_reason") String stopReason,

        @Schema(description = """
                토큰 사용량. 게이트웨이는 이 값으로 비용을 계산해 `x-gw-cost-usd` 에 담는다.
                **캐시 토큰은 단가가 다르다** — 쓰기는 기본 입력의 1.25 배, 읽기는 0.1 배다.""")
        UsageDoc usage) {

    @Schema(name = "ContentBlock")
    public record ContentBlockDoc(
            @Schema(example = "text") String type,
            @Schema(example = "pong") String text) {}

    @Schema(name = "AnthropicUsage")
    public record UsageDoc(
            @Schema(description = "캐시에 해당하지 않는 입력 토큰.", example = "11")
            @JsonProperty("input_tokens") Integer inputTokens,

            @Schema(description = "캐시에 새로 써 넣은 토큰. 기본 입력 단가의 1.25 배로 청구된다.", example = "0")
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,

            @Schema(description = "캐시에서 읽은 토큰. 기본 입력 단가의 0.1 배로 청구된다.", example = "0")
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,

            @Schema(description = "생성된 출력 토큰.", example = "3")
            @JsonProperty("output_tokens") Integer outputTokens) {}
}
