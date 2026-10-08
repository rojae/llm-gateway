package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.observe.Usage;
import dev.rojae.llmgateway.observe.UsageSniffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UsageSnifferTest {

    private static final String ANTHROPIC_START = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-5",\
            "usage":{"input_tokens":10,"cache_creation_input_tokens":200,\
            "cache_read_input_tokens":1000,"output_tokens":1}}}

            """;

    private static final String ANTHROPIC_DELTA = """
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},\
            "usage":{"output_tokens":42}}

            """;

    private static void feed(UsageSniffer sniffer, String text) {
        sniffer.feed(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("★ Anthropic: message_delta 가 message_start 의 입력 토큰을 지우지 않는다")
    void anthropicDeltaDoesNotEraseInputTokens() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);

        feed(sniffer, ANTHROPIC_START);
        assertThat(sniffer.usage().promptTokens()).isEqualTo(10);

        feed(sniffer, ANTHROPIC_DELTA);

        Usage usage = sniffer.usage();
        // 덮어쓰기 구현이라면 여기서 input 이 0 으로 지워진다 — 비용이 조용히 과소집계된다.
        assertThat(usage.promptTokens()).as("입력 토큰이 보존되어야 한다").isEqualTo(10);
        assertThat(usage.completionTokens()).as("출력 토큰은 갱신되어야 한다").isEqualTo(42);
    }

    @Test
    @DisplayName("Anthropic: 캐시 쓰기/읽기 토큰을 따로 잡는다")
    void anthropicCapturesCacheTokensSeparately() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START + ANTHROPIC_DELTA);

        Usage usage = sniffer.usage();
        assertThat(usage.cacheWriteTokens()).isEqualTo(200);
        assertThat(usage.cacheReadTokens()).isEqualTo(1000);
        // 총합은 Anthropic 이 주지 않으므로 직접 더한다: 10 + 200 + 1000 + 42
        assertThat(usage.totalTokens()).isEqualTo(1252);
        assertThat(usage.billedInputTokens()).isEqualTo(1210);
    }

    @Test
    @DisplayName("Anthropic: 모델 이름을 message.model 에서 읽는다")
    void anthropicReadsModelFromNestedMessage() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START);
        assertThat(sniffer.model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    @DisplayName("청크 경계에서 프레임이 잘려도 이어 붙여 읽는다")
    void reassemblesFrameSplitAcrossChunks() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        String whole = ANTHROPIC_START + ANTHROPIC_DELTA;
        int cut = whole.length() / 3;

        feed(sniffer, whole.substring(0, cut));
        feed(sniffer, whole.substring(cut));

        assertThat(sniffer.usage().promptTokens()).isEqualTo(10);
        assertThat(sniffer.usage().completionTokens()).isEqualTo(42);
    }

    @Test
    @DisplayName("OpenAI: 마지막 usage 프레임을 읽고 캐시 토큰은 0 이다")
    void openAiReadsFinalUsageFrame() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI);
        feed(sniffer, """
                data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"hi"}}]}

                data: {"id":"c1","model":"gpt-4o-mini","choices":[],\
                "usage":{"prompt_tokens":11,"completion_tokens":60,"total_tokens":71}}

                data: [DONE]

                """);

        Usage usage = sniffer.usage();
        assertThat(sniffer.model()).isEqualTo("gpt-4o-mini");
        assertThat(usage.promptTokens()).isEqualTo(11);
        assertThat(usage.completionTokens()).isEqualTo(60);
        assertThat(usage.totalTokens()).isEqualTo(71);
        assertThat(usage.cacheWriteTokens()).isZero();
        assertThat(usage.cacheReadTokens()).isZero();
    }

    @Test
    @DisplayName("사용량 프레임이 없으면 null 로 남는다 — 0 으로 꾸며내지 않는다")
    void noUsageFrameLeavesNull() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI);
        feed(sniffer, """
                data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"hi"}}]}

                data: [DONE]

                """);
        assertThat(sniffer.usage()).isNull();
        assertThat(sniffer.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("깨진 JSON 프레임이 섞여도 관측이 죽지 않는다")
    void survivesMalformedFrames() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, "data: {not json at all\n\n");
        feed(sniffer, ANTHROPIC_START);
        assertThat(sniffer.usage().promptTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ cache_creation 내역이 오면 TTL 별로 나눠 담는다")
    void splitsCacheCreationByTtl() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-sonnet-5",\
                "usage":{"input_tokens":10,"cache_creation_input_tokens":200,\
                "cache_creation":{"ephemeral_5m_input_tokens":50,"ephemeral_1h_input_tokens":150},\
                "cache_read_input_tokens":1000,"output_tokens":1}}}

                """);

        Usage usage = sniffer.usage();
        assertThat(usage.cacheWriteTokens()).as("5분 TTL").isEqualTo(50);
        assertThat(usage.cacheWrite1hTokens()).as("1시간 TTL").isEqualTo(150);
        assertThat(usage.cacheWriteTokens() + usage.cacheWrite1hTokens())
                .as("합계는 cache_creation_input_tokens 와 같아야 한다").isEqualTo(200);
    }

    @Test
    @DisplayName("cache_creation 내역이 없으면 전부 기본 TTL(5분)로 본다")
    void fallsBackToFiveMinuteWhenBreakdownAbsent() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START);

        assertThat(sniffer.usage().cacheWriteTokens()).isEqualTo(200);
        assertThat(sniffer.usage().cacheWrite1hTokens()).isZero();
    }
    @Test
    void explicitZerosReplaceCumulativeUsageAndAbsentFieldsSurvive() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START + ANTHROPIC_DELTA);
        feed(sniffer, "data: {\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}\n\n");
        assertThat(sniffer.usage()).isEqualTo(Usage.EMPTY);
    }

    @Test
    void cacheBreakdownUpdatesWithoutAggregateDoNotRetainOldTotal() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START);
        feed(sniffer, "data: {\"usage\":{\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":30},\"output_tokens\":8}}\n\n");
        feed(sniffer, "data: {\"usage\":{\"output_tokens\":9}}\n\n");
        assertThat(sniffer.usage()).isEqualTo(new Usage(10, 9, 1049, 0, 30, 1000));
        feed(sniffer, "data: {\"usage\":{\"cache_creation_input_tokens\":0}}\n\n");
        assertThat(sniffer.usage()).isEqualTo(new Usage(10, 9, 1019, 0, 0, 1000));
    }

    @Test
    void reducedCacheAggregateReplacesStaleFiveMinuteTotal() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START);
        feed(sniffer, "data: {\"usage\":{\"cache_creation_input_tokens\":20}}\n\n");
        assertThat(sniffer.usage().cacheWriteTokens()).isEqualTo(20);
        assertThat(sniffer.usage().totalTokens()).isEqualTo(1031);
    }

    @Test
    void fragmentedProviderErrorExposesOnlySafeTypeAndPreservesUsage() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, ANTHROPIC_START);
        assertThat(sniffer.upstreamError()).isNull();
        String error = "event: error\r\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"secret-token\"}}\r\n\r\n";
        for (byte value : error.getBytes(StandardCharsets.UTF_8)) {
            sniffer.feed(new byte[]{value});
        }
        assertThat(sniffer.upstreamError()).isEqualTo("overloaded_error");
        assertThat(sniffer.usage().promptTokens()).isEqualTo(10);
    }

    @Test
    void unknownProviderErrorTypeCannotLeakArbitraryUpstreamText() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC);
        feed(sniffer, "data: {\"type\":\"error\",\"error\":{\"type\":\"secret_token\",\"message\":\"secret\"}}\n\n");
        assertThat(sniffer.upstreamError()).isEqualTo("upstream_error");
    }

    @Test void callbackReassemblesUtf8AndMultilineDataWithFragmentedCrLf() {
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add, 512, () -> {});
        String event = ": comment\r\nevent: delta\r\ndata: {\"model\":\"한글\",\r\ndata: \"content\":\"안녕🌍\"}\r\n\r\ndata: [DONE]\n\n";
        for (byte b : event.getBytes(StandardCharsets.UTF_8)) sniffer.feed(new byte[]{b});
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("content").asText()).isEqualTo("안녕🌍");
        assertThat(sniffer.model()).isEqualTo("한글");
    }

    @Test void oversizedEventIsSkippedOnceAndNextEventRecoversInSameChunk() {
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add, 128, skipped::incrementAndGet);
        feed(sniffer, "data: " + "x".repeat(2_000_000) + "\r\n\r\n"
                + "data: {\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\n");
        assertThat(skipped).hasValue(1);
        assertThat(events).hasSize(1);
        assertThat(sniffer.usage().totalTokens()).isEqualTo(5);
    }

    @Test void eventLimitExcludesDelimiterAndHandlesExactBoundary() {
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        String event = "data: {\"model\":\"a\"}";
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add,
                event.getBytes(StandardCharsets.UTF_8).length, skipped::incrementAndGet);
        feed(sniffer, event + "\r\n\r\n" + event + " \n\n" + event + "\n\n");
        assertThat(events).hasSize(2);
        assertThat(skipped).hasValue(1);
    }

    @Test void throwingObservationCallbacksCannotBreakUsageOrFollowingEvents() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, node -> {
            throw new IllegalStateException("observer failed");
        }, 128, () -> { throw new IllegalStateException("skip observer failed"); });
        feed(sniffer, "data: " + "x".repeat(256) + "\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n\n");
        assertThat(sniffer.usage().totalTokens()).isEqualTo(5);
        feed(sniffer, "data: {\"model\":\"next\"}\n\n");
        assertThat(sniffer.model()).isEqualTo("next");
    }

    @Test void oversizedFragmentedEventWaitsForDelimiterBeforeResuming() {
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add, 64, skipped::incrementAndGet);
        feed(sniffer, "data: " + "x".repeat(100));
        feed(sniffer, "data: {\"model\":\"must-not-parse\"}\r");
        feed(sniffer, "\n\r");
        assertThat(events).isEmpty();
        assertThat(skipped).hasValue(1);
        feed(sniffer, "\ndata: {\"model\":\"recovered\"}\n\n");
        assertThat(events).hasSize(1);
        assertThat(sniffer.model()).isEqualTo("recovered");
        assertThat(skipped).hasValue(1);
    }

    @Test void throwingObserverRetainsResponsesTerminalAndErrorDetection() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI_RESPONSES,
                node -> { throw new IllegalStateException("failed observation"); }, 512, () -> {});
        feed(sniffer, "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"rate_limit_exceeded\"}}}\n\n");
        assertThat(sniffer.incompleteResponseStream()).isFalse();
        assertThat(sniffer.upstreamError()).isNotNull();
    }

    @Test void malformedJsonMarksCapturePartialButCommentsDoneAndEmptyDoNot() {
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add, 512, skipped::incrementAndGet);
        feed(sniffer, ": heartbeat\n\ndata: [DONE]\n\ndata:\n\ndata: {broken\n\n"
                + "data: {\"model\":\"valid\"}\n\n");
        assertThat(skipped).hasValue(1);
        assertThat(events).hasSize(1);
    }

    @Test void finishMarksUnterminatedTailOnceAndPreservesCompletedUsage() {
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.ANTHROPIC, node -> {}, 1024, skipped::incrementAndGet);
        feed(sniffer, ANTHROPIC_START + "data: {\"usage\":{");
        sniffer.finish();
        sniffer.finish();
        assertThat(skipped).hasValue(1);
        assertThat(sniffer.usage().promptTokens()).isEqualTo(10);
    }

    @Test void finishDoesNotDoubleReportOversizedTailOrMarkCompletedEventsSkipped() {
        var skipped = new java.util.concurrent.atomic.AtomicInteger();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, node -> {}, 32, skipped::incrementAndGet);
        feed(sniffer, "data: " + "x".repeat(100));
        sniffer.finish();
        sniffer.finish();
        assertThat(skipped).hasValue(1);
        UsageSniffer completed = new UsageSniffer(ApiFormat.OPENAI, node -> {}, 32, skipped::incrementAndGet);
        feed(completed, "data: {}\n\n");
        completed.finish();
        assertThat(skipped).hasValue(1);
    }

    @Test void eventBufferGrowsAcrossFragmentsWithoutLosingUtf8Content() {
        var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI, events::add, 16384, () -> {});
        String content = "가".repeat(3000);
        byte[] event = ("data: {\"content\":\"" + content + "\"}\n\n").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < event.length; i += 257) {
            sniffer.feed(java.util.Arrays.copyOfRange(event, i, Math.min(i + 257, event.length)));
        }
        sniffer.finish();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("content").asText()).isEqualTo(content);
    }

}
