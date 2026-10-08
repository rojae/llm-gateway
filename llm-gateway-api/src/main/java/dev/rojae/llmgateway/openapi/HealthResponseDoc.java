package dev.rojae.llmgateway.openapi;

import io.swagger.v3.oas.annotations.media.Schema;

/** 문서 전용 스키마. {@code /healthz} 응답. */
@Schema(name = "HealthResponse")
public record HealthResponseDoc(

        @Schema(example = "ok") String status,

        @Schema(description = "기존 /v1/** 경로의 업스트림.",
                example = "http://127.0.0.1:8080/stub/v1")
        String upstream,

        @Schema(description = """
                `managed` = 게이트웨이가 키를 소유하고 호출자 Authorization 을 덮어쓴다.
                `passthrough` = 호출자 키를 그대로 전달한다.""",
                example = "passthrough", allowableValues = {"managed", "passthrough"})
        String keyMode,

        @Schema(description = "공급자별 개인 API 키 경로의 basePath, upstream, keyMode.")
        java.util.Map<String, java.util.Map<String, String>> providers) {}
