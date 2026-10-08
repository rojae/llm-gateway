package dev.rojae.llmgateway.observe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContentCaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode json(String value) throws Exception { return JSON.readTree(value); }

    @Test void debugCapturesLastMessageOnlyAndOmitsMediaThinkingAndTools() throws Exception {
        var capture = new ContentCapture(ApiFormat.ANTHROPIC, false, 2000);
        capture.request(json("""
                {"system":"private system","messages":[{"role":"user","content":"old"},
                {"role":"user","content":[{"type":"text","text":"hello"},
                {"type":"image","source":{"data":"BASE64PRIVATE"}},
                {"type":"thinking","thinking":"PRIVATE_REASONING"},
                {"type":"tool_result","content":"TOOLPRIVATE"}]}]}
                """));
        String text = capture.requestSnapshot().text();
        assertThat(text).contains("user", "hello", "image media is not logged",
                "reasoning is not logged", "tool_result requires TRACE");
        assertThat(text).doesNotContain("private system", "old", "BASE64PRIVATE", "PRIVATE_REASONING", "TOOLPRIVATE");
    }

    @Test void traceIncludesBoundedHistoryInstructionsAndRedactedTools() throws Exception {
        var capture = new ContentCapture(ApiFormat.OPENAI, true, 2000);
        capture.request(json("""
                {"messages":[{"role":"system","content":"instruction"},{"role":"user","content":"question"},
                {"role":"assistant","tool_calls":[{"type":"function","function":{"name":"lookup","arguments":"{\\"password\\":\\"PRIVATE\\",\\"city\\":\\"Seoul\\"}"}}]},
                {"role":"tool","content":"result"}]}
                """));
        assertThat(capture.requestSnapshot().text()).contains("instruction", "question", "lookup", "Seoul", "result", "[REDACTED]")
                .doesNotContain("PRIVATE");
    }

    @Test void responsesStringInputAndAggregateOutputAreCaptured() throws Exception {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 1000);
        capture.request(json("{\"input\":\"question\",\"instructions\":\"hidden\"}"));
        capture.response(json("""
                {"output":[{"type":"reasoning","summary":[{"text":"PRIVATE_REASONING"}]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}]}]}
                """));
        assertThat(capture.requestSnapshot().text()).contains("question").doesNotContain("hidden");
        assertThat(capture.responseSnapshot().text()).contains("answer").doesNotContain("PRIVATE_REASONING");
    }

    @Test void nativeStreamingFormatsCaptureTextAndNeverDuplicateTerminalOutput() throws Exception {
        var anthropic = new ContentCapture(ApiFormat.ANTHROPIC, false, 1000);
        anthropic.event(json("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}"));
        assertThat(anthropic.responseSnapshot().text()).contains("hello");
        var chat = new ContentCapture(ApiFormat.OPENAI, false, 1000);
        chat.event(json("{\"choices\":[{\"delta\":{\"content\":\"chat\"}}]}"));
        assertThat(chat.responseSnapshot().text()).contains("chat");
        var responses = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 1000);
        responses.event(json("{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}"));
        responses.event(json("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]}}"));
        assertThat(responses.responseSnapshot().text().split("answer", -1)).hasSize(2);
        var fallback = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 1000);
        fallback.event(json("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"fallback\"}]}]}}"));
        assertThat(fallback.responseSnapshot().text()).contains("fallback");
    }

    @Test void splitStreamSecretsAreRedactedAtSnapshotAndControlCharactersEscaped() throws Exception {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 1000);
        for (String text : new String[]{"sk-", "private123", " Bear", "er privateBearer", "\r\u001b[31m\nnormal"}) {
            var event = JSON.createObjectNode().put("type", "response.output_text.delta").put("delta", text);
            capture.event(event);
        }
        assertThat(capture.responseSnapshot().text()).contains("[REDACTED]", "\\r", "\\u001b", "\nnormal")
                .doesNotContain("private123", "privateBearer", "\r", "\u001b");
    }

    @Test void traceToolArgumentDeltasAreCapturedWithoutReasoningPayload() throws Exception {
        var capture = new ContentCapture(ApiFormat.ANTHROPIC, true, 1000);
        capture.event(json("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"name\":\"lookup\",\"input\":{}}}"));
        capture.event(json("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"api_key\\\":\\\"sk-secret\\\"}\"}}"));
        capture.event(json("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private thought\"}}"));
        assertThat(capture.responseSnapshot().text()).contains("lookup", "[REDACTED]").doesNotContain("sk-secret", "private thought");
    }

    @Test void hugePayloadIsBoundedMarkedTruncatedAndSkippedEventsIncomplete() {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 64);
        capture.request(JSON.createObjectNode().put("input", "a".repeat(1000000)));
        assertThat(capture.requestSnapshot().text().length()).isLessThanOrEqualTo(64);
        assertThat(capture.requestSnapshot().truncated()).isTrue();
        capture.skippedEvent();
        assertThat(capture.responseSnapshot().incomplete()).isTrue();
        capture.response(JSON.nullNode());
        assertThat(capture.responseSnapshot().text()).doesNotContain("null");
    }
    @Test void responsesHistoryAndFunctionCallsAreTraceOnlyAndBinaryIsNeverRendered() throws Exception {
        JsonNode request = json("""
                {"instructions":"guide","input":[{"role":"user","content":[{"type":"input_text","text":"question"},
                {"type":"input_file","file_data":"PRIVATE_PDF"}]},
                {"type":"function_call","name":"lookup","arguments":"{\\"city\\":\\"Seoul\\"}"},
                {"type":"function_call_output","output":"tool result"}]}
                """);
        var trace = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 2000);
        trace.request(request);
        assertThat(trace.requestSnapshot().text()).contains("guide", "question", "lookup", "Seoul", "tool result", "omitted")
                .doesNotContain("PRIVATE_PDF");
        var debug = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 2000);
        debug.request(request);
        assertThat(debug.requestSnapshot().text()).contains("omitted").doesNotContain("tool result", "Seoul", "guide");
    }

    @Test void veryLongStructuredSecretRedactsBeforeTruncationWithoutRegexStackOverflow() {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 20000);
        String content = "password=\"" + "private-value-".repeat(3000) + "\"";
        capture.request(JSON.createObjectNode().put("input", content));
        assertThat(capture.requestSnapshot().text()).contains("[REDACTED]").doesNotContain("private-value");
        assertThat(capture.requestSnapshot().truncated()).isTrue();
    }

    @Test void traceHistoryStopsAtTwentyMessagesAndStructuredSecretsAreRedacted() throws Exception {
        var root = JSON.createObjectNode();
        var messages = root.putArray("messages");
        for (int i = 0; i < 25; i++) messages.addObject().put("role", "user").put("content", "message-" + i + ";");
        var capture = new ContentCapture(ApiFormat.ANTHROPIC, true, 4000);
        capture.request(root);
        assertThat(capture.requestSnapshot().text()).contains("message-5;", "message-24;").doesNotContain("message-0;");
        assertThat(capture.requestSnapshot().truncated()).isTrue();
        capture.response(json("""
                {"content":[{"type":"tool_use","name":"lookup","input":{"access_token":"PRIVATE_ACCESS",
                "refresh_token":"PRIVATE_REFRESH","cookie":"PRIVATE_COOKIE","secret":"PRIVATE_SECRET", "data":"PRIVATE_DATA"}}]}
                """));
        assertThat(capture.responseSnapshot().text()).contains("[REDACTED]", "omitted")
                .doesNotContain("PRIVATE_ACCESS", "PRIVATE_REFRESH", "PRIVATE_COOKIE", "PRIVATE_SECRET", "PRIVATE_DATA");
    }

    @Test void authorizationFieldCannotExposeBearerValueAfterFieldRedaction() {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 1000);
        capture.request(JSON.createObjectNode().put("input", "Authorization: Bearer PRIVATE_BEARER"));
        assertThat(capture.requestSnapshot().text()).contains("[REDACTED]").doesNotContain("PRIVATE_BEARER");
    }

    @Test void providerErrorMessagesAreBoundedAndSanitizedForAggregateAndSse() throws Exception {
        JsonNode error = json("{\"type\":\"error\",\"error\":{\"message\":\"Rejected sk-private123 Authorization: Bearer PRIVATE_BEARER\"}}");
        for (boolean trace : new boolean[]{false, true}) {
            for (ApiFormat format : ApiFormat.values()) {
                var aggregate = new ContentCapture(format, trace, 200);
                aggregate.response(error);
                assertThat(aggregate.responseSnapshot().text()).contains("error", "Rejected", "[REDACTED]")
                        .doesNotContain("sk-private123", "PRIVATE_BEARER");
                var stream = new ContentCapture(format, trace, 200);
                stream.event(error);
                assertThat(stream.responseSnapshot().text()).contains("Rejected", "[REDACTED]")
                        .doesNotContain("sk-private123", "PRIVATE_BEARER");
            }
        }
        var responses = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 200);
        responses.event(JSON.createObjectNode().put("type", "response.failed").set("response", error));
        assertThat(responses.responseSnapshot().text()).contains("Rejected", "[REDACTED]");
        assertThat(responses.responseSnapshot().incomplete()).isTrue();
        var bounded = new ContentCapture(ApiFormat.OPENAI_RESPONSES, false, 32);
        var huge = JSON.createObjectNode();
        huge.putObject("error").put("message", "limit exceeded ".repeat(10000));
        bounded.response(huge);
        assertThat(bounded.responseSnapshot().text().length()).isLessThanOrEqualTo(32);
        assertThat(bounded.responseSnapshot().truncated()).isTrue();
    }

    @Test void interleavedChatToolArgumentsNeverLeakPasswordAcrossCalls() throws Exception {
        var capture = new ContentCapture(ApiFormat.OPENAI, true, 2000);
        for (String[] parts : new String[][]{{"{\"password\":\"", "{\"city\":\""}, {"PRIVATE_PASSWORD\"}", "Seoul\"}"}}) {
            var event = JSON.createObjectNode();
            var calls = event.putArray("choices").addObject().put("index", 0).putObject("delta").putArray("tool_calls");
            for (int index = 0; index < 2; index++) calls.addObject().put("index", index).putObject("function").put("arguments", parts[index]);
            capture.event(event);
        }
        assertThat(capture.responseSnapshot().text()).contains("[REDACTED]", "Seoul").doesNotContain("PRIVATE_PASSWORD");
    }

    @Test void interleavedProviderToolsAndTextRemainSeparate() {
        for (ApiFormat format : new ApiFormat[]{ApiFormat.ANTHROPIC, ApiFormat.OPENAI_RESPONSES}) {
            var capture = new ContentCapture(format, true, 4000);
            fragment(capture, format, 0, true, "{\"password\":\"");
            fragment(capture, format, 1, true, "{\"city\":\"");
            fragment(capture, format, 2, false, "Visible text");
            fragment(capture, format, 0, true, "PRIVATE_PASSWORD\"}");
            fragment(capture, format, 1, true, "Seoul\"}");
            assertThat(capture.responseSnapshot().text()).contains("[REDACTED]", "Seoul", "Visible text")
                    .doesNotContain("PRIVATE_PASSWORD");
        }
    }

    @Test void chatSequentialTextToolTextPreservesHumanReadableOrder() {
        var capture = new ContentCapture(ApiFormat.OPENAI, true, 4000);
        chatText(capture, "Before tool");
        var event = JSON.createObjectNode();
        event.putArray("choices").addObject().put("index", 0).putObject("delta").putArray("tool_calls")
                .addObject().put("index", 0).putObject("function").put("arguments", "{\"password\":\"PRIVATE_PASSWORD\"}");
        capture.event(event);
        chatText(capture, "After tool");
        String result = capture.responseSnapshot().text();
        assertThat(result).contains("Before tool", "[REDACTED]", "After tool").doesNotContain("PRIVATE_PASSWORD");
        assertThat(result.indexOf("Before tool")).isLessThan(result.indexOf("[REDACTED]"));
        assertThat(result.indexOf("[REDACTED]")).isLessThan(result.indexOf("After tool"));
    }

    @Test void conflictingResponseIdentitiesCannotMergeArgumentStreams() {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 4000);
        responseFragment(capture, "A", 0, "{\"password\":\"");
        responseFragment(capture, "B", 0, "{\"city\":\"");
        responseFragment(capture, "A", 0, "PRIVATE_PASSWORD\"}");
        responseFragment(capture, "B", 0, "Seoul\"}");
        capture.event(JSON.createObjectNode().put("type", "response.function_call_arguments.delta")
                .put("item_id", "B").put("delta", "PRIVATE_PASSWORD"));
        assertThat(capture.responseSnapshot().text()).doesNotContain("PRIVATE_PASSWORD");
        assertThat(capture.responseSnapshot().incomplete()).isTrue();
    }

    @Test void responseAliasesResolveBothIdAndIndexOnlyDeltas() {
        var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, 4000);
        responseFragment(capture, "A", 0, "{\"password\":\"");
        capture.event(JSON.createObjectNode().put("type", "response.function_call_arguments.delta")
                .put("item_id", "A").put("delta", "PRIVATE_"));
        capture.event(JSON.createObjectNode().put("type", "response.function_call_arguments.delta")
                .put("output_index", 0).put("delta", "PASSWORD\"}"));
        assertThat(capture.responseSnapshot().text()).contains("[REDACTED]").doesNotContain("PRIVATE_", "PASSWORD");
    }

    @Test void ambiguousAndInvalidChatIndicesAreSkipped() {
        for (int invalid = 0; invalid < 3; invalid++) {
            var capture = new ContentCapture(ApiFormat.OPENAI, true, 4000);
            for (String[] parts : new String[][]{{"{\"password\":\"", "{\"city\":\""}, {"PRIVATE_PASSWORD\"}", "Seoul\"}"}}) {
                var event = JSON.createObjectNode();
                var calls = event.putArray("choices").addObject().put("index", 0).putObject("delta").putArray("tool_calls");
                for (String part : parts) {
                    var call = calls.addObject();
                    if (invalid == 1) call.put("index", "invalid");
                    if (invalid == 2) call.put("index", 0);
                    call.putObject("function").put("arguments", part);
                }
                capture.event(event);
            }
            assertThat(capture.responseSnapshot().text()).doesNotContain("PRIVATE_PASSWORD");
            assertThat(capture.responseSnapshot().incomplete()).isTrue();
        }
    }

    @Test void manyStreamLanesShareOneRetentionBudgetAndFiniteLaneLimit() {
        for (int budget : new int[]{64, 100000}) {
            var capture = new ContentCapture(ApiFormat.OPENAI_RESPONSES, true, budget);
            for (int index = 0; index < 500; index++) fragment(capture, ApiFormat.OPENAI_RESPONSES, index, true, "value-" + index);
            assertThat(capture.responseSnapshot().text().length()).isLessThanOrEqualTo(budget);
            assertThat(capture.responseSnapshot().truncated()).isTrue();
            assertThat(capture.responseSnapshot().text()).doesNotContain("value-499");
        }
    }

    private void chatText(ContentCapture capture, String text) {
        var event = JSON.createObjectNode();
        event.putArray("choices").addObject().put("index", 0).putObject("delta").put("content", text);
        capture.event(event);
    }

    private void responseFragment(ContentCapture capture, String id, int index, String text) {
        capture.event(JSON.createObjectNode().put("type", "response.function_call_arguments.delta")
                .put("item_id", id).put("output_index", index).put("delta", text));
    }

    private void fragment(ContentCapture capture, ApiFormat format, int index, boolean tool, String text) {
        var event = JSON.createObjectNode();
        if (format == ApiFormat.ANTHROPIC) {
            event.put("type", "content_block_delta").put("index", index).putObject("delta")
                    .put("type", tool ? "input_json_delta" : "text_delta").put(tool ? "partial_json" : "text", text);
        } else {
            event.put("type", tool ? "response.function_call_arguments.delta" : "response.output_text.delta")
                    .put("output_index", index).put("item_id", "item-" + index).put("delta", text);
        }
        capture.event(event);
    }

}
