package dev.rojae.llmgateway.analytics;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitoringCostEstimatorAdversarialTest {
    private static final String MODEL = "gpt-6.1-sol";

    @Test void thresholdUsesTotalInputEvenWhenEveryInputTokenIsCached() {
        var standard = estimate(272000L, 1L, 272000L, 0L);
        var longContext = estimate(272001L, 1L, 272001L, 0L);
        assertThat(standard.amountUsd()).isEqualByComparingTo("0.027210");
        assertThat(standard.longContext()).isFalse();
        assertThat(longContext.amountUsd()).isEqualByComparingTo("0.054415");
        assertThat(longContext.longContext()).isTrue();
        assertThat(longContext.ordinaryInputTokens()).isZero();
    }

    @Test void longContextMultipliesCacheWriteForTheEntireRequest() {
        var result = estimate(272001L, 1L, 0L, 272001L);
        assertThat(result.amountUsd()).isEqualByComparingTo("1.360020");
        assertThat(result.cacheWriteUsdPerMillion()).isEqualByComparingTo("5");
    }

    @Test void combinedCachesCanExactlyConsumeAllInput() {
        var result = estimate(100L, 0L, 40L, 60L);
        assertThat(result.availability()).isEqualTo("priced");
        assertThat(result.ordinaryInputTokens()).isZero();
        assertThat(result.amountUsd()).isEqualByComparingTo("0.000154");
    }

    @Test void combinedCachesExceedingInputByOneAreUnavailable() {
        assertUnavailable(estimate(100L, 0L, 40L, 61L), "invalid_usage");
    }

    @Test void overflowingCacheSumIsUnavailableWithoutWrappingToANegativeCount() {
        assertUnavailable(estimate(Long.MAX_VALUE, 0L, Long.MAX_VALUE, Long.MAX_VALUE), "invalid_usage");
    }

    @Test void longMaxInputOutputAndCacheArePricedUsingExactArithmetic() {
        var result = estimate(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1, 1L);
        assertThat(result.amountUsd()).isEqualByComparingTo(
            oracle("codex", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1, 1));
        assertThat(result.ordinaryInputTokens()).isZero();
        assertThat(result.amountUsd().scale()).isEqualTo(6);
    }

    @Test void zeroUsageIsAPricedZeroRatherThanMissing() {
        var result = estimate(0L, 0L, 0L, 0L);
        assertThat(result.availability()).isEqualTo("priced");
        assertThat(result.amountUsd()).isEqualTo(new BigDecimal("0.000000"));
        assertThat(result.assumptions()).noneMatch(a -> a.contains("Missing cache"));
    }

    @Test void missingInputOrOutputRemainsUnavailableEvenWhenOtherCountersAreZero() {
        assertUnavailable(estimate(null, 0L, 0L, 0L), "missing_usage");
        assertUnavailable(estimate(0L, null, 0L, 0L), "missing_usage");
    }

    @Test void eitherMissingCacheCounterIsExplicitlyAssumedZero() {
        for (Long[] caches : List.of(new Long[]{null, 5L}, new Long[]{5L, null})) {
            var result = estimate(10L, 0L, caches[0], caches[1]);
            assertThat(result.amountUsd()).isEqualByComparingTo(oracle("codex", 10, 0,
                caches[0] == null ? 0 : caches[0], caches[1] == null ? 0 : caches[1]));
            assertThat(result.assumptions()).contains("Missing cache counters assumed zero");
        }
    }

    @Test void everyNegativeTokenCounterMakesTheEstimateUnavailable() {
        for (Long[] usage : List.of(new Long[]{-1L, 0L, 0L, 0L}, new Long[]{0L, -1L, 0L, 0L},
                new Long[]{0L, 0L, -1L, 0L}, new Long[]{0L, 0L, 0L, -1L})) {
            assertUnavailable(estimate(usage[0], usage[1], usage[2], usage[3]), "invalid_usage");
        }
    }

    @Test void claudeInputIsSeparateFromCachesAndCanBeSmallerThanTheirSum() {
        var result = MonitoringCostEstimator.estimate("claude_code", MODEL, 1L, 0L, 10L, 10L, null);
        assertThat(result.availability()).isEqualTo("priced");
        assertThat(result.ordinaryInputTokens()).isEqualTo(1L);
        assertThat(result.amountUsd()).isEqualByComparingTo("0.000028");
        assertThat(result.assumptions()).noneMatch(a -> a.contains("Codex input"));
    }

    @Test void ratesAreNeverBorrowedForAliasesSiblingModelsOrMissingModels() {
        for (String model : new String[]{null, "", "gpt-6.1", "gpt-6.1-sol-latest", "gpt-6.1-sol-2026-10-08",
                "gpt-6.1-sol-fast", "GPT-6.1-SOL", "gpt-6.1-sol ", "claude-sonnet-4-5"}) {
            var result = MonitoringCostEstimator.estimate("codex", model, 1L, 1L, 0L, 0L, null);
            assertUnavailable(result, "unpriced");
            assertThat(result.model()).isEqualTo(model);
        }
    }

    @Test void cacheReadRoundingUsesHalfUpAtHalfAMicroDollar() {
        assertThat(estimate(4L, 0L, 4L, 0L).amountUsd()).isEqualTo(new BigDecimal("0.000000"));
        assertThat(estimate(5L, 0L, 5L, 0L).amountUsd()).isEqualTo(new BigDecimal("0.000001"));
    }

    @Test void cacheWriteRoundingUsesHalfUpAtTwoAndAHalfMicroDollars() {
        assertThat(estimate(1L, 0L, 0L, 1L).amountUsd()).isEqualTo(new BigDecimal("0.000003"));
    }

    @Test void clientReportedAmountDoesNotRequireModelOrTokenCounters() {
        var result = MonitoringCostEstimator.estimate("claude_code", null, null, null, null, null,
            new BigDecimal("1.2345675"));
        assertThat(result.availability()).isEqualTo("priced");
        assertThat(result.amountUsd()).isEqualTo(new BigDecimal("1.234568"));
        assertThat(result.basis()).isEqualTo("client_reported");
        assertThat(result.priceVersion()).isNull();
        assertThat(result.sourceUrl()).isNull();
        assertThat(result.inputUsdPerMillion()).isNull();
    }

    @Test void clientReportedZeroIsPreserved() {
        var result = MonitoringCostEstimator.estimate("claude_code", "unknown", null, null, null, null, BigDecimal.ZERO);
        assertThat(result.amountUsd()).isEqualTo(new BigDecimal("0.000000"));
        assertThat(result.availability()).isEqualTo("priced");
        assertThat(result.basis()).isEqualTo("client_reported");
    }

    @Test void negativeClientReportedAmountNeverFallsBackToAnOfficialEstimate() {
        var result = MonitoringCostEstimator.estimate("codex", MODEL, 1L, 1L, 0L, 0L, new BigDecimal("-0.00000001"));
        assertUnavailable(result, "invalid_usage");
        assertThat(result.basis()).isEqualTo("client_reported");
    }

    @Test void jdbcShapedEventMapPreservesNullableCountersAndReportedAmounts() {
        var event = new HashMap<String, Object>();
        event.put("source", "codex");
        event.put("model", MODEL);
        event.put("inputTokens", 10L);
        event.put("outputTokens", 0L);
        assertThat(MonitoringCostEstimator.estimate(event).amountUsd()).isEqualByComparingTo("0.000020");
        event.put("apiEquivalentCostUsd", new BigDecimal("0.0000015"));
        assertThat(MonitoringCostEstimator.estimate(event).amountUsd()).isEqualByComparingTo("0.000002");
        assertThat(MonitoringCostEstimator.estimate(event).basis()).isEqualTo("client_reported");
    }

    @Test void deterministicSupportedSourceUsageMatchesIntegerRationalOracle() {
        var random = new Random(0x20261008L);
        long[] boundaries = {0, 1, 4, 5, 271999, 272000, 272001, 272002, 999999999999L, Long.MAX_VALUE};
        for (String source : List.of("codex", "claude_code")) {
            for (int sample = 0; sample < 300; sample++) {
                long input = sample < boundaries.length ? boundaries[sample] : random.nextLong(1000000000000L);
                long output = random.nextLong(1000000000000L);
                long read = input == 0 ? 0 : random.nextLong(input);
                long remaining = input - read;
                long write = remaining == 0 ? 0 : random.nextLong(remaining);
                var result = MonitoringCostEstimator.estimate(source, MODEL, input, output, read, write, null);
                assertThat(result.amountUsd()).as("seed=0x20261008 source=%s sample=%s input=%s output=%s read=%s write=%s",
                    source, sample, input, output, read, write).isEqualByComparingTo(oracle(source, input, output, read, write));
            }
        }
    }

    private static MonitoringCostEstimator.Estimate estimate(Long input, Long output, Long read, Long write) {
        return MonitoringCostEstimator.estimate("codex", MODEL, input, output, read, write, null);
    }

    private static void assertUnavailable(MonitoringCostEstimator.Estimate result, String reason) {
        assertThat(result.availability()).isEqualTo(reason);
        assertThat(result.amountUsd()).isNull();
        assertThat(result.ordinaryInputTokens()).isNull();
        assertThat(result.inputUsdPerMillion()).isNull();
        assertThat(result.cacheReadUsdPerMillion()).isNull();
        assertThat(result.cacheWriteUsdPerMillion()).isNull();
        assertThat(result.outputUsdPerMillion()).isNull();
    }

    // Integer weights are tenths of USD per million. Division by 10,000,000 is exact;
    // round once at the display boundary, independent of the estimator's intermediate scale.
    private static BigDecimal oracle(String source, long input, long output, long read, long write) {
        BigInteger ordinary = BigInteger.valueOf(input);
        if (source.equals("codex")) ordinary = ordinary.subtract(BigInteger.valueOf(read)).subtract(BigInteger.valueOf(write));
        BigInteger[] counts = {ordinary, BigInteger.valueOf(read), BigInteger.valueOf(write), BigInteger.valueOf(output)};
        int[][] weights = {{20, 1, 25, 100}, {40, 2, 50, 150}};
        int tariff = Long.compare(input, 272000) > 0 ? 1 : 0;
        BigInteger numerator = BigInteger.ZERO;
        for (int i = 0; i < counts.length; i++) numerator = numerator.add(counts[i].multiply(BigInteger.valueOf(weights[tariff][i])));
        return new BigDecimal(numerator, 7).setScale(6, RoundingMode.HALF_UP);
    }
}
