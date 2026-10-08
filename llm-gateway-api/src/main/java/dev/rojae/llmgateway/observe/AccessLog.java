package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One structured ledger event plus a concise local-console summary per completed request. */
@Component
public class AccessLog {
    private static final Logger LOG = LoggerFactory.getLogger("gateway.access");
    private static final Logger SUMMARY = LoggerFactory.getLogger("gateway.summary");
    private static final Logger DIAGNOSTICS = LoggerFactory.getLogger(AccessLog.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final String traffic;
    private final String environment;

    public AccessLog(@Value("${gateway.traffic-label:live}") String traffic,
                     @Value("${gateway.environment:local}") String environment) {
        this.traffic = traffic;
        this.environment = environment;
    }

    public void emit(RequestRecord record) {
        record.setTraffic(traffic);
        record.setEnvironment(environment);
        try {
            LOG.info("{}", mapper.writeValueAsString(record));
            if ((record.getStatus() != null && record.getStatus() >= 500)
                    || "upstream_error".equals(record.getOutcome())) {
                if (SUMMARY.isErrorEnabled()) SUMMARY.error("{}", summary(record));
            } else if (record.getStatus() != null && record.getStatus() >= 400) {
                if (SUMMARY.isWarnEnabled()) SUMMARY.warn("{}", summary(record));
            } else if (SUMMARY.isInfoEnabled()) SUMMARY.info("{}", summary(record));
        } catch (Exception e) {
            // Serialization exceptions can contain field values; never print their messages.
            DIAGNOSTICS.warn("Failed to serialize access record request={}", record.getRequestId());
        }
    }

    static String summary(RequestRecord r) {
        String cost = r.getCostUsd() == null ? "unknown"
                : String.format(java.util.Locale.ROOT, "$%.6f", r.getCostUsd());
        String key = r.getKeyId() == null ? "-" : safe(r.getKeyId());
        if (key.length() > 16) key = key.substring(0, 16);
        String diagnostics = "success".equals(r.getOutcome()) ? ""
                : " | error=" + safe(r.getUpstreamError())
                + " reason=" + safe(r.getUpstreamErrorReason())
                + " retryAfter=" + (r.getRetryAfterSeconds() == null ? "-" : r.getRetryAfterSeconds() + "s")
                + " upstreamRequest=" + safe(r.getUpstreamRequestId());
        return String.format(java.util.Locale.ROOT,
                "[%s/%s] %s · %s | %s %s | %sms%n    request=%s key=%s | tokens=%s (in=%s out=%s cache-write=%s cache-read=%s) usage=%s | cost=%s firstByte=%sms",
                safe(r.getEnvironment()), safe(r.getTraffic()), safe(r.getProvider()), safe(r.getModel()),
                value(r.getStatus()), r.getOutcome(), value(r.getTotalMs()),
                r.getRequestId(), key, value(r.getTotalTokens()), value(r.getPromptTokens()),
                value(r.getCompletionTokens()), cacheWrite(r), value(r.getCacheReadTokens()),
                r.getUsageAvailability(), cost, value(r.getTtftMs())) + diagnostics;
    }

    private static String cacheWrite(RequestRecord r) {
        return r.getCacheWriteTokens() == null ? "-"
                : String.valueOf((long) r.getCacheWriteTokens() + r.getCacheWrite1hTokens());
    }

    private static String value(Object value) { return value == null ? "-" : value.toString(); }

    private static String safe(String value) {
        if (value == null) return "-";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
        return clean.length() > 100 ? clean.substring(0, 100) + "…" : clean;
    }
}
