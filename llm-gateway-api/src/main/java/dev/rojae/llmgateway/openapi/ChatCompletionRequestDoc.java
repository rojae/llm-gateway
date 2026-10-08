package dev.rojae.llmgateway.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 문서 전용 스키마. 런타임에서 쓰이지 않는다 — 프록시는 본문을 바이트로만 다룬다.
 *
 * <p>여기 없는 필드(예: {@code tools}, {@code response_format})도 업스트림으로 그대로 전달된다.
 */
@Schema(name = "ChatCompletionRequest",
        description = "OpenAI `/v1/chat/completions` 요청 본문. 명세에 없는 필드도 그대로 전달된다.",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
public record ChatCompletionRequestDoc(

        // 설명은 OpenApiConfig#knownModelTableCustomizer 가 런타임에 덮어쓴다.
        // 단가를 아는 모델 표를 KnownModel 에서 만들어 붙이기 위한 것이다
        // (애너테이션 값은 컴파일 타임 상수여야 해서 여기서는 표를 만들 수 없다).
        @Schema(description = "모델 이름. 접근 로그의 `model` 과 비용 계산에 쓰인다.",
                example = "gpt-4o-mini", requiredMode = Schema.RequiredMode.REQUIRED)
        String model,

        @Schema(description = "대화 메시지 목록.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<MessageDoc> messages,

        @Schema(description = """
                true 면 SSE 로 토큰을 흘려보낸다. 이때 비용·토큰 수는 응답 헤더에 담을 수 없고
                (헤더는 첫 토큰보다 먼저 나간다) 접근 로그에만 남는다.""",
                defaultValue = "false", example = "false")
        Boolean stream,

        @Schema(description = """
                스트리밍 옵션. `gateway.inject-stream-usage=true`(기본)이면 게이트웨이가
                `include_usage: true` 를 자동으로 주입한다 — 없으면 스트리밍 요청의 토큰 수를 알 수 없다.""")
        @JsonProperty("stream_options")
        StreamOptionsDoc streamOptions,

        @Schema(description = "샘플링 온도.", example = "0.7", minimum = "0", maximum = "2")
        Double temperature,

        @Schema(description = "생성할 최대 토큰 수.", example = "256")
        @JsonProperty("max_tokens")
        Integer maxTokens) {

    @Schema(name = "ChatMessage")
    public record MessageDoc(
            @Schema(description = "메시지 역할.", example = "user",
                    allowableValues = {"system", "user", "assistant", "tool"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String role,

            @Schema(description = "메시지 내용.", example = "ping",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String content) {}

    @Schema(name = "StreamOptions")
    public record StreamOptionsDoc(
            @Schema(description = "마지막 프레임에 usage 이벤트를 포함할지 여부.", example = "true")
            @JsonProperty("include_usage")
            Boolean includeUsage) {}
}
