package dev.rojae.llmgateway.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation only: the actual body is forwarded unchanged, including tools and input arrays. */
@Schema(name = "ResponsesRequest", description = "OpenAI Responses 요청. tools, input 배열 등 추가 필드도 그대로 전달한다.")
public record ResponsesRequestDoc(
        @Schema(example = "gpt-4o-mini", requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(description = "문자열 또는 메시지/도구 결과 배열", example = "안녕하세요") Object input,
        @Schema(example = "false") Boolean stream,
        @Schema(example = "128") Integer max_output_tokens) {}
