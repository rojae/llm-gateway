package dev.rojae.llmgateway.web;

import dev.rojae.llmgateway.config.ProviderProperties;
import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.openapi.ChatCompletionRequestDoc;
import dev.rojae.llmgateway.openapi.MessagesRequestDoc;
import dev.rojae.llmgateway.openapi.ResponsesRequestDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** The URL selects the provider; credentials and native request bodies stay provider-specific. */
@RestController
@Tag(name = "Personal API keys", description = "각 사용자의 실제 공급자 API 키로 호출한다. 서버 공용 키로 교체하지 않는다.")
public class ProviderController {
    private final ProxyEngine engine;
    private final ProviderProperties providers;

    public ProviderController(ProxyEngine engine, ProviderProperties providers) {
        this.engine = engine;
        this.providers = providers;
    }

    @Operation(summary = "OpenAI Chat Completions", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/openai/v1/chat/completions")
    public Mono<Void> chat(ServerWebExchange exchange,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatCompletionRequestDoc.class)))
            @RequestBody(required = false) byte[] body) {
        return engine.personal(exchange, body, ApiFormat.OPENAI, HttpMethod.POST, "/chat/completions", true, providers.openaiUrl());
    }

    @Operation(summary = "OpenAI Responses (Codex HTTP/SSE)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/openai/v1/responses")
    public Mono<Void> responses(ServerWebExchange exchange,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponsesRequestDoc.class)))
            @RequestBody(required = false) byte[] body) {
        return engine.personal(exchange, body, ApiFormat.OPENAI_RESPONSES, HttpMethod.POST, "/responses", true, providers.openaiUrl());
    }

    @Operation(summary = "Responses (기존 단일 업스트림 설정 사용)")
    @PostMapping("/v1/responses")
    public Mono<Void> legacyResponses(ServerWebExchange exchange, @RequestBody(required = false) byte[] body) {
        return engine.proxy(exchange, body, ApiFormat.OPENAI_RESPONSES);
    }

    @Operation(summary = "OpenAI 개인 키의 모델 목록", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/openai/v1/models")
    public Mono<Void> openaiModels(ServerWebExchange exchange) {
        return engine.personal(exchange, null, ApiFormat.OPENAI, HttpMethod.GET, "/models", false, providers.openaiUrl());
    }

    @Operation(summary = "Anthropic Messages (Claude Code)", security = @SecurityRequirement(name = "anthropicKey"))
    @PostMapping("/anthropic/v1/messages")
    public Mono<Void> messages(ServerWebExchange exchange,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = MessagesRequestDoc.class)))
            @RequestBody(required = false) byte[] body) {
        return engine.personal(exchange, body, ApiFormat.ANTHROPIC, HttpMethod.POST, "/messages", true, providers.anthropicUrl());
    }

    @Operation(summary = "Anthropic 입력 토큰 수 계산", security = @SecurityRequirement(name = "anthropicKey"))
    @PostMapping("/anthropic/v1/messages/count_tokens")
    public Mono<Void> countTokens(ServerWebExchange exchange, @RequestBody(required = false) byte[] body) {
        return engine.personal(exchange, body, ApiFormat.ANTHROPIC, HttpMethod.POST, "/messages/count_tokens", false, providers.anthropicUrl());
    }

    @Operation(summary = "Anthropic 개인 키의 모델 목록", security = @SecurityRequirement(name = "anthropicKey"))
    @GetMapping("/anthropic/v1/models")
    public Mono<Void> anthropicModels(ServerWebExchange exchange) {
        return engine.personal(exchange, null, ApiFormat.ANTHROPIC, HttpMethod.GET, "/models", false, providers.anthropicUrl());
    }
}
