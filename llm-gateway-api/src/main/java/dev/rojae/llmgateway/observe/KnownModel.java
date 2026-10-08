package dev.rojae.llmgateway.observe;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 게이트웨이가 <b>단가를 아는</b> 모델 목록.
 *
 * <p>이 열거형은 {@link Pricing} 의 단가 표이자 Swagger 문서에 찍히는 표의 원본이다.
 * 두 곳에 같은 목록을 적어두면 반드시 한쪽이 먼저 썩으므로 원본은 하나만 둔다.
 *
 * <p><b>이것은 허용 목록이 아니다.</b> 게이트웨이는 본문을 바이트로 흘려보내는 프록시라
 * 여기 없는 모델도 그대로 업스트림으로 간다. 목록에 없으면 요청이 막히는 게 아니라
 * 비용이 {@code null} 로 남을 뿐이다.
 *
 * <p>단가는 벤더가 언제든 바꾸므로 <b>박제된 스냅샷</b>이다 (기준일: 2026-09).
 * 실제 청구서와 대조하는 용도로 쓰고, 정산 근거로 삼지 말 것.
 */
public enum KnownModel {

    // OpenAI
    GPT_4O_MINI("gpt-4o-mini", Vendor.OPENAI, 0.15, 0.60),
    GPT_4O("gpt-4o", Vendor.OPENAI, 2.50, 10.00),

    // Anthropic — 게이트웨이를 api.anthropic.com/v1 (OpenAI 호환 레이어)로 향하게 하면
    // 이 모델들이 그대로 흘러온다. 응답 model 은 "claude-haiku-4-5-20251001" 처럼
    // Pricing은 정확한 ID 또는 날짜 스냅샷만 매칭한다. 유사 모델 변형은 별도 단가가 필요하다.
    CLAUDE_FABLE_5_1("claude-fable-5-1", Vendor.ANTHROPIC, 10.00, 50.00),
    CLAUDE_OPUS_5("claude-opus-5", Vendor.ANTHROPIC, 5.00, 25.00),
    CLAUDE_OPUS_4_8("claude-opus-4-8", Vendor.ANTHROPIC, 5.00, 25.00),
    CLAUDE_SONNET_5("claude-sonnet-5", Vendor.ANTHROPIC, 2.00, 10.00),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6", Vendor.ANTHROPIC, 3.00, 15.00),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5", Vendor.ANTHROPIC, 1.00, 5.00);

    /** 어느 업스트림에 물렸을 때 쓸 수 있는 이름인지 구분한다. */
    public enum Vendor {
        OPENAI("OpenAI", "https://api.openai.com/v1"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1");

        private final String label;
        private final String baseUrl;

        Vendor(String label, String baseUrl) {
            this.label = label;
            this.baseUrl = baseUrl;
        }

        public String label() {
            return label;
        }

        /** 이 벤더 모델을 쓰려면 {@code gateway.upstream-base-url} 이 가리켜야 하는 곳. */
        public String baseUrl() {
            return baseUrl;
        }
    }

    private final String id;
    private final Vendor vendor;
    private final double inputPerMillion;
    private final double outputPerMillion;

    KnownModel(String id, Vendor vendor, double inputPerMillion, double outputPerMillion) {
        this.id = id;
        this.vendor = vendor;
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
    }

    /** 요청 본문의 {@code model} 에 넣는 실제 문자열. */
    public String id() {
        return id;
    }

    public Vendor vendor() {
        return vendor;
    }

    /** 입력 100만 토큰당 USD. */
    public double inputPerMillion() {
        return inputPerMillion;
    }

    /** 출력 100만 토큰당 USD. */
    public double outputPerMillion() {
        return outputPerMillion;
    }

    /**
     * Swagger 설명란에 그대로 박히는 마크다운 표.
     *
     * <p>문서를 손으로 유지보수하지 않으려고 런타임에 만든다 — 모델을 하나 추가하면
     * 단가 표와 API 문서가 같이 움직인다.
     */
    public static String asMarkdownTable() {
        String rows = Arrays.stream(values())
                .map(m -> "| `%s` | %s | $%.2f | $%.2f |"
                        .formatted(m.id, m.vendor.label(), m.inputPerMillion, m.outputPerMillion))
                .collect(Collectors.joining("\n"));

        // 표 뒤에 빈 줄이 없으면 바로 다음 문단이 표 안으로 빨려 들어간다.
        // 반환값 끝에 개행을 붙여 호출부가 이어 붙이기만 해도 표가 닫히게 한다.
        return """
                | 모델 | 벤더 | input | output |
                |---|---|---:|---:|
                """ + rows + "\n";
    }
}
