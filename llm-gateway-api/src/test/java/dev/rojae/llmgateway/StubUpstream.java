package dev.rojae.llmgateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 가짜 업스트림.
 *
 * <p>핵심은 {@link #clientGone} 이다. 클라이언트가 사라지면 소켓 쓰기가 IOException 을
 * 던지는데, 그 순간을 잡아서 래치를 푼다. 이게 "취소가 실제로 업스트림까지 갔는가"에 대한
 * 유일하게 믿을 수 있는 증거다. 게이트웨이 내부 상태를 들여다보는 건 증거가 못 된다.
 */
public class StubUpstream implements AutoCloseable {

    private final HttpServer server;
    private final int port;

    /** 스트리밍 중 클라이언트가 끊겨 소켓 쓰기가 실패한 횟수. */
    public final AtomicInteger disconnects = new AtomicInteger();
    /** 업스트림이 실제로 내보낸 청크 수. */
    public final AtomicInteger chunksSent = new AtomicInteger();
    /** 마지막으로 받은 요청 본문 (stream_options 주입 검증용). */
    public volatile String lastRequestBody = "";
    /** 마지막으로 받은 요청 헤더 (자격증명 교체·anthropic-version 검증용). */
    public volatile com.sun.net.httpserver.Headers lastRequestHeaders = null;

    private static final int MAX_CHUNKS = 40;
    private static final long CHUNK_DELAY_MS = 100;
    /** Anthropic 스트림은 끝까지 읽어 사용량을 확인하므로 짧게 둔다. */
    private static final int ANTHROPIC_CHUNKS = 3;

    public StubUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            capture(exchange);
            if (compactBody().contains("\"stream\":true")) {
                streamResponse(exchange);
            } else {
                unaryResponse(exchange);
            }
        });
        // Anthropic 네이티브 경로. Claude Code 가 쓰는 포맷을 흉내낸다.
        server.createContext("/v1/messages", exchange -> {
            capture(exchange);
            if (compactBody().contains("\"stream\":true")) {
                anthropicStreamResponse(exchange);
            } else {
                anthropicUnaryResponse(exchange);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    private void capture(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        lastRequestHeaders = exchange.getRequestHeaders();
        lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Anthropic 비스트리밍 응답. <b>캐시 토큰을 일부러 0 이 아니게</b> 돌려준다 —
     * 캐시 단가가 비용 계산에 반영되는지 확인할 수 있어야 하기 때문이다.
     */
    private void anthropicUnaryResponse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] body = ("""
                {"id":"msg_stub","type":"message","role":"assistant","model":"claude-sonnet-5",
                 "content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":10,"cache_creation_input_tokens":200,
                          "cache_read_input_tokens":1000,"output_tokens":5}}
                """).replace("\n", "").replace("  ", "").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Anthropic 스트리밍 응답.
     *
     * <p>핵심은 <b>사용량이 두 프레임에 나뉘어</b> 온다는 것이다: 입력 토큰은 {@code message_start},
     * 출력 토큰은 {@code message_delta}. 게이트웨이가 뒤 프레임으로 앞 프레임을 덮어쓰면
     * 입력 토큰이 0 으로 지워진다 — 그 회귀를 잡기 위한 스텁이다.
     * OpenAI 와 달리 {@code [DONE]} 이 없다.
     */
    private void anthropicStreamResponse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            write(out, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_stub\","
                    + "\"model\":\"claude-sonnet-5\",\"usage\":{\"input_tokens\":10,"
                    + "\"cache_creation_input_tokens\":200,"
                    // 실제 응답처럼 TTL 내역을 함께 보낸다: 5분 50 + 1시간 150 = 200
                    + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":50,"
                    + "\"ephemeral_1h_input_tokens\":150},"
                    + "\"cache_read_input_tokens\":1000,"
                    + "\"output_tokens\":1}}}");
            for (int i = 0; i < ANTHROPIC_CHUNKS; i++) {
                write(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"tok" + i + " \"}}");
                chunksSent.incrementAndGet();
                Thread.sleep(CHUNK_DELAY_MS);
            }
            // 출력 토큰은 여기서 확정된다. input_tokens 는 이 프레임에 없다.
            write(out, "message_delta", "{\"type\":\"message_delta\","
                    + "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}");
            write(out, "message_stop", "{\"type\":\"message_stop\"}");
            out.close();
        } catch (IOException e) {
            disconnects.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void unaryResponse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] body = ("""
                {"id":"chatcmpl-stub","object":"chat.completion","model":"gpt-4o-mini",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}
                """).replace("\n", "").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void streamResponse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked
        OutputStream out = exchange.getResponseBody();
        try {
            for (int i = 0; i < MAX_CHUNKS; i++) {
                String frame = "data: {\"id\":\"chatcmpl-stub\",\"model\":\"gpt-4o-mini\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok" + i + " \"}}]}\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                chunksSent.incrementAndGet();
                Thread.sleep(CHUNK_DELAY_MS);
            }
            // 마지막 usage 프레임 — stream_options.include_usage 를 요청했을 때만 보낸다.
            if (compactBody().contains("\"include_usage\":true")) {
                out.write(("data: {\"id\":\"chatcmpl-stub\",\"model\":\"gpt-4o-mini\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":60,\"total_tokens\":71}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (IOException e) {
            // 클라이언트가 사라졌다. 이게 바로 취소 전파의 증거다.
            disconnects.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String compactBody() {
        return lastRequestBody.replace(" ", "").replace("\n", "");
    }

    /** 업스트림에서 끊김이 최소 n번 관측될 때까지 기다린다. */
    public boolean awaitDisconnects(int atLeast, java.time.Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (disconnects.get() >= atLeast) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
