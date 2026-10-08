package dev.rojae.llmgateway.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 문서 전용 스키마. 런타임에서 쓰이지 않는다 — 프록시는 본문을 바이트로만 다룬다.
 *
 * <p>Anthropic <b>네이티브</b> Messages API 형식이다. OpenAI 호환 경로와 다른 점:
 * {@code max_tokens} 가 필수이고, 시스템 프롬프트가 {@code messages} 밖의 {@code system} 필드다.
 */
@Schema(name = "MessagesRequest",
        description = """
                Anthropic `/v1/messages` 요청 본문. 명세에 없는 필드(`tools`, `thinking` 등)도 그대로 전달된다.

                OpenAI 경로와 헷갈리기 쉬운 두 가지:
                - `max_tokens` 는 **필수**다 (OpenAI 에서는 선택)
                - 시스템 프롬프트는 `messages` 안이 아니라 최상위 `system` 필드에 넣는다""",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
public record MessagesRequestDoc(

        // 설명은 OpenApiConfig#knownModelTableCustomizer 가 런타임에 덮어쓴다.
        @Schema(description = "모델 이름. 접근 로그의 `model` 과 비용 계산에 쓰인다.",
                example = "claude-sonnet-5", requiredMode = Schema.RequiredMode.REQUIRED)
        String model,

        @Schema(description = "생성할 최대 토큰 수. Anthropic 에서는 **필수**다.",
                example = "1024", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("max_tokens")
        Integer maxTokens,

        @Schema(description = "대화 메시지 목록. `system` 역할은 여기 넣지 않는다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<MessageDoc> messages,

        @Schema(description = "시스템 프롬프트. 문자열 또는 블록 배열.",
                example = "You are a helpful assistant.")
        Object system,

        @Schema(description = """
                true 면 SSE 로 토큰을 흘려보낸다. Anthropic 은 `stream_options` 같은 별도 설정 없이도
                `message_delta` 프레임에 출력 토큰 수를 실어 보내므로, 게이트웨이가 아무것도 주입하지 않는다.""",
                defaultValue = "false", example = "false")
        Boolean stream,

        @Schema(description = """
                샘플링 온도. **일부 최신 모델은 이 필드를 거부한다**
                (`temperature is deprecated for this model`). 그럴 땐 빼고 보낼 것.""",
                example = "1.0", minimum = "0", maximum = "1")
        Double temperature) {

    @Schema(name = "AnthropicMessage")
    public record MessageDoc(
            @Schema(description = "메시지 역할. Anthropic 은 `system` 을 여기서 받지 않는다.",
                    example = "user", allowableValues = {"user", "assistant"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String role,

            @Schema(description = "메시지 내용. 문자열 또는 콘텐츠 블록 배열.", example = "ping",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Object content) {}
}
