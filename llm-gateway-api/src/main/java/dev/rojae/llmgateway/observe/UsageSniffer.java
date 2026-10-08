package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SSE 스트림에서 토큰 사용량을 "엿본다".
 *
 * <p>중요한 성질: 클라이언트에게 나가는 바이트를 절대 바꾸지 않는다.
 * 프록시는 원본 청크를 그대로 흘려보내고, 복사본만 이 클래스에 넘긴다.
 *
 * <p>프레임을 어떻게 읽을지는 {@link ApiFormat} 이 안다. 이 클래스가 하는 일은
 * <b>청크 경계에 걸쳐 잘린 이벤트를 다시 붙이는 것</b>뿐이다.
 *
 * <p><b>조기 종료를 하지 않는다.</b> Anthropic 은 입력 토큰({@code message_start})과
 * 출력 토큰({@code message_delta})을 나눠서 보내므로, 사용량을 한 번 찾았다고 멈추면
 * 출력 토큰을 영영 못 본다. 스트림 끝까지 본다.
 */
public final class UsageSniffer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // One bounded event buffer, never a copy of the entire upstream chunk.
    private byte[] buffer;
    private final int maxEventBytes;
    private final Consumer<JsonNode> onEvent;
    private final Runnable onSkippedEvent;
    private int buffered;
    private int suffix;
    private int scanned;
    private boolean discarding;
    private boolean finished;
    private final ApiFormat format;
    private Usage usage;
    private String model;
    private String upstreamError;
    private boolean terminalSeen;

    public UsageSniffer(ApiFormat format) {
        this(format, event -> {}, 1024 * 1024, () -> {});
    }

    public UsageSniffer(ApiFormat format, Consumer<JsonNode> onEvent,
                        int maxEventBytes, Runnable onSkippedEvent) {
        if (maxEventBytes <= 0 || maxEventBytes > Integer.MAX_VALUE - 4) {
            throw new IllegalArgumentException("maxEventBytes must be positive and allow delimiter slack");
        }
        this.format = Objects.requireNonNull(format);
        this.onEvent = Objects.requireNonNull(onEvent);
        this.onSkippedEvent = Objects.requireNonNull(onSkippedEvent);
        this.maxEventBytes = maxEventBytes;
        this.buffer = new byte[Math.min(4096, maxEventBytes + 4)];
    }

    public void feed(byte[] chunk) {
        if (finished) return;
        for (byte value : chunk) {
            suffix = (suffix << 8) | (value & 0xff);
            scanned = Math.min(4, scanned + 1);
            if (!discarding) {
                if (buffered == buffer.length && buffer.length < maxEventBytes + 4) {
                    int capacity = (int) Math.min((long) buffer.length * 2, (long) maxEventBytes + 4);
                    buffer = java.util.Arrays.copyOf(buffer, capacity);
                }
                if (buffered == buffer.length) {
                    discarding = true;
                    buffered = 0;
                    skipped();
                } else {
                    buffer[buffered++] = value;
                }
            }
            int delimiter = scanned >= 4 && suffix == 0x0d0a0d0a ? 4
                    : scanned >= 2 && (suffix & 0xffff) == 0x0a0a ? 2
                    : scanned >= 3 && (suffix & 0xffffff) == 0x0a0d0a ? 3 : 0;
            if (delimiter == 0) continue;
            if (!discarding) {
                int size = buffered - delimiter;
                if (size > maxEventBytes) skipped();
                else consumeEvent(new String(buffer, 0, size, StandardCharsets.UTF_8));
            }
            buffered = 0;
            suffix = 0;
            scanned = 0;
            discarding = false;
        }
    }

    /** Report any unfinished tail once on EOF/cancellation; subsequent calls are harmless. */
    public void finish() {
        if (finished) return;
        finished = true;
        if (!discarding && buffered > 0) skipped();
        buffered = 0;
    }

    private void skipped() {
        try {
            onSkippedEvent.run();
        } catch (Exception ignored) {
            // Optional observation must never break byte forwarding.
        }
    }

    private void consumeEvent(String event) {
        StringBuilder data = new StringBuilder();
        boolean found = false;
        for (String line : event.split("\\r?\\n")) {
            if (!line.equals("data") && !line.startsWith("data:")) continue;
            String value = line.equals("data") ? "" : line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            if (found) data.append('\n');
            data.append(value);
            found = true;
        }
        if (!found) return;
        String payload = data.toString();
        if (payload.isBlank() || "[DONE]".equals(payload.trim())) return;
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (Exception ignored) {
            skipped();
            return;
        }
        if (node == null || !node.isObject()) return;
        try {
            if (format == ApiFormat.OPENAI_RESPONSES) {
                String type = node.path("type").asText();
                if ("response.completed".equals(type) || "response.failed".equals(type)
                        || "response.incomplete".equals(type)) terminalSeen = true;
            }
            if (upstreamError == null) upstreamError = format.errorFrom(node);
            if (model == null) model = format.modelFrom(node);
            usage = format.mergeUsage(usage, node);
            // Update our accounting first, so an observer cannot corrupt it by mutating node.
            onEvent.accept(node);
        } catch (Exception ignored) {
            // Malformed JSON and observer failures must never affect the proxied stream.
        }
    }

    /** First provider error's allowlisted type, or null; never includes upstream message text. */
    public String upstreamError() {
        return upstreamError;
    }

    /** Call at stream termination: Responses requires an explicit terminal event. */
    public boolean incompleteResponseStream() {
        return format == ApiFormat.OPENAI_RESPONSES && !terminalSeen;
    }

    public Usage usage() {
        return usage;
    }

    public String model() {
        return model;
    }
}
