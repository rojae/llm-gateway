package dev.rojae.llmgateway.observe;

/**
 * 토큰 사용량을 USD 비용으로 환산한다. 모르는 모델은 비용을 null로 남긴다.
 * 조용히 틀린 숫자를 적는 것보다 비워두는 편이 낫다 — 틀린 비용 리포트는 신뢰를 한 번에 잃는다.
 *
 * <p>단가 표 자체는 {@link KnownModel} 에 있다. 모델을 추가하려면 그쪽만 고치면 된다.
 */
public final class Pricing {

    /** 5분 TTL 캐시 쓰기 배수. 기본 입력의 1.25 배. */
    private static final double CACHE_WRITE_5M_MULTIPLIER = 1.25;

    /**
     * 1시간 TTL 캐시 쓰기 배수. 기본 입력의 2.0 배.
     *
     * <p>응답의 {@code usage.cache_creation.ephemeral_1h_input_tokens} 로 구분해서 받는다.
     * 이 구분이 없으면 Claude Code 처럼 1시간 캐시를 쓰는 클라이언트의 비용이
     * 조용히 과소집계된다 (같은 토큰을 1.25 배로 계산하므로 37.5% 덜 잡힌다).
     */
    private static final double CACHE_WRITE_1H_MULTIPLIER = 2.0;

    /** 캐시 읽기 배수. 기본 입력의 0.1 배 (90% 할인). */
    private static final double CACHE_READ_MULTIPLIER = 0.1;

    private Pricing() {}

    /** @return USD 비용, 단가를 모르면 null */
    public static Double estimate(String model, Usage usage) {
        if (model == null || usage == null) {
            return null;
        }
        KnownModel known = lookup(model);
        if (known == null) {
            return null;
        }
        double in = known.inputPerMillion();
        double cacheReadMultiplier = known.vendor() == KnownModel.Vendor.OPENAI
                ? 0.5 : CACHE_READ_MULTIPLIER;
        double micros = usage.promptTokens() * in
                + usage.cacheWriteTokens() * in * CACHE_WRITE_5M_MULTIPLIER
                + usage.cacheWrite1hTokens() * in * CACHE_WRITE_1H_MULTIPLIER
                + usage.cacheReadTokens() * in * cacheReadMultiplier
                + usage.completionTokens() * known.outputPerMillion();
        double cost = micros / 1_000_000d;
        return Math.round(cost * 1_000_000d) / 1_000_000d;
    }

    /** Exact catalog IDs or dated snapshots only; sibling model variants have different prices. */
    private static KnownModel lookup(String model) {
        for (KnownModel candidate : KnownModel.values()) {
            String id = candidate.id();
            if (model.equals(id)) {
                return candidate;
            }
            if (model.startsWith(id + "-")) {
                String suffix = model.substring(id.length() + 1);
                String datePattern = candidate.vendor() == KnownModel.Vendor.OPENAI
                        ? "[0-9]{4}-[0-9]{2}-[0-9]{2}" : "[0-9]{8}";
                if (suffix.matches(datePattern)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
