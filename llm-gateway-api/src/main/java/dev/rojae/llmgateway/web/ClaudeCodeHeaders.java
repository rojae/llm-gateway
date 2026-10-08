package dev.rojae.llmgateway.web;

import dev.rojae.llmgateway.observe.RequestRecord;
import org.springframework.http.HttpHeaders;

/**
 * Claude Code 가 요청마다 붙여주는 귀속 헤더를 접근 로그로 옮긴다.
 *
 * <p>이 헤더들이 있는 이유가 정확히 "게이트웨이가 비용을 귀속시킬 수 있게"다.
 * 특히 {@code x-claude-code-session-id} 는 <b>본문을 파싱하지 않고</b> 한 세션의 모든 요청을
 * 묶으라고 공식 문서가 권하는 값이다. 프록시가 본문을 안 여는 설계와 딱 맞는다.
 *
 * <p>없으면 그냥 비워둔다 — 다른 클라이언트의 요청에는 당연히 없다.
 *
 * @see <a href="https://code.claude.com/docs/en/llm-gateway-protocol">Claude Code gateway compatibility guide</a>
 */
final class ClaudeCodeHeaders {

    private static final String SESSION_ID = "x-claude-code-session-id";
    private static final String REQUEST_CLASS = "x-claude-code-request-class";
    private static final String AGENT_ID = "x-claude-code-agent-id";
    private static final String AGENT_TYPE = "x-claude-code-agent-type";
    private static final String PARENT_AGENT_ID = "x-claude-code-parent-agent-id";
    private static final String TOOL_DURATIONS = "x-claude-code-prev-tool-durations";

    private ClaudeCodeHeaders() {}

    static void apply(HttpHeaders headers, RequestRecord record) {
        record.setSessionId(headers.getFirst(SESSION_ID));
        record.setRequestClass(headers.getFirst(REQUEST_CLASS));
        record.setAgentId(headers.getFirst(AGENT_ID));
        record.setAgentType(headers.getFirst(AGENT_TYPE));
        record.setParentAgentId(headers.getFirst(PARENT_AGENT_ID));
        record.setToolDurations(headers.getFirst(TOOL_DURATIONS));
    }
}
