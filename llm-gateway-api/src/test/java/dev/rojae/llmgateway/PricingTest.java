package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.rojae.llmgateway.observe.Pricing;
import dev.rojae.llmgateway.observe.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricingTest {

    @Test
    @DisplayName("모르는 모델은 0이 아니라 null 이다 — 틀린 숫자보다 빈 칸이 낫다")
    void unknownModelYieldsNull() {
        assertThat(Pricing.estimate("llama-3-70b", new Usage(100, 100, 200))).isNull();
        assertThat(Pricing.estimate(null, new Usage(100, 100, 200))).isNull();
        assertThat(Pricing.estimate("gpt-4o", null)).isNull();
    }

    @Test
    @DisplayName("실측값 재현: claude-haiku-4-5, prompt 15 / completion 5 → $0.000040")
    void reproducesMeasuredClaudeCost() {
        // 실제 Anthropic 호환 엔드포인트로 측정한 값 (x-gw-cost-usd: 0.000040)
        // 15 * $1.00/1M + 5 * $5.00/1M = 0.000015 + 0.000025
        assertThat(Pricing.estimate("claude-haiku-4-5", new Usage(15, 5, 20)))
                .isEqualTo(0.000040);
    }

    @Test
    @DisplayName("응답에 붙어오는 날짜 접미사 버전도 같은 단가로 잡힌다")
    void datedModelNameMatchesBasePrice() {
        // 업스트림은 "claude-haiku-4-5-20251001" 처럼 돌려준다 (실측 확인)
        assertThat(Pricing.estimate("claude-haiku-4-5-20251001", new Usage(15, 5, 20)))
                .isEqualTo(Pricing.estimate("claude-haiku-4-5", new Usage(15, 5, 20)));
    }

    @Test
    @DisplayName("접두사가 겹칠 때 더 긴 쪽이 이긴다 — gpt-4o-mini 가 gpt-4o 단가로 새면 4배 틀린다")
    void longestPrefixWinsOverShorterOne() {
        Usage usage = new Usage(1_000_000, 0, 1_000_000);

        Double mini = Pricing.estimate("gpt-4o-mini-2024-07-18", usage);
        Double full = Pricing.estimate("gpt-4o-2024-11-20", usage);

        assertThat(mini).isEqualTo(0.15);   // gpt-4o(2.50) 로 새면 여기서 터진다
        assertThat(full).isEqualTo(2.50);
        assertThat(mini).isLessThan(full);
    }

    @Test
    @DisplayName("현행 Claude 모델이 표에 있다")
    void currentClaudeModelsArePriced() {
        Usage oneMillionIn = new Usage(1_000_000, 0, 1_000_000);

        assertThat(Pricing.estimate("claude-opus-5", oneMillionIn)).isEqualTo(5.00);
        assertThat(Pricing.estimate("claude-sonnet-5", oneMillionIn)).isEqualTo(2.00);
        assertThat(Pricing.estimate("claude-haiku-4-5", oneMillionIn)).isEqualTo(1.00);
    }

    @Test
    @DisplayName("출력 토큰이 입력보다 비싸게 계산된다")
    void outputCostsMoreThanInput() {
        Double inputOnly = Pricing.estimate("claude-opus-5", new Usage(1000, 0, 1000));
        Double outputOnly = Pricing.estimate("claude-opus-5", new Usage(0, 1000, 1000));

        assertThat(inputOnly).isEqualTo(0.005, within(1e-9));
        assertThat(outputOnly).isEqualTo(0.025, within(1e-9));
    }

    @Test
    @DisplayName("캐시 읽기는 기본 입력의 0.1배로 계산된다")
    void cacheReadIsTenPercentOfInput() {
        // claude-sonnet-5 input $2.00/1M → 캐시 읽기는 $0.20/1M
        Usage cacheRead = new Usage(0, 0, 1_000_000, 0, 1_000_000);
        assertThat(Pricing.estimate("claude-sonnet-5", cacheRead)).isEqualTo(0.20);

        Usage plainInput = new Usage(1_000_000, 0, 1_000_000);
        assertThat(Pricing.estimate("claude-sonnet-5", plainInput)).isEqualTo(2.00);
    }

    @Test
    @DisplayName("캐시 쓰기는 기본 입력의 1.25배로 계산된다")
    void cacheWriteIsOneAndAQuarterOfInput() {
        Usage cacheWrite = new Usage(0, 0, 1_000_000, 1_000_000, 0);
        assertThat(Pricing.estimate("claude-sonnet-5", cacheWrite)).isEqualTo(2.50);
    }

    @Test
    @DisplayName("★ 캐시를 무시하면 비용이 3배 이상 틀린다 — Claude Code 는 캐시를 상시 쓴다")
    void ignoringCacheMultipliersWouldBeWildlyWrong() {
        // input 10 / cache write 200 / cache read 1000 / output 5
        Usage usage = new Usage(10, 5, 1215, 200, 1000);
        Double actual = Pricing.estimate("claude-sonnet-5", usage);

        // 10*2.00 + 200*2.00*1.25 + 1000*2.00*0.10 + 5*10.00 = 770
        assertThat(actual).isEqualTo(0.000770);

        // 캐시분을 전부 1배 입력으로 취급했다면:
        double naive = ((10 + 200 + 1000) * 2.00 + 5 * 10.00) / 1_000_000d;
        assertThat(naive).isEqualTo(0.002470);
        assertThat(actual).isLessThan(naive / 3);
    }

    @Test
    @DisplayName("캐시를 쓰지 않는 업스트림은 기존과 동일하게 계산된다")
    void nonCachingUpstreamIsUnaffected() {
        // 3인자 생성자는 캐시 토큰을 0으로 둔다 — OpenAI 경로의 회귀 방지.
        assertThat(Pricing.estimate("gpt-4o-mini", new Usage(1_000_000, 0, 1_000_000)))
                .isEqualTo(0.15);
    }

    @Test
    @DisplayName("★ 1시간 TTL 캐시 쓰기는 2.0배 — 5분(1.25배)으로 계산하면 37.5% 덜 잡힌다")
    void oneHourCacheWriteCostsMoreThanFiveMinute() {
        Usage fiveMin = new Usage(0, 0, 1_000_000, 1_000_000, 0, 0);
        Usage oneHour = new Usage(0, 0, 1_000_000, 0, 1_000_000, 0);

        assertThat(Pricing.estimate("claude-sonnet-5", fiveMin)).isEqualTo(2.50);   // 2.00 * 1.25
        assertThat(Pricing.estimate("claude-sonnet-5", oneHour)).isEqualTo(4.00);   // 2.00 * 2.00
        assertThat(Pricing.estimate("claude-sonnet-5", fiveMin))
                .isEqualTo(Pricing.estimate("claude-sonnet-5", oneHour) * 0.625);
    }
}
