package dev.rojae.llmgateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.observe.RequestRecord;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/** Extracts finite classifications, never provider messages or arbitrary header values. */
final class UpstreamDiagnostics {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UpstreamDiagnostics() {}

    static void observe(RequestRecord record, ApiFormat format, byte[] bytes,
                        HttpHeaders headers, int status) {
        record.setRetryAfterSeconds(retrySeconds(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        String type = null;
        String reason = null;
        try {
            JsonNode root = MAPPER.readTree(bytes);
            if (root != null && root.isObject()) {
                type = format.errorFrom(root);
                JsonNode error = root.path("response").isObject()
                        ? root.path("response").path("error") : root.path("error");
                reason = classify(error);
            }
        } catch (Exception ignored) {
            // Non-JSON errors are relayed unchanged, with no body-derived diagnostics.
        }
        record.setUpstreamError(type == null ? "upstream_error" : type);
        record.setUpstreamErrorReason(reason == null && status == 429 ? "rate_limit" : reason);
    }

    private static Long retrySeconds(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String classify(JsonNode error) {
        for (String code : new String[]{error.path("details").path("error_code").asText(),
                error.path("code").asText(), error.path("type").asText()}) {
            String reason = switch (code) {
                case "enforced_spend_limit_reached", "spend_limit_reached" -> "spend_limit";
                case "insufficient_quota" -> "insufficient_quota";
                case "request_rate_limit_exceeded", "requests_per_minute_limit_exceeded" -> "request_rate";
                case "input_token_rate_limit_exceeded", "input_tokens_per_minute_limit_exceeded" -> "input_token_rate";
                case "output_token_rate_limit_exceeded", "output_tokens_per_minute_limit_exceeded" -> "output_token_rate";
                case "acceleration_limit_exceeded", "acceleration_limit" -> "acceleration_limit";
                default -> null;
            };
            if (reason != null) {
                return reason;
            }
        }
        // Match only known limit phrases; the original message never leaves this method.
        String message = error.path("message").asText().toLowerCase(Locale.ROOT);
        if (message.contains("monthly spend limit") || message.contains("enforced spend limit")) {
            return "spend_limit";
        }
        if (message.contains("input tokens per minute")) return "input_token_rate";
        if (message.contains("output tokens per minute")) return "output_token_rate";
        if (message.contains("requests per minute")) return "request_rate";
        if (message.contains("acceleration limit")) return "acceleration_limit";
        if (message.contains("exceeded your current quota")) return "insufficient_quota";
        if ("rate_limit_error".equals(error.path("type").asText())
                || "rate_limit_exceeded".equals(error.path("code").asText())) {
            return "rate_limit";
        }
        return null;
    }
}
