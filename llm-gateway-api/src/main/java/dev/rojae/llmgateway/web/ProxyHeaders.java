package dev.rojae.llmgateway.web;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/** 프록시가 건드려야 할 헤더 규칙을 한곳에 모아둔다. */
public final class ProxyHeaders {

    /**
     * "홉 단위" 헤더 — 이 연결 하나에만 의미가 있으므로 절대 전달하면 안 된다.
     * Content-Length / Transfer-Encoding 을 그대로 넘기면 본문 길이가 어긋나 응답이 깨진다.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");
    private static final Set<String> GATEWAY_REQUEST_HEADERS = Set.of(
            "cookie", "x-csrf-token", "forwarded", "x-real-ip");

    private ProxyHeaders() {}

    public static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 호출자 → 업스트림.
     *
     * <p>anthropic-version, OpenAI-Beta 같은 벤더 헤더는 의도적으로 보존한다.
     * 임의로 떨어뜨리면 특정 기능이 <b>조용히</b> 꺼지고, 그 버그는 찾기 매우 어렵다.
     */
    public static void copyRequest(HttpHeaders from, HttpHeaders to) {
        from.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!isHopByHop(name) && !GATEWAY_REQUEST_HEADERS.contains(lower)
                    && !lower.startsWith("x-forwarded-")) {
                to.put(name, values);
            }
        });
    }

    /** 업스트림 → 호출자. */
    public static void copyResponse(HttpHeaders from, HttpHeaders to) {
        from.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!isHopByHop(name) && !lower.equals("set-cookie") && !lower.startsWith("x-gw-")) {
                to.put(name, values);
            }
        });
    }
}
