package dev.rojae.llmgateway.observe;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 게이트웨이가 프록시하는 와이어 포맷들.
 *
 * <p>프록시는 본문을 해석하지 않지만 <b>사용량은 엿봐야</b> 한다. 그 사용량이 담기는 자리가
 * 포맷마다 달라서, 그 차이만 이 열거형에 모았다. 여기 없는 차이는 프록시가 몰라도 되는 차이다.
 *
 * <p>두 포맷의 결정적 차이는 스트리밍에서 <b>사용량이 몇 번에 나눠 오는가</b>다.
 * <ul>
 *   <li>OpenAI: 마지막 프레임 하나에 {@code usage} 가 통째로 온다 → 덮어쓰면 된다</li>
 *   <li>Anthropic: {@code message_start} 에 입력 토큰, {@code message_delta} 에 출력 토큰이
 *       <b>나눠서</b> 온다 → 존재하는 필드만 누적값으로 갱신한다</li>
 * </ul>
 *
 * <p>{@code observe} 패키지는 스프링과 {@code web} 을 모른다. 이 열거형도 순수 값이다.
 */
public enum ApiFormat {

    /** OpenAI 호환 {@code /v1/chat/completions}. */
    OPENAI("/chat/completions", true) {
        @Override
        public String modelFrom(JsonNode root) {
            return root.hasNonNull("model") ? root.get("model").asText() : null;
        }

        @Override
        public Usage mergeUsage(Usage current, JsonNode root) {
            JsonNode usage = root.get("usage");
            if (usage == null || !usage.isObject()) {
                return current;
            }
            return openAiUsage(current, usage, "prompt_tokens", "completion_tokens", "prompt_tokens_details");
        }
    },

    /** OpenAI Responses API used by Codex. */
    OPENAI_RESPONSES("/responses", false) {
        @Override
        public String modelFrom(JsonNode root) {
            JsonNode response = responseObject(root);
            return response.hasNonNull("model") ? response.get("model").asText() : null;
        }

        @Override
        public Usage mergeUsage(Usage current, JsonNode root) {
            JsonNode usage = responseObject(root).get("usage");
            return usage != null && usage.isObject()
                    ? openAiUsage(current, usage, "input_tokens", "output_tokens", "input_tokens_details")
                    : current;
        }
    },

    /** Anthropic 네이티브 {@code /v1/messages}. Claude Code 가 쓰는 포맷이다. */
    ANTHROPIC("/messages", false) {
        @Override
        public String modelFrom(JsonNode root) {
            // 비스트리밍 응답은 최상위에, 스트리밍의 message_start 는 message 아래에 담는다.
            if (root.hasNonNull("model")) {
                return root.get("model").asText();
            }
            JsonNode message = root.get("message");
            if (message != null && message.hasNonNull("model")) {
                return message.get("model").asText();
            }
            return null;
        }

        @Override
        public Usage mergeUsage(Usage current, JsonNode root) {
            // message_start 는 message.usage, message_delta 와 비스트리밍 응답은 최상위 usage.
            Usage merged = apply(current, root.get("usage"));
            JsonNode message = root.get("message");
            if (message != null && message.isObject()) {
                merged = apply(merged, message.get("usage"));
            }
            return merged;
        }

        /**
         * 들어온 필드만 갱신하고 나머지는 보존한다.
         *
         * <p>필드 부재와 명시적인 0 을 구분한다. 각 값은 증분이 아닌 누적값이다.
         */
        private Usage apply(Usage current, JsonNode usage) {
            if (usage == null || !usage.isObject()) {
                return current;
            }
            Usage base = (current == null) ? Usage.EMPTY : current;
            int input = keep(base.promptTokens(), usage, "input_tokens");
            int output = keep(base.completionTokens(), usage, "output_tokens");
            int cacheRead = keep(base.cacheReadTokens(), usage, "cache_read_input_tokens");

            // 캐시 쓰기는 TTL 에 따라 단가가 다르다 (5분 1.25배, 1시간 2.0배).
            // cache_creation 하위에 TTL 별 내역이 오면 그것을 쓰고, 없으면
            // cache_creation_input_tokens 전부를 5분으로 본다 (그게 기본 TTL 이다).
            int write1h = base.cacheWrite1hTokens();
            int write5m = base.cacheWriteTokens();
            JsonNode breakdown = usage.get("cache_creation");
            if (breakdown != null && breakdown.isObject()) {
                write1h = keep(write1h, breakdown, "ephemeral_1h_input_tokens");
                write5m = keep(write5m, breakdown, "ephemeral_5m_input_tokens");
            }
            if (hasCount(usage, "cache_creation_input_tokens")) {
                int writeTotal = usage.get("cache_creation_input_tokens").intValue();
                // 합계만 갱신되면 기존 1시간 내역을 가능한 범위에서 보존하고
                // 나머지를 기본 TTL 로 둔다. 감소와 명시적 0 도 반영한다.
                write1h = Math.min(write1h, writeTotal);
                write5m = writeTotal - write1h;
            }

            // Anthropic 은 total 을 주지 않으므로 직접 더한다.
            int total = input + write5m + write1h + cacheRead + output;
            return new Usage(input, output, total, write5m, write1h, cacheRead);
        }

        private int keep(int existing, JsonNode usage, String field) {
            return hasCount(usage, field) ? usage.get(field).intValue() : existing;
        }

        private boolean hasCount(JsonNode usage, String field) {
            JsonNode value = usage.get(field);
            return value != null && value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 0;
        }
    };

    private static JsonNode responseObject(JsonNode root) {
        return root.path("response").isObject() ? root.get("response") : root;
    }

    /** OpenAI cached input is already part of the input total. */
    private static Usage openAiUsage(Usage current, JsonNode usage, String inputField, String outputField,
                                    String detailsField) {
        if (!validCount(usage.get(inputField)) || !validCount(usage.get(outputField))) {
            return current;
        }
        int input = usage.get(inputField).intValue();
        int output = usage.get(outputField).intValue();
        int cached = Math.min(input, Math.max(0, usage.path(detailsField).path("cached_tokens").asInt(0)));
        int total = validCount(usage.get("total_tokens"))
                ? usage.get("total_tokens").intValue() : input + output;
        return new Usage(input - cached, output, total, 0, 0, cached);
    }

    private static boolean validCount(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0;
    }

    /** A finite safe error classification for JSON responses and SSE events; never message text. */
    public String errorFrom(JsonNode root) {
        JsonNode response = this == OPENAI_RESPONSES ? responseObject(root) : root;
        String event = root.path("type").asText();
        if (this == OPENAI_RESPONSES && ("response.incomplete".equals(event)
                || "incomplete".equals(response.path("status").asText()))) {
            return switch (response.path("incomplete_details").path("reason").asText()) {
                case "max_output_tokens", "max_tokens" -> "response_incomplete_max_output_tokens";
                case "content_filter" -> "response_incomplete_content_filter";
                case "steered" -> "response_incomplete_steered";
                default -> "response_incomplete";
            };
        }
        JsonNode error = response.path("error");
        if (!error.isObject() && !"error".equals(event) && !"response.failed".equals(event)
                && !(this == OPENAI_RESPONSES && "failed".equals(response.path("status").asText()))) {
            return null;
        }
        String type = error.path("type").asText();
        if (type.isEmpty()) {
            type = error.path("code").asText(root.path("code").asText());
        }
        return switch (type) {
            case "invalid_request_error", "authentication_error", "permission_error",
                    "not_found_error", "request_too_large", "rate_limit_error",
                    "api_error", "overloaded_error", "server_error", "rate_limit_exceeded",
                    "insufficient_quota", "invalid_api_key", "context_length_exceeded" -> type;
            default -> "upstream_error";
        };
    }

    private final String upstreamPath;
    private final boolean injectsStreamUsage;

    ApiFormat(String upstreamPath, boolean injectsStreamUsage) {
        this.upstreamPath = upstreamPath;
        this.injectsStreamUsage = injectsStreamUsage;
    }

    /** 업스트림 base URL 뒤에 붙는 경로. */
    public String upstreamPath() {
        return upstreamPath;
    }

    /**
     * {@code stream_options.include_usage} 를 주입해도 되는 포맷인지.
     *
     * <p>Anthropic 은 그 필드를 모르므로 주입하면 400 이 난다. 그리고 주입할 필요도 없다 —
     * 스트리밍 사용량을 언제나 {@code message_delta} 로 돌려주기 때문이다.
     */
    public boolean injectsStreamUsage() {
        return injectsStreamUsage;
    }

    /** 응답(또는 SSE 프레임)에서 모델 이름을 읽는다. 없으면 null. */
    public abstract String modelFrom(JsonNode root);

    /** 지금까지 모은 사용량에 이 프레임의 정보를 합친다. 새 정보가 없으면 current 를 그대로 돌려준다. */
    public abstract Usage mergeUsage(Usage current, JsonNode root);
}
