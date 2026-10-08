package dev.rojae.llmgateway.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 문서 전용 스키마. 비스트리밍( {@code stream: false} ) 응답 본문. */
@Schema(name = "ChatCompletionResponse",
        description = "업스트림 응답을 그대로 돌려준다. 게이트웨이는 본문을 수정하지 않는다.",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
public record ChatCompletionResponseDoc(

        @Schema(example = "chatcmpl-demo") String id,
        @Schema(example = "chat.completion") String object,
        @Schema(description = "유닉스 타임스탬프(초).", example = "1717171717") Long created,
        @Schema(example = "gpt-4o-mini") String model,
        List<ChoiceDoc> choices,

        @Schema(description = "토큰 사용량. 게이트웨이는 이 값으로 비용을 계산해 `x-gw-cost-usd` 에 담는다.")
        UsageDoc usage) {

    @Schema(name = "Choice")
    public record ChoiceDoc(
            @Schema(example = "0") Integer index,
            ChatCompletionRequestDoc.MessageDoc message,
            @Schema(example = "stop") @JsonProperty("finish_reason") String finishReason) {}

    @Schema(name = "Usage")
    public record UsageDoc(
            @Schema(example = "11") @JsonProperty("prompt_tokens") Integer promptTokens,
            @Schema(example = "3") @JsonProperty("completion_tokens") Integer completionTokens,
            @Schema(example = "14") @JsonProperty("total_tokens") Integer totalTokens) {}
}
