package dev.rojae.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.observe.Pricing;
import dev.rojae.llmgateway.observe.Usage;
import dev.rojae.llmgateway.observe.UsageSniffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenAiObservationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESPONSE = """
            {"model":"gpt-4o-mini","status":"completed","usage":{
              "input_tokens":100,"input_tokens_details":{"cached_tokens":80},
              "output_tokens":20,"output_tokens_details":{"reasoning_tokens":12},"total_tokens":120}}
            """;

    @Test
    void responsesAggregateAndFragmentedStreamNormalizeCacheWithoutDoubleCounting() throws Exception {
        ApiFormat format = ApiFormat.OPENAI_RESPONSES;
        Usage expected = new Usage(20, 20, 120, 0, 0, 80);
        assertThat(format.mergeUsage(null, MAPPER.readTree(RESPONSE))).isEqualTo(expected);
        assertThat(format.upstreamPath()).isEqualTo("/responses");
        assertThat(format.injectsStreamUsage()).isFalse();
        UsageSniffer sniffer = new UsageSniffer(format);
        String stream = "event: response.completed\ndata: " + MAPPER.readTree("{\"type\":\"response.completed\",\"response\":" + RESPONSE + "}") + "\n\n";
        for (byte value : stream.getBytes(StandardCharsets.UTF_8)) {
            sniffer.feed(new byte[]{value});
        }
        assertThat(sniffer.model()).isEqualTo("gpt-4o-mini");
        assertThat(sniffer.usage()).isEqualTo(expected);
        assertThat(sniffer.usage().billedInputTokens()).isEqualTo(100);
        assertThat(sniffer.upstreamError()).isNull();
    }

    @Test
    void absentResponsesUsageStaysUnknownAndDoesNotEraseKnownUsage() throws Exception {
        ApiFormat format = ApiFormat.OPENAI_RESPONSES;
        var pending = MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"model\":\"gpt-4o\",\"usage\":null}}");
        assertThat(format.mergeUsage(null, pending)).isNull();
        Usage previous = format.mergeUsage(null, MAPPER.readTree(RESPONSE));
        assertThat(format.mergeUsage(previous, pending)).isSameAs(previous);
    }

    @Test
    void chatCacheIsSubsetOfPromptAndUsesOpenAiCachePrice() throws Exception {
        Usage usage = ApiFormat.OPENAI.mergeUsage(null, MAPPER.readTree("""
                {"usage":{"prompt_tokens":1000000,"completion_tokens":100000,"total_tokens":1100000,
                 "prompt_tokens_details":{"cached_tokens":800000}}}
                """));
        assertThat(usage).isEqualTo(new Usage(200000, 100000, 1100000, 0, 0, 800000));
        assertThat(Pricing.estimate("gpt-4o-mini", usage)).isEqualTo(0.15);
        assertThat(Pricing.estimate("gpt-4o", usage)).isEqualTo(2.5);
        assertThat(Pricing.estimate("gpt-unpriced-future", usage)).isNull();
    }

    @Test
    void failedResponseErrorIsSharedByStreamAndAggregateAndSanitized() throws Exception {
        String failed = "{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"secret\"}}";
        assertThat(ApiFormat.OPENAI_RESPONSES.errorFrom(MAPPER.readTree(failed))).isEqualTo("server_error");
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI_RESPONSES);
        sniffer.feed(("data: {\"type\":\"response.failed\",\"response\":" + failed + "}\n\n").getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.upstreamError()).isEqualTo("server_error");
        assertThat(sniffer.usage()).isNull();
        assertThat(ApiFormat.OPENAI_RESPONSES.errorFrom(MAPPER.readTree(failed.replace("server_error", "secret_token"))))
                .isEqualTo("upstream_error");
    }

    @Test
    void incompleteResponseUsesOnlyKnownReasonAndKeepsAvailableUsage() throws Exception {
        String incomplete = "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":4,\"output_tokens\":8,\"total_tokens\":12}}}";
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI_RESPONSES);
        sniffer.feed(("data: " + incomplete + "\n\n").getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.upstreamError()).isEqualTo("response_incomplete_max_output_tokens");
        assertThat(sniffer.usage()).isEqualTo(new Usage(4, 8, 12));
        assertThat(ApiFormat.OPENAI_RESPONSES.errorFrom(MAPPER.readTree(incomplete.replace("max_output_tokens", "secret"))))
                .isEqualTo("response_incomplete");
    }
    @Test
    void incompleteUsageDoesNotInventReportedZeros() throws Exception {
        for (String usage : new String[]{"{}", "{\"input_tokens\":2}",
                "{\"input_tokens\":\"2\",\"output_tokens\":0}",
                "{\"input_tokens\":-1,\"output_tokens\":0}"}) {
            var response = MAPPER.readTree("{\"usage\":" + usage + "}");
            assertThat(ApiFormat.OPENAI_RESPONSES.mergeUsage(null, response)).isNull();
            Usage previous = new Usage(2, 3, 5);
            assertThat(ApiFormat.OPENAI_RESPONSES.mergeUsage(previous, response)).isSameAs(previous);
        }
        assertThat(ApiFormat.OPENAI.mergeUsage(null, MAPPER.readTree("{\"usage\":{}}"))).isNull();
        assertThat(ApiFormat.OPENAI_RESPONSES.mergeUsage(null,
                MAPPER.readTree("{\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}}")))
                .isEqualTo(Usage.EMPTY);
    }

    @Test
    void responsesRequireExplicitTerminalEventRatherThanNormalEof() {
        UsageSniffer sniffer = new UsageSniffer(ApiFormat.OPENAI_RESPONSES);
        sniffer.feed("data: {\"type\":\"response.created\",\"response\":{}}\n\n".getBytes(StandardCharsets.UTF_8));
        assertThat(sniffer.incompleteResponseStream()).isTrue();
        for (String terminal : new String[]{"response.completed", "response.failed", "response.incomplete"}) {
            UsageSniffer complete = new UsageSniffer(ApiFormat.OPENAI_RESPONSES);
            complete.feed(("data: {\"type\":\"" + terminal + "\",\"response\":{}}\n\n").getBytes(StandardCharsets.UTF_8));
            assertThat(complete.incompleteResponseStream()).isFalse();
        }
        assertThat(new UsageSniffer(ApiFormat.ANTHROPIC).incompleteResponseStream()).isFalse();
        assertThat(new UsageSniffer(ApiFormat.OPENAI).incompleteResponseStream()).isFalse();
    }

    @Test
    void unpricedSiblingModelsCannotBorrowPrefixPrices() {
        Usage usage = new Usage(1000000, 0, 1000000);
        for (String model : new String[]{"gpt-4o-audio-preview", "gpt-4o-mini-realtime-preview",
                "gpt-4o-2024-08-06-extra", "claude-sonnet-5-new", "gpt-4o-miniature"}) {
            assertThat(Pricing.estimate(model, usage)).as(model).isNull();
        }
        assertThat(Pricing.estimate("gpt-4o-mini-2024-07-18", usage)).isEqualTo(0.15);
        assertThat(Pricing.estimate("claude-haiku-4-5-20251001", usage)).isEqualTo(1.0);
    }

}
