package dev.rojae.llmgateway.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import dev.rojae.llmgateway.observe.KnownModel;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI ( {@code /docs} ) 와 OpenAPI 문서 ( {@code /openapi.json} ) 설정.
 *
 * <p>이 게이트웨이는 본문을 해석하지 않고 바이트 그대로 흘려보내는 프록시다.
 * 따라서 스키마를 코드에서 자동으로 뽑아낼 수가 없다 — 컨트롤러가 보는 타입은 {@code byte[]} 뿐이다.
 * {@link dev.rojae.llmgateway.openapi} 패키지의 레코드들은 <b>문서 전용</b>이며,
 * 런타임 경로에서는 단 한 번도 인스턴스화되지 않는다.
 *
 * <p>문서에 적힌 필드는 "게이트웨이가 아는 필드"일 뿐이고, 명세에 없는 필드도 업스트림으로
 * 그대로 전달된다. 그래서 요청 스키마는 추가 속성을 허용하도록 열어두었다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("API key")
                .description("""
                        OpenAI에서 발급받은 본인의 API 키. /openai/v1/** 에서는 그대로 OpenAI에 전달된다.
                        기존 /v1/** 경로만 gateway.upstream-api-key 설정 시 서버 키로 교체될 수 있다.""");
        SecurityScheme anthropic = new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name("x-api-key")
                .description("Anthropic에서 발급받은 본인의 API 키. /anthropic/v1/** 에서는 그대로 Anthropic에 전달된다.");

        return new OpenAPI()
                .info(new Info()
                        .title("LLM Gateway")
                        .version("0.1.0")
                        .description("""
                                각 사용자의 API 키로 OpenAI와 Anthropic을 호출하고 토큰·비용·지연시간을 기록한다.
                                OpenAI: `/openai/v1/chat/completions`, `/openai/v1/responses`.
                                Anthropic / Claude Code: `/anthropic/v1/messages`.
                                기존 `/v1/**`는 단일 업스트림 설정을 유지한다.

                                **Swagger UI 에서 스트리밍 요청은 제대로 보이지 않는다.**
                                `"stream": true` 응답은 SSE 라 브라우저 UI 가 전부 모아서 한 번에 보여준다.
                                스트리밍의 실제 동작(첫 토큰 지연, 취소 전파)을 확인하려면 `curl -N` 을 쓸 것.""")
                        .license(new License().name("교보재 프로젝트 — 제품 아님")))
                // 상대 경로를 쓴다. 절대 URL 을 박아두면 Swagger UI 가 "자기가 서빙된 곳"이 아니라
                // 여기 적힌 곳으로 요청을 쏜다 — 127.0.0.1 로 열거나 포트를 바꿔 띄우는 순간
                // 교차 출처가 되어 CORS 로 막히고, "Failed to fetch" 만 남는다.
                // 상대 경로면 항상 동일 출처라 8081 로 띄운 두 번째 인스턴스에서도 그대로 동작한다.
                .servers(List.of(
                        new Server().url("/").description("이 문서를 서빙 중인 호스트")))
                .components(new Components().addSecuritySchemes("bearerAuth", bearer)
                        .addSecuritySchemes("anthropicKey", anthropic))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * {@code model} 필드 설명에 "단가를 아는 모델" 표를 런타임에 주입한다.
     *
     * <p>애너테이션 값은 컴파일 타임 상수여야 해서 {@code @Schema(description=...)} 안에
     * 표를 박아 넣을 수가 없다. 손으로 복사해 두면 {@link KnownModel} 에 모델을 추가할 때마다
     * 문서가 한 박자씩 늦게 썩는다. 원본 하나에서 만들어 붙이는 쪽을 택한다.
     *
     * <p>스키마 모양은 springdoc 이 만들어 주는 것이라 언제든 바뀔 수 있다.
     * 문서 장식 실패가 기동을 막아서는 안 되므로 각 단계를 조용히 건너뛴다.
     */
    @Bean
    public OpenApiCustomizer knownModelTableCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null || components.getSchemas() == null) {
                return;
            }
            String description = modelDescription();
            // 두 포맷의 요청 스키마가 같은 표를 공유한다. 한쪽만 채우면 나머지가 조용히 뒤처진다.
            for (String schemaName : List.of("ChatCompletionRequest", "MessagesRequest", "ResponsesRequest")) {
                Schema<?> request = components.getSchemas().get(schemaName);
                if (request == null || request.getProperties() == null) {
                    continue;
                }
                Map<String, Schema> properties = request.getProperties();
                Schema<?> model = properties.get("model");
                if (model != null) {
                    model.setDescription(description);
                }
            }
        };
    }

    /** {@link KnownModel} 에서 만들어 붙이는 {@code model} 필드 설명. */
    private static String modelDescription() {
        return """
                모델 이름. 접근 로그의 `model` 과 비용 계산에 쓰인다.

                **이 목록은 허용 목록이 아니다.** 게이트웨이는 본문을 해석하지 않고 흘려보내므로
                아래에 없는 모델(`gpt-4.1`, 날짜 접미사가 붙은 스냅샷 등)도 그대로 업스트림으로 간다.
                목록에 없으면 요청이 막히는 게 아니라 응답의 `x-gw-cost-usd` 가 비는 것뿐이다.
                실제로 호출 가능한 이름은 업스트림이 정한다.

                아래는 게이트웨이가 **단가를 알고 있어 비용을 계산해 주는** 모델이다
                (100만 토큰당 USD, 기준일 2026-09 스냅샷):

                """ + KnownModel.asMarkdownTable() + """

                OpenAI 모델은 `/openai/v1/**`, Anthropic 모델은 `/anthropic/v1/**` 경로로 호출한다.
                기존 `/v1/**` 경로의 목적지는 `gateway.upstream-base-url` 설정을 따른다.
                현재 연결 주소는 `GET /healthz` 에서 확인한다.""";
    }
}
