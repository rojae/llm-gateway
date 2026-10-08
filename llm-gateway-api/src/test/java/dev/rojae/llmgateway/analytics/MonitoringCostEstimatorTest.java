package dev.rojae.llmgateway.analytics;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitoringCostEstimatorTest {
    @Test void cacheIsSubtractedFromCodexInputAndNotDoubleCounted(){
        var estimate=MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",28985L,10L,12288L,0L,null);
        assertThat(estimate.amountUsd()).isEqualByComparingTo("0.034723");assertThat(estimate.ordinaryInputTokens()).isEqualTo(16697L);
        assertThat(estimate.basis()).isEqualTo("official_standard_api");assertThat(estimate.sourceUrl()).isEqualTo(MonitoringCostEstimator.SOURCE);
        assertThat(estimate.assumptions()).anyMatch(a->a.contains("include cache"));
    }
    @Test void missingCachesAreExplicitlyAssumedZero(){
        var estimate=MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",12497L,0L,null,null,null);
        assertThat(estimate.amountUsd()).isEqualByComparingTo("0.024994");assertThat(estimate.assumptions()).contains("Missing cache counters assumed zero");
    }
    @Test void thresholdChangesEntireRequestRates(){
        var standard=MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",272000L,1L,0L,0L,null);
        var large=MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",272001L,1L,0L,0L,null);
        assertThat(standard.longContext()).isFalse();assertThat(standard.amountUsd()).isEqualByComparingTo("0.544010");
        assertThat(large.longContext()).isTrue();assertThat(large.amountUsd()).isEqualByComparingTo("1.088019");assertThat(large.outputUsdPerMillion()).isEqualByComparingTo("15");
    }
    @Test void preservesUnknownMissingAndImpossibleUsage(){
        assertThat(MonitoringCostEstimator.estimate("codex","gpt-6.1",100L,2L,0L,0L,null).availability()).isEqualTo("unpriced");
        assertThat(MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",null,2L,0L,0L,null).availability()).isEqualTo("missing_usage");
        var invalid=MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",100L,2L,99L,2L,null);
        assertThat(invalid.availability()).isEqualTo("invalid_usage");assertThat(invalid.amountUsd()).isNull();
        assertThat(MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",-1L,2L,0L,0L,null).availability()).isEqualTo("invalid_usage");
    }
    @Test void cacheSumDoesNotOverflowAndClientReportedCostHasDistinctBasis(){
        assertThat(MonitoringCostEstimator.estimate("codex","gpt-6.1-sol",Long.MAX_VALUE,2L,Long.MAX_VALUE,1L,null).availability()).isEqualTo("invalid_usage");
        var reported=MonitoringCostEstimator.estimate("claude_code","unknown",null,null,null,null,new BigDecimal("0.001061"));
        assertThat(reported.amountUsd()).isEqualByComparingTo("0.001061");assertThat(reported.basis()).isEqualTo("client_reported");assertThat(reported.inputUsdPerMillion()).isNull();
    }
}
