package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.UUID;

/**
 * 접근 로그 한 줄. v0의 산출물은 사실상 이 객체다.
 *
 * <p>측정할 수 없으면 개선할 수 없다. 프록시를 세우는 진짜 이유는
 * 트래픽을 가로채기 위해서가 아니라 이 레코드를 남기기 위해서다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"ts", "timestamp", "environment", "traffic", "requestId", "upstreamRequestId", "method", "provider", "generation", "outcome", "usageAvailability", "keyId", "path", "format", "model", "stream", "status",
        "ttftMs", "totalMs", "promptTokens", "completionTokens", "totalTokens",
        "cacheWriteTokens", "cacheWrite1hTokens", "cacheReadTokens", "costUsd",
        "sessionId", "requestClass", "agentId", "agentType", "parentAgentId", "toolDurations",
        "clientDisconnected", "upstreamError", "upstreamErrorReason", "retryAfterSeconds"})
public class RequestRecord {

    private final String requestId = UUID.randomUUID().toString().replace("-", "");
    private final long startedMillis = System.currentTimeMillis();
    private final long startedNanos = System.nanoTime();

    private String method;
    private String upstreamRequestId;
    private String provider;
    private String environment;
    private String traffic;
    private boolean generation = true;
    private Integer totalTokens;
    private String keyId;
    private String path;
    private String format;
    private String model;
    private boolean stream;
    private Integer status;
    private Double ttftMs;
    private Double totalMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cacheWriteTokens;
    private Integer cacheWrite1hTokens;
    private Integer cacheReadTokens;
    private Double costUsd;
    private boolean clientDisconnected;
    private String upstreamError;
    private String upstreamErrorReason;
    private Long retryAfterSeconds;

    // Claude Code 가 보내주는 귀속 정보. 다른 클라이언트의 요청에는 없으므로
    // 필드 단위로 null 을 빼서, 일반 트래픽의 로그 줄이 불필요하게 길어지지 않게 한다.
    // (클래스 기본값은 ALWAYS — 고정 컬럼이 집계에 편하다는 판단은 그대로 둔다)
    @JsonInclude(JsonInclude.Include.NON_NULL) private String sessionId;
    @JsonInclude(JsonInclude.Include.NON_NULL) private String requestClass;
    @JsonInclude(JsonInclude.Include.NON_NULL) private String agentId;
    @JsonInclude(JsonInclude.Include.NON_NULL) private String agentType;
    @JsonInclude(JsonInclude.Include.NON_NULL) private String parentAgentId;
    @JsonInclude(JsonInclude.Include.NON_NULL) private String toolDurations;

    public RequestRecord(String path, ApiFormat format) {
        this.path = path;
        this.format = format.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 첫 바이트가 나간 시점. 사용자가 체감하는 "반응 속도"의 전부다. */
    public void markFirstByte() {
        if (ttftMs == null) {
            ttftMs = elapsedMs();
        }
    }

    public void finish(Usage usage) {
        totalMs = elapsedMs();
        if (usage != null) {
            totalTokens = usage.totalTokens();
            promptTokens = usage.promptTokens();
            completionTokens = usage.completionTokens();
            // 캐시를 쓰지 않은 요청에 0 을 적어두면 "캐시를 안 썼다"는 정보가 되므로 그대로 둔다.
            cacheWriteTokens = usage.cacheWriteTokens();
            cacheWrite1hTokens = usage.cacheWrite1hTokens();
            cacheReadTokens = usage.cacheReadTokens();
            costUsd = Pricing.estimate(model, usage);
        }
    }

    private double elapsedMs() {
        return Math.round((System.nanoTime() - startedNanos) / 100_000d) / 10d;
    }

    public String getRequestId() { return requestId; }
    public String getKeyId() { return keyId; }
    public String getPath() { return path; }
    public String getFormat() { return format; }
    public String getModel() { return model; }
    public boolean isStream() { return stream; }
    public Integer getStatus() { return status; }
    public Double getTtftMs() { return ttftMs; }
    public Double getTotalMs() { return totalMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public Integer getCacheWrite1hTokens() { return cacheWrite1hTokens; }
    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public Double getCostUsd() { return costUsd; }
    public boolean isClientDisconnected() { return clientDisconnected; }
    public String getUpstreamErrorReason() { return upstreamErrorReason; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }
    public String getUpstreamError() { return upstreamError; }
    public long getTs() { return startedMillis; }
    public String getTimestamp() { return java.time.Instant.ofEpochMilli(startedMillis).toString(); }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUpstreamRequestId() { return upstreamRequestId; }
    public void setUpstreamRequestId(String upstreamRequestId) { this.upstreamRequestId = upstreamRequestId; }
    public String getProvider() { return provider; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getTraffic() { return traffic; }
    public boolean isGeneration() { return generation; }
    public Integer getTotalTokens() { return totalTokens; }
    public String getOutcome() {
        if (clientDisconnected) return "cancelled";
        if (status != null && status >= 400) return "http_error";
        if (upstreamError != null) return "upstream_error";
        return status != null && status >= 200 && status < 400 ? "success" : "http_error";
    }
    public String getUsageAvailability() {
        if (!generation) return "not_applicable";
        if (totalTokens == null) return "missing";
        return clientDisconnected || upstreamError != null ? "partial" : "reported";
    }
    public void setProvider(String provider) { this.provider = provider; }
    public void setTraffic(String traffic) { this.traffic = traffic; }
    public void setGeneration(boolean generation) { this.generation = generation; }

    public String getSessionId() { return sessionId; }
    public String getRequestClass() { return requestClass; }
    public String getAgentId() { return agentId; }
    public String getAgentType() { return agentType; }
    public String getParentAgentId() { return parentAgentId; }
    public String getToolDurations() { return toolDurations; }

    public void setKeyId(String keyId) { this.keyId = keyId; }
    public void setModel(String model) { this.model = model; }
    public void setStream(boolean stream) { this.stream = stream; }
    public void setStatus(Integer status) { this.status = status; }
    public void setClientDisconnected(boolean v) { this.clientDisconnected = v; }
    /** Accepts only the finite classification supplied by the proxy, never provider error text. */
    public void setUpstreamErrorReason(String reason) { this.upstreamErrorReason = reason; }
    public void setRetryAfterSeconds(Long seconds) {
        this.retryAfterSeconds = seconds != null && seconds >= 0 ? seconds : null;
    }
    public void setUpstreamError(String upstreamError) { this.upstreamError = upstreamError; }

    public void setSessionId(String v) { this.sessionId = v; }
    public void setRequestClass(String v) { this.requestClass = v; }
    public void setAgentId(String v) { this.agentId = v; }
    public void setAgentType(String v) { this.agentType = v; }
    public void setParentAgentId(String v) { this.parentAgentId = v; }
    public void setToolDurations(String v) { this.toolDurations = v; }
}
