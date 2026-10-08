package dev.rojae.llmgateway.web;

import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.config.ProviderProperties;
import dev.rojae.llmgateway.openapi.HealthResponseDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Ops", description = "게이트웨이 자체 상태 확인")
public class HealthController {

    private final GatewayProperties props;
    private final ProviderProperties providers;

    public HealthController(GatewayProperties props, ProviderProperties providers) {
        this.props = props;
        this.providers = providers;
    }

    @Operation(
            summary = "헬스 체크",
            description = """
                    프로세스가 살아 있는지, 그리고 **어떤 업스트림을 어떤 키 모드로 바라보는지**를 돌려준다.
                    업스트림에 실제로 요청을 보내지는 않으므로 업스트림 장애를 감지하지는 못한다.""")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = HealthResponseDoc.class)))
    @SecurityRequirements  // 인증 불필요
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "upstream", props.normalizedBaseUrl(),
                "keyMode", props.hasManagedKey() ? "managed" : "passthrough",
                "providers", Map.of(
                        "openai", Map.of("basePath", "/openai/v1", "upstream", providers.openaiUrl(), "keyMode", "personal"),
                        "anthropic", Map.of("basePath", "/anthropic", "upstream", providers.anthropicUrl(), "keyMode", "personal")));
    }
}
