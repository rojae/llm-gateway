package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded, opt-in text observation. No headers, reasoning, or binary content are captured. */
public final class ContentCapture {
    private static final int HISTORY_LIMIT = 20;
    private static final int LOOKAHEAD = 512;
    private static final int SEGMENT_LIMIT = 64;
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)([\\\"']?(?:api[_-]?key|password|passwd|authorization|access[_-]?token|refresh[_-]?token|cookie|set[_-]?cookie|secret|client[_-]?secret|token)[\\\"']?\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*+(?:\\\"|$)|'(?:\\\\.|[^'\\\\])*+(?:'|$)|[^\\s,;}]+)");
    private static final Pattern API_TOKEN = Pattern.compile("(?i)sk-[a-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer[ \\t]+[^\\s\\\"',;}]+");
    private static final Pattern DATA_URI = Pattern.compile("(?i)data:[^\\s,]*;base64,[a-z0-9+/=_-]*");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]{128,}={0,2}");

    private final ApiFormat format;
    private final boolean trace;
    private final int maxChars;
    private final Capture request;
    private final Capture response;
    private boolean streamedContent;
    private final Map<String, String> responseAliases = new LinkedHashMap<>();
    private final Map<String, String> responseIndexAliases = new LinkedHashMap<>();
    private final Map<String, String> responseIndexOwners = new LinkedHashMap<>();
    private final Map<String, String> responseIdIndices = new LinkedHashMap<>();
    private final Set<String> rejectedResponseKeys = new HashSet<>();
    private final Map<String, Integer> chatTextRuns = new LinkedHashMap<>();
    private final Map<String, Boolean> chatWasTool = new LinkedHashMap<>();

    public ContentCapture(ApiFormat format, boolean trace, int maxChars) {
        if (format == null || maxChars < 1) throw new IllegalArgumentException("format and positive maxChars required");
        this.format = format;
        this.trace = trace;
        this.maxChars = Math.min(maxChars, 1_000_000);
        request = new Capture();
        response = new Capture();
    }

    public record Snapshot(String text, boolean truncated, boolean incomplete) {}

    public void request(JsonNode root) {
        if (!object(root, request)) return;
        if (trace) {
            if (root.hasNonNull("system")) content(request, root.get("system"), "system");
            if (root.hasNonNull("instructions")) content(request, root.get("instructions"), "instructions");
        }
        JsonNode messages = root.get(format == ApiFormat.OPENAI_RESPONSES ? "input" : "messages");
        if (messages == null || messages.isNull()) return;
        if (messages.isTextual()) {
            content(request, messages, "user");
        } else if (messages.isArray()) {
            int start = Math.max(0, messages.size() - (trace ? HISTORY_LIMIT : 1));
            if (trace && start > 0) request.truncated = true;
            for (int i = start; i < messages.size() && !request.full(); i++) message(request, messages.get(i));
        } else {
            request.incomplete = true;
        }
    }

    public void response(JsonNode root) {
        if (!object(root, response)) return;
        if (errorContent(root)) return;
        if (format == ApiFormat.ANTHROPIC) {
            content(response, root.get("content"), "assistant");
        } else if (format == ApiFormat.OPENAI_RESPONSES) {
            JsonNode output = root.get("output");
            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    if (response.full()) break;
                    message(response, item);
                }
            } else if (output != null && !output.isNull()) response.incomplete = true;
        } else {
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    if (response.full()) break;
                    message(response, choice.path("message"));
                }
            } else if (choices != null) response.incomplete = true;
        }
    }

    public void event(JsonNode node) {
        if (!object(node, response)) return;
        if (response.full()) { response.truncated = true; return; }
        String type = node.path("type").asText();
        boolean errorCaptured = errorContent(node);
        if (format == ApiFormat.ANTHROPIC) {
            String blockIndex = index(node, "index");
            if (blockIndex == null) return;
            String blockKey = "anthropic:" + blockIndex;
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                switch (delta.path("type").asText()) {
                    case "text_delta" -> delta(blockKey + ":text", "assistant", delta.get("text"));
                    case "input_json_delta" -> {
                        if (trace) delta(blockKey + ":tool", "tool call arguments", delta.get("partial_json"));
                    }
                    default -> { /* Thinking and signatures are intentionally not retained. */ }
                }
            } else if ("content_block_start".equals(type)) {
                JsonNode block = node.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    if (trace) {
                        streamTool(blockKey + ":tool", block.path("name"));
                        // Anthropic sends {} before incremental JSON. Do not prepend it to the arguments.
                        if (block.path("input").isObject() && !block.path("input").isEmpty()) {
                            response.lane(blockKey + ":tool", "tool call arguments");
                            jsonValue(response, block.get("input"), 0);
                        }
                    } else response.label("tool omitted: tool_use requires TRACE");
                } else if ("text".equals(block.path("type").asText())) {
                    delta(blockKey + ":text", "assistant", block.get("text"));
                } else omittedBlock(response, block);
            }
        } else if (format == ApiFormat.OPENAI_RESPONSES) {
            if ("response.output_text.delta".equals(type)) {
                String key = responseKey(node);
                String contentIndex = index(node, "content_index");
                if (key != null && contentIndex != null) delta(key + ":text:" + contentIndex, "assistant", node.get("delta"));
            } else if ("response.function_call_arguments.delta".equals(type) && trace) {
                String key = responseKey(node);
                if (key != null) delta(key + ":tool", "tool call arguments", node.get("delta"));
            } else if ("response.output_item.added".equals(type)) {
                JsonNode item = node.path("item");
                if ("function_call".equals(item.path("type").asText())) {
                    if (trace) {
                        String key = responseKey(node);
                        if (key != null) streamTool(key + ":tool", item.path("name"));
                    }
                    else response.label("tool omitted: function_call requires TRACE");
                } else if ("reasoning".equals(item.path("type").asText())) response.label("reasoning omitted");
            } else if ("response.completed".equals(type) || "response.failed".equals(type)
                    || "response.incomplete".equals(type)) {
                if (!streamedContent && !errorCaptured) response(node.path("response"));
                if (!"response.completed".equals(type)) response.incomplete = true;
            }
        } else {
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray()) {
                if (!uniqueIndices(choices)) return;
                for (JsonNode choice : choices) {
                    if (response.full()) break;
                    String choiceIndex = index(choice, "index", choices.size() == 1);
                    if (choiceIndex == null) continue;
                    String choiceKey = "chat:" + choiceIndex;
                    if (!chatTextRuns.containsKey(choiceKey) && chatTextRuns.size() >= SEGMENT_LIMIT) {
                        response.truncated = true; response.incomplete = true; break;
                    }
                    chatTextRuns.putIfAbsent(choiceKey, 0);
                    JsonNode change = choice.path("delta");
                    if (change.hasNonNull("content")) {
                        if (Boolean.TRUE.equals(chatWasTool.get(choiceKey))) {
                            chatTextRuns.compute(choiceKey, (key, run) -> run + 1);
                        }
                        chatWasTool.put(choiceKey, false);
                        delta(choiceKey + ":text:" + chatTextRuns.get(choiceKey), "assistant", change.get("content"));
                    }
                    if (change.path("tool_calls").isArray()) {
                        chatWasTool.put(choiceKey, true);
                        if (!uniqueIndices(change.path("tool_calls"))) continue;
                        if (trace) for (JsonNode call : change.path("tool_calls")) {
                            if (response.full()) break;
                            String callIndex = index(call, "index", change.path("tool_calls").size() == 1);
                            if (callIndex == null) continue;
                            String toolKey = choiceKey + ":tool:" + callIndex;
                            JsonNode function = call.path("function");
                            if (function.hasNonNull("name")) streamTool(toolKey, function.get("name"));
                            if (function.hasNonNull("arguments")) delta(toolKey, "tool call arguments", function.get("arguments"));
                        }
                    }
                }
            }
        }
    }

    private boolean uniqueIndices(JsonNode array) {
        if (array.size() > SEGMENT_LIMIT) { response.truncated = true; response.incomplete = true; return false; }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : array) {
            String value = index(item, "index", array.size() == 1);
            if (value == null || !seen.add(value)) { response.incomplete = true; return false; }
        }
        return true;
    }

    private String index(JsonNode node, String field) { return index(node, field, true); }

    private String index(JsonNode node, String field, boolean allowMissing) {
        JsonNode value = node.get(field);
        if (value == null && allowMissing) return "0";
        if (value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) return value.asText();
        response.incomplete = true;
        return null;
    }

    private String responseKey(JsonNode node) {
        JsonNode idNode = node.has("item_id") ? node.get("item_id") : node.path("item").get("id");
        if (idNode != null && (!idNode.isTextual() || idNode.textValue().isEmpty() || idNode.textValue().length() > 128)) {
            response.incomplete = true;
            return null;
        }
        String id = idNode == null ? null : idNode.textValue();
        String indexed = node.has("output_index") ? index(node, "output_index", false) : null;
        if (node.has("output_index") && indexed == null) return null;
        String idKey = id == null ? null : responseAliases.get(id);
        String indexKey = indexed == null ? null : responseIndexAliases.get(indexed);
        String owner = indexed == null ? null : responseIndexOwners.get(indexed);
        String previousIndex = id == null ? null : responseIdIndices.get(id);
        // Conflicting identities never share reconstruction state, even on malformed SSE.
        if (id != null && indexed != null && ((owner != null && !owner.equals(id))
                || (previousIndex != null && !previousIndex.equals(indexed))
                || (idKey != null && indexKey != null && !idKey.equals(indexKey)))) {
            if (idKey != null) rejectedResponseKeys.add(idKey);
            if (indexKey != null) rejectedResponseKeys.add(indexKey);
            // Remember both rejected aliases: a later suffix may contain only one identity.
            String rejected = idKey != null ? idKey : indexKey;
            if (idKey == null && responseAliases.size() < SEGMENT_LIMIT) responseAliases.put(id, rejected);
            else if (idKey == null) { response.segmentLimit = true; response.truncated = true; }
            if (indexKey == null && responseIndexAliases.size() < SEGMENT_LIMIT) responseIndexAliases.put(indexed, rejected);
            else if (indexKey == null) { response.segmentLimit = true; response.truncated = true; }
            response.incomplete = true;
            return null;
        }
        String key = idKey != null ? idKey : indexKey != null ? indexKey
                : id != null ? "response:id:" + id : indexed != null ? "response:index:" + indexed : "response:default";
        if (rejectedResponseKeys.contains(key)) { response.incomplete = true; return null; }
        if ((id != null && !responseAliases.containsKey(id) && responseAliases.size() >= SEGMENT_LIMIT)
                || (indexed != null && !responseIndexAliases.containsKey(indexed) && responseIndexAliases.size() >= SEGMENT_LIMIT)) {
            response.truncated = true; response.incomplete = true; return null;
        }
        if (id != null) responseAliases.put(id, key);
        if (indexed != null) {
            responseIndexAliases.put(indexed, key);
            if (id != null) {
                responseIndexOwners.put(indexed, id);
                responseIdIndices.put(id, indexed);
            }
        }
        return key;
    }

    private void streamTool(String key, JsonNode name) {
        if (key == null) return;
        streamedContent = true;
        // Tool names are sanitized independently of argument JSON as well.
        if (name != null && name.isTextual() && !response.lanes.containsKey(key + ":name")) {
            response.lane(key + ":name", "tool call");
            response.add(name.textValue());
        }
        response.lane(key, "tool call arguments");
    }

    private void delta(String key, String label, JsonNode text) {
        if (key == null) return;
        if (text == null || !text.isTextual()) { response.incomplete = true; return; }
        if (text.textValue().isEmpty()) return;
        streamedContent = true;
        response.lane(key, label);
        response.add(text.textValue());
    }

    private boolean errorContent(JsonNode root) {
        JsonNode error = root.path("error");
        if (!error.isObject()) error = root.path("response").path("error");
        if (!error.isObject()) return false;
        JsonNode message = error.get("message");
        if (message != null && message.isTextual()) {
            content(response, message, "upstream error");
        }
        return true;
    }

    public void skippedEvent() { response.incomplete = true; }
    public Snapshot requestSnapshot() { return snapshot(request); }
    public Snapshot responseSnapshot() { return snapshot(response); }

    private void message(Capture target, JsonNode message) {
        if (!object(message, target)) return;
        String type = message.path("type").asText();
        if ("reasoning".equals(type) || "thinking".equals(type) || "redacted_thinking".equals(type)) {
            target.label("reasoning omitted"); return;
        }
        if ("function_call".equals(type) || "function_call_output".equals(type)) {
            if (!trace) { target.label("tool omitted: " + type + " requires TRACE"); return; }
            if ("function_call".equals(type)) tool(target, message.path("name"), message.get("arguments"));
            else content(target, message.get("output"), "tool result");
            return;
        }
        String role = switch (message.path("role").asText("assistant")) {
            case "user" -> "user";
            case "system" -> "system";
            case "developer" -> "developer";
            case "tool", "function" -> "tool result";
            default -> "assistant";
        };
        if (!trace && ("tool result".equals(role) || "system".equals(role) || "developer".equals(role))) {
            target.label("block omitted: " + role + " requires TRACE"); return;
        }
        content(target, message.get("content"), role);
        if (message.path("tool_calls").isArray()) {
            if (!trace) target.label("tool omitted: tool_calls require TRACE");
            else for (JsonNode call : message.path("tool_calls")) {
                if (target.full()) break;
                tool(target, call.path("function").path("name"), call.path("function").get("arguments"));
            }
        }
    }

    private void content(Capture target, JsonNode node, String label) {
        if (target.full() || node == null || node.isNull()) return;
        if (node.isTextual()) { target.label(label); target.add(node.textValue()); }
        else if (node.isArray()) {
            for (JsonNode block : node) {
                if (target.full()) break;
                if (block.isTextual()) { target.label(label); target.add(block.textValue()); continue; }
                if (!block.isObject()) { target.incomplete = true; continue; }
                switch (block.path("type").asText()) {
                    case "text", "input_text", "output_text" -> {
                        target.label(label);
                        if (block.path("text").isTextual()) target.add(block.get("text").textValue());
                        else target.incomplete = true;
                    }
                    case "tool_use" -> {
                        if (trace) tool(target, block.path("name"), block.get("input"));
                        else target.label("tool omitted: tool_use requires TRACE");
                    }
                    case "tool_result" -> {
                        if (trace) content(target, block.get("content"), "tool result");
                        else target.label("tool omitted: tool_result requires TRACE");
                    }
                    default -> omittedBlock(target, block);
                }
            }
        } else target.incomplete = true;
    }

    private static void omittedBlock(Capture target, JsonNode block) {
        String type = block.path("type").asText();
        String reason = switch (type) {
            case "thinking", "redacted_thinking", "reasoning" -> "reasoning is not logged";
            case "image", "image_url", "input_image", "audio", "input_audio", "output_audio",
                    "document", "file", "input_file", "video" -> type + " media is not logged";
            default -> "unsupported content type="
                    + (type.matches("[a-z][a-z0-9_]{0,47}") ? type : "unknown");
        };
        target.label("block omitted: " + reason);
    }

    private void tool(Capture target, JsonNode name, JsonNode arguments) {
        target.label("tool call");
        if (name != null && name.isTextual()) target.add(name.textValue());
        target.add(" ");
        if (arguments != null) jsonValue(target, arguments, 0);
    }

    private void jsonValue(Capture target, JsonNode node, int depth) {
        if (target.full()) return;
        if (depth > 10) { target.add("[nested content omitted]"); target.truncated = true; return; }
        if (node.isTextual()) target.add(node.textValue());
        else if (node.isObject()) {
            target.add("{");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext() && !target.full()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (key.matches("apikey|password|passwd|authorization|accesstoken|refreshtoken|cookie|setcookie|secret|clientsecret|token")) {
                    target.add("[secret]=[REDACTED]");
                } else if (key.matches("image|audio|pdf|data|base64|thinking|reasoning|signature|encryptedcontent")) {
                    target.add("[block omitted]");
                } else {
                    target.add(field.getKey()); target.add(":"); jsonValue(target, field.getValue(), depth + 1);
                }
                if (fields.hasNext()) target.add(",");
            }
            target.add("}");
        } else if (node.isArray()) {
            target.add("[");
            for (JsonNode item : node) { if (target.full()) break; jsonValue(target, item, depth + 1); target.add(","); }
            target.add("]");
        } else if (node.isValueNode()) target.add(node.asText());
        else target.incomplete = true;
    }

    private static boolean object(JsonNode node, Capture capture) {
        if (node != null && node.isObject()) return true;
        capture.incomplete = true;
        return false;
    }

    private Snapshot snapshot(Capture capture) {
        StringBuilder safe = new StringBuilder(Math.min(maxChars, capture.retained));
        boolean truncated = capture.truncated || capture.retained > maxChars;
        for (StringBuilder segment : capture.segments) {
            // Reconstruct and redact each independent stream BEFORE joining display segments.
            String text = BEARER.matcher(segment).replaceAll("Bearer [REDACTED]");
            text = API_TOKEN.matcher(text).replaceAll("[REDACTED]");
            text = SECRET_FIELD.matcher(text).replaceAll("$1[REDACTED]");
            text = DATA_URI.matcher(text).replaceAll("[binary omitted]");
            text = BASE64.matcher(text).replaceAll("[binary omitted]");
            for (int i = 0; i < text.length() && safe.length() <= maxChars; i++) {
                char c = text.charAt(i);
                if (c == '\r') safe.append("\\r");
                else if ((Character.isISOControl(c) && c != '\n' && c != '\t')
                        || c == '\u2028' || c == '\u2029' || (c >= '\u202a' && c <= '\u202e')
                        || (c >= '\u2066' && c <= '\u2069')) safe.append(String.format("\\u%04x", (int) c));
                else safe.append(c);
            }
            if (safe.length() > maxChars) { truncated = true; break; }
        }
        if (safe.length() > maxChars) safe.setLength(maxChars);
        return new Snapshot(safe.toString(), truncated, capture.incomplete);
    }

    private final class Capture {
        private final List<StringBuilder> segments = new ArrayList<>();
        private final Map<String, StringBuilder> lanes = new LinkedHashMap<>();
        private StringBuilder active;
        private int retained;
        private boolean truncated;
        private boolean incomplete;
        private boolean segmentLimit;
        private boolean full() { return retained >= maxChars + LOOKAHEAD || segmentLimit; }
        private void label(String label) {
            if (segments.size() >= SEGMENT_LIMIT || full()) {
                truncated = true; segmentLimit = true; active = null; return;
            }
            active = new StringBuilder();
            segments.add(active);
            add("\n[" + label + "]\n");
        }
        private void lane(String key, String label) {
            StringBuilder existing = lanes.get(key);
            if (existing != null) { active = existing; return; }
            label(label);
            if (active != null) lanes.put(key, active);
        }
        private void add(String value) {
            if (active == null) {
                if (segmentLimit) { truncated = true; return; }
                label("content");
                if (active == null) return;
            }
            int remaining = maxChars + LOOKAHEAD - retained;
            if (value.length() > remaining) truncated = true;
            if (remaining > 0) {
                int length = Math.min(remaining, value.length());
                active.append(value, 0, length);
                retained += length;
            }
        }
    }
}
