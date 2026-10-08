package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccessLogTest {
    @Test void summarySeverityReflectsHttpFailuresWhileLedgerRemainsAtInfo() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("gateway.summary");
        var ledger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("gateway.access");
        var summaryEvents = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var ledgerEvents = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previous = logger.getLevel();
        summaryEvents.start(); ledgerEvents.start();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(summaryEvents); ledger.addAppender(ledgerEvents);
        try {
            for (int status : new int[]{200, 429, 502}) {
                RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
                record.setStatus(status); record.finish(null);
                new AccessLog("test", "test").emit(record);
            }
            assertThat(summaryEvents.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getLevel)
                    .containsExactly(ch.qos.logback.classic.Level.INFO, ch.qos.logback.classic.Level.WARN, ch.qos.logback.classic.Level.ERROR);
            assertThat(ledgerEvents.list).hasSize(3).allMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO);
        } finally {
            logger.detachAppender(summaryEvents); ledger.detachAppender(ledgerEvents);
            logger.setLevel(previous); summaryEvents.stop(); ledgerEvents.stop();
        }
    }
    @Test void recordsStartTimeAndReportedUsage() throws Exception {
        RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
        long start = record.getTs();
        record.setProvider("anthropic");
        record.setStatus(200);
        record.finish(new Usage(3, 5, 8));
        assertThat(record.getTs()).isEqualTo(start);
        assertThat(record.getTimestamp()).isEqualTo(java.time.Instant.ofEpochMilli(start).toString());
        assertThat(record.getTotalTokens()).isEqualTo(8);
        assertThat(record.getOutcome()).isEqualTo("success");
        assertThat(record.getUsageAvailability()).isEqualTo("reported");
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(record));
        assertThat(json.path("provider").asText()).isEqualTo("anthropic");
        assertThat(json.path("totalTokens").asInt()).isEqualTo(8);
    }

    @Test void missingUsageAndMetadataAreDistinct() {
        RequestRecord record = new RequestRecord("/v1/models", ApiFormat.OPENAI);
        record.setStatus(200);
        record.finish(null);
        assertThat(record.getUsageAvailability()).isEqualTo("missing");
        assertThat(record.getTotalTokens()).isNull();
        record.setGeneration(false);
        assertThat(record.getUsageAvailability()).isEqualTo("not_applicable");
    }

    @Test void failuresNeverMasqueradeAsSuccessEvenWhenUsageWasReported() {
        RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
        record.setStatus(200);
        record.finish(new Usage(3, 0, 3));
        record.setUpstreamError("overloaded_error");
        assertThat(record.getOutcome()).isEqualTo("upstream_error");
        assertThat(record.getUsageAvailability()).isEqualTo("partial");
        record.setClientDisconnected(true);
        assertThat(record.getOutcome()).isEqualTo("cancelled");
        record.setClientDisconnected(false);
        record.setStatus(401);
        assertThat(record.getOutcome()).isEqualTo("http_error");
    }

    @Test void readableSummaryHasTwoLinesAndNoInjectedControlCharactersOrMetadata() {
        RequestRecord record = new RequestRecord("/v1/messages\nFORGED", ApiFormat.ANTHROPIC);
        record.setModel("model\u001b[31m\rFORGED");
        record.setProvider("anthropic");
        record.setStatus(200);
        record.setSessionId("secret-in-arbitrary-metadata");
        record.setKeyId("key_0123456789abcdef0123456789abcdef");
        record.finish(new Usage(3, 5, 8));
        String summary = AccessLog.summary(record);
        assertThat(summary).contains("anthropic", "200", "tokens=8", "success", "request=")
                .contains("firstByte=", "cost=", record.getRequestId(), "key=key_0123456789ab")
                .doesNotContain("\r", "\u001b", "secret-in-arbitrary-metadata", "key_0123456789abcdef0123456789abcdef");
        assertThat(summary.lines().toList()).hasSize(2);
        assertThat(summary.lines().toList().get(1)).startsWith("    request=");
    }
    @Test void summaryShowsFixedPrecisionCostOrUnknown() {
        RequestRecord record = new RequestRecord("/v1/chat/completions", ApiFormat.OPENAI);
        assertThat(AccessLog.summary(record)).contains("cost=unknown");
        record.setModel("gpt-4o-mini");
        record.finish(new Usage(1000, 1000, 2000));
        assertThat(AccessLog.summary(record)).contains("cost=$0.000750");
    }
    @Test void rateLimitDiagnosticsAreActionableWithoutInventingUsage() throws Exception {
        RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
        record.setStatus(429);
        record.setUpstreamError("rate_limit_error");
        record.setUpstreamErrorReason("rate_limit_exceeded");
        record.setRetryAfterSeconds(12L);
        record.setUpstreamRequestId("req-provider-123");
        record.finish(null);
        assertThat(AccessLog.summary(record)).contains("error=rate_limit_error", "reason=rate_limit_exceeded",
                "retryAfter=12s", "upstreamRequest=req-provider-123", "usage=missing", "cost=unknown", "tokens=-");
        var json = new ObjectMapper().valueToTree(record);
        assertThat(json.path("upstreamErrorReason").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(json.path("retryAfterSeconds").asLong()).isEqualTo(12L);
        assertThat(record.getTotalTokens()).isNull();
        assertThat(record.getCostUsd()).isNull();
    }

    @Test void missingDiagnosticsStayUnknownAndNegativeRetryIsIgnored() {
        RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
        record.setStatus(429);
        record.setRetryAfterSeconds(-1L);
        record.finish(null);
        assertThat(AccessLog.summary(record)).contains("error=- reason=- retryAfter=- upstreamRequest=-");
        var json = new ObjectMapper().valueToTree(record);
        assertThat(json.has("upstreamErrorReason")).isTrue();
        assertThat(json.path("upstreamErrorReason").isNull()).isTrue();
        assertThat(json.path("retryAfterSeconds").isNull()).isTrue();
        record.setRetryAfterSeconds(0L);
        assertThat(AccessLog.summary(record)).contains("retryAfter=0s");
    }

    @Test void successfulSummariesDoNotAddErrorClutter() {
        RequestRecord record = new RequestRecord("/v1/messages", ApiFormat.ANTHROPIC);
        record.setStatus(200);
        assertThat(AccessLog.summary(record)).doesNotContain("error=", "retryAfter=", "upstreamRequest=");
    }

}
