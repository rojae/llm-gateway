package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.config.ContentLogProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Opt-in, bounded conversation diagnostics, separate from the usage ledger. */
@Component
public final class ContentLog {
    private static final Logger LOG = LoggerFactory.getLogger("gateway.content");
    private static final Logger FILE = LoggerFactory.getLogger("gateway.content.file");
    private static final Logger DIAGNOSTICS = LoggerFactory.getLogger(ContentLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ContentLogProperties properties;

    public ContentLog(ContentLogProperties properties) { this.properties = properties; }
    public int maxEventBytes() { return properties.maxEventBytes(); }

    public Exchange start(RequestRecord record, ApiFormat format, JsonNode request) {
        boolean enabled = record.isGeneration() && LOG.isDebugEnabled();
        boolean trace = enabled && LOG.isTraceEnabled();
        Exchange exchange = new Exchange(record, trace,
                enabled ? new ContentCapture(format, trace, properties.maxChars()) : null);
        if (enabled) {
            try {
                exchange.capture.request(request);
                emit(record, trace, "request", exchange.capture.requestSnapshot());
            } catch (Exception ignored) { failed(record); }
        }
        return exchange;
    }

    public final class Exchange {
        private final RequestRecord record;
        private final boolean trace;
        private final ContentCapture capture;
        private boolean finished;
        private Exchange(RequestRecord record, boolean trace, ContentCapture capture) {
            this.record = record; this.trace = trace; this.capture = capture;
        }
        public boolean enabled() { return capture != null; }
        public synchronized void event(JsonNode event) {
            if (capture != null && !finished) {
                try { capture.event(event); }
                catch (Exception ignored) { capture.skippedEvent(); }
            }
        }
        public synchronized void response(JsonNode response) {
            if (capture != null && !finished) {
                try { capture.response(response); }
                catch (Exception ignored) { capture.skippedEvent(); }
            }
        }
        public synchronized void skippedEvent() {
            if (capture != null && !finished) capture.skippedEvent();
        }
        public synchronized void finish() {
            if (finished) return;
            finished = true;
            if (capture != null) {
                try { emit(record, trace, "response", capture.responseSnapshot()); }
                catch (Exception ignored) { failed(record); }
            }
        }
    }

    private void emit(RequestRecord record, boolean trace, String phase, ContentCapture.Snapshot snapshot) throws Exception {
        // A runtime level reduction must also stop the independent file destination.
        if (trace ? !LOG.isTraceEnabled() : !LOG.isDebugEnabled()) return;
        // Do not retain header credentials, arbitrary metadata, or the raw request/response trees.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("event", "gateway.content");
        event.put("requestId", record.getRequestId());
        event.put("phase", phase);
        event.put("detail", trace ? "TRACE" : "DEBUG");
        event.put("provider", safeLabel(record.getProvider()));
        event.put("format", record.getFormat());
        event.put("model", safeLabel(record.getModel()));
        event.put("method", safeLabel(record.getMethod()));
        event.put("path", safeLabel(record.getPath()));
        event.put("stream", record.isStream());
        if ("response".equals(phase)) {
            event.put("status", record.getStatus());
            event.put("outcome", record.getOutcome());
        }
        boolean partial = snapshot.incomplete() || ("response".equals(phase) && !"success".equals(record.getOutcome()));
        event.put("truncated", snapshot.truncated());
        event.put("partial", partial);
        event.put("text", snapshot.text());
        String json = MAPPER.writeValueAsString(event);
        boolean response = "response".equals(phase);
        String output = properties.format() == ContentLogProperties.Format.JSON ? json
                : "===== " + (response ? "RESPONSE <<< " : "REQUEST >>> ")
                + event.get("provider") + " · " + event.get("model") + " ====="
                + "\n    request=" + record.getRequestId() + " phase=" + phase
                + "\n    method=" + event.get("method") + " path=" + event.get("path") + " stream=" + record.isStream()
                + (response ? "\n    status=" + record.getStatus() + " outcome=" + record.getOutcome() : "")
                + "\n    detail=" + event.get("detail") + " truncated=" + snapshot.truncated() + " partial=" + partial
                + "\n    | " + (snapshot.text().isBlank() ? "[no text captured]" : snapshot.text().strip().replace("\n", "\n    | "));
        // Detail is fixed at request admission. A level change cannot unexpectedly upgrade a capture.
        if (trace) { LOG.trace("{}", output); FILE.trace("{}", json); }
        else { LOG.debug("{}", output); FILE.debug("{}", json); }
    }

    private static String safeLabel(String value) {
        if (value == null) return "-";
        String safe = value.replaceAll("(?i)sk-[a-z0-9_-]*", "[REDACTED]")
                .replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
        return safe.length() > 160 ? safe.substring(0, 160) + "…" : safe;
    }
    private static void failed(RequestRecord record) {
        DIAGNOSTICS.warn("Content observation failed request={}", record.getRequestId());
    }
}
