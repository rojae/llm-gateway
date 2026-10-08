package dev.rojae.llmgateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.observe.AccessLog;
import dev.rojae.llmgateway.observe.ContentLog;
import dev.rojae.llmgateway.observe.ApiFormat;
import dev.rojae.llmgateway.observe.KeyIdentity;
import dev.rojae.llmgateway.observe.RequestRecord;
import dev.rojae.llmgateway.observe.Usage;
import dev.rojae.llmgateway.observe.UsageSniffer;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * v0 프록시의 본체. 두 컨트롤러({@link ChatCompletionsController}, {@link MessagesController})가
 * 이 엔진을 공유하고, 포맷만 달리 넘긴다.
 *
 * <p>일부러 <b>하지 않는</b> 것들: 라우팅, 캐시, 쿼터, 재시도, 폴백.
 * v0의 목표는 "아무도 눈치채지 못하게 요청 경로에 끼어들어 기록을 남기는 것" 하나뿐이다.
 *
 * <p>틀리기 쉬운 지점이 셋이고, 이 클래스가 존재하는 이유가 사실상 그 셋이다.
 * <ol>
 *   <li><b>버퍼링 금지</b> — 청크는 도착 즉시 그대로 내보낸다. 모아두면 TTFT가 죽는다.</li>
 *   <li><b>취소 전파</b> — 호출자가 끊으면 업스트림 요청도 죽어야 한다.
 *       안 그러면 아무도 읽지 않을 토큰을 GPU가 계속 만들고 요금이 계속 나간다.
 *       WebFlux에서는 이게 거의 공짜다: 아래 Flux 구독이 취소되면
 *       WebClient 의 업스트림 구독도 함께 취소되고 커넥션이 닫힌다.</li>
 *   <li><b>상태 코드 선확정</b> — 본문을 쓰기 전에 업스트림 상태를 먼저 안다.
 *       그래야 429가 "200 안에 숨은 에러"로 둔갑하지 않는다.</li>
 * </ol>
 */
@Component
public class ProxyEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Anthropic 네이티브 API 가 요구하는 버전 헤더. 없으면 400 이 난다. */
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION_DEFAULT = "2023-06-01";
    private static final String ANTHROPIC_API_KEY_HEADER = "x-api-key";

    private final WebClient webClient;
    private final GatewayProperties props;
    private final AccessLog accessLog;
    private final KeyIdentity keyIdentity;
    private final ContentLog contentLog;

    public ProxyEngine(WebClient upstreamWebClient,
                       GatewayProperties props,
                       AccessLog accessLog,
                       KeyIdentity keyIdentity, ContentLog contentLog) {
        this.webClient = upstreamWebClient;
        this.props = props;
        this.accessLog = accessLog;
        this.keyIdentity = keyIdentity;
        this.contentLog = contentLog;
    }

    public Mono<Void> proxy(ServerWebExchange exchange, byte[] body, ApiFormat format) {
        return forward(exchange, body, format, HttpMethod.POST, format.upstreamPath(), true,
                props.normalizedBaseUrl(), false);
    }

    public Mono<Void> personal(ServerWebExchange exchange, byte[] body, ApiFormat format,
                               HttpMethod method, String path, boolean generation, String baseUrl) {
        return forward(exchange, body, format, method, path, generation, baseUrl, true);
    }

    /** Provider metadata endpoints do not consume generated-token usage. */
    public Mono<Void> metadata(ServerWebExchange exchange, byte[] body, ApiFormat format,
                               HttpMethod method, String upstreamPath) {
        return forward(exchange, body, format, method, upstreamPath, false, props.normalizedBaseUrl(), false);
    }

    private Mono<Void> forward(ServerWebExchange exchange, byte[] body, ApiFormat format,
                               HttpMethod method, String upstreamPath, boolean generation,
                               String baseUrl, boolean personal) {
        HttpHeaders inbound = exchange.getRequest().getHeaders();
        RequestRecord record = new RequestRecord(exchange.getRequest().getPath().value(), format);
        String host = URI.create(baseUrl).getHost();
        record.setProvider(personal ? (format == ApiFormat.ANTHROPIC ? "anthropic" : "openai")
                : "api.anthropic.com".equals(host) ? "anthropic"
                : "api.openai.com".equals(host) ? "openai" : "custom");
        record.setGeneration(generation);
        record.setMethod(method.name());
        ClaudeCodeHeaders.apply(inbound, record);
        record.setKeyId(keyIdentity.identify(effectiveCredential(inbound, format, personal)));

        byte[] payload = (body == null) ? new byte[0] : body;
        boolean streaming = false;
        JsonNode requestJson = null;

        try {
            JsonNode parsed = MAPPER.readTree(payload);
            requestJson = parsed;
            if (parsed != null && parsed.isObject()) {
                ObjectNode json = (ObjectNode) parsed;
                if (json.hasNonNull("model")) {
                    record.setModel(json.get("model").asText());
                }
                streaming = generation && json.path("stream").asBoolean(false);

                if (streaming && format.injectsStreamUsage() && props.injectStreamUsage()) {
                    // 이 한 줄이 없으면 OpenAI 스트리밍 요청의 토큰 수를 영영 알 수 없다.
                    // 주의: 마지막 프레임에 choices 가 빈 usage 이벤트가 추가되므로,
                    //      choices[0] 을 무조건 읽는 구형 클라이언트는 깨질 수 있다.
                    //      그럴 땐 gateway.inject-stream-usage=false 로 끈다.
                    // Anthropic 포맷에는 이 필드가 없어서 주입하면 400 이 난다 —
                    // 그래서 format.injectsStreamUsage() 가 먼저 막는다. 주입할 필요도 없다:
                    // message_delta 가 언제나 출력 토큰을 알려준다.
                    JsonNode existing = json.get("stream_options");
                    ObjectNode options = (existing != null && existing.isObject())
                            ? (ObjectNode) existing
                            : MAPPER.createObjectNode();
                    if (!options.has("include_usage")) {
                        options.put("include_usage", true);
                    }
                    json.set("stream_options", options);
                    payload = MAPPER.writeValueAsBytes(json);
                }
            }
        } catch (Exception ignored) {
            // JSON이 아니면 손대지 않고 그대로 흘려보낸다. 프록시는 관대해야 한다.
        }

        record.setStream(streaming);
        final boolean isStreaming = streaming;
        ContentLog.Exchange content = contentLog.start(record, format, requestJson);

        String query = exchange.getRequest().getURI().getRawQuery();
        URI target = URI.create(baseUrl + upstreamPath
                + (query == null ? "" : "?" + query));
        WebClient.RequestBodySpec request = webClient.method(method)
                .uri(target)
                .headers(headers -> {
                    ProxyHeaders.copyRequest(inbound, headers);
                    // The observer reads JSON/SSE bytes. Negotiate an uncompressed upstream
                    // response even when Claude Code advertises gzip/br/zstd support.
                    headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
                    applyCredential(headers, format, personal);
                    if (format == ApiFormat.ANTHROPIC
                            && !headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
                        // Claude Code 와 공식 SDK 는 항상 보내지만 curl 로 직접 부르면 빠진다.
                        // 없을 때만 채워 넣는다 — 호출자가 보낸 값은 절대 덮어쓰지 않는다.
                        headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_DEFAULT);
                    }
                    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                });
        WebClient.RequestHeadersSpec<?> outbound = method == HttpMethod.GET
                ? request : request.bodyValue(payload);
        return outbound
                .exchangeToMono(resp -> isStreaming
                        ? streamBack(exchange, record, resp, format, content)
                        : aggregateBack(exchange, record, resp, format, generation, content))
                .onErrorResume(error -> badGateway(exchange, record, error))
                .doFinally(signal -> {
                    record.setClientDisconnected(signal == SignalType.CANCEL);
                    record.finish(null);
                    accessLog.emit(record);
                    content.finish();
                });
    }

    private String effectiveCredential(HttpHeaders headers, ApiFormat format, boolean personal) {
        if (!personal && props.hasManagedKey()) {
            return props.upstreamApiKey();
        }
        // The same credential precedence is applied to the forwarded headers and keyId.
        if (personal && format == ApiFormat.ANTHROPIC && headers.containsKey(ANTHROPIC_API_KEY_HEADER)) {
            return headers.getFirst(ANTHROPIC_API_KEY_HEADER);
        }
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return format == ApiFormat.ANTHROPIC ? headers.getFirst(ANTHROPIC_API_KEY_HEADER) : null;
    }

    /**
     * managed 모드에서 업스트림 자격증명을 게이트웨이 키로 교체한다.
     *
     * <p>포맷에 따라 헤더 이름이 다르다. 그리고 <b>반대쪽 헤더를 지우는 것</b>이 중요하다 —
     * Claude Code 는 {@code Authorization} 과 {@code x-api-key} 를 둘 다 보낼 수 있는데,
     * 하나만 덮어쓰면 호출자의 키가 업스트림으로 함께 새어 나간다.
     */
    private void applyCredential(HttpHeaders headers, ApiFormat format, boolean personal) {
        if (personal) {
            if (format != ApiFormat.ANTHROPIC) {
                headers.remove(ANTHROPIC_API_KEY_HEADER);
            } else if (headers.containsKey(ANTHROPIC_API_KEY_HEADER)) {
                headers.remove(HttpHeaders.AUTHORIZATION);
            }
            return;
        }
        if (!props.hasManagedKey()) {
            return;     // passthrough: 호출자 자격증명을 그대로 전달한다
        }
        if (format == ApiFormat.ANTHROPIC) {
            headers.set(ANTHROPIC_API_KEY_HEADER, props.upstreamApiKey());
            headers.remove(HttpHeaders.AUTHORIZATION);
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + props.upstreamApiKey());
            headers.remove(ANTHROPIC_API_KEY_HEADER);
        }
    }

    /** 스트리밍 경로. 청크를 받는 즉시 내보낸다. */
    private Mono<Void> streamBack(ServerWebExchange exchange,
                                  RequestRecord record,
                                  ClientResponse resp,
                                  ApiFormat format, ContentLog.Exchange content) {

        ServerHttpResponse out = exchange.getResponse();
        record.setStatus(resp.statusCode().value());
        record.setUpstreamRequestId(providerRequestId(resp));
        out.setStatusCode(resp.statusCode());
        ProxyHeaders.copyResponse(resp.headers().asHttpHeaders(), out.getHeaders());
        out.getHeaders().set("x-gw-request-id", record.getRequestId());

        if (resp.statusCode().isError()) {
            // 에러는 스트림이 아니다. 통째로 읽어서 그대로 돌려준다.
            return resp.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> {
                        observeContentResponse(content, bytes);
                        UpstreamDiagnostics.observe(record, format, bytes,
                                resp.headers().asHttpHeaders(), resp.statusCode().value());
                        return out.writeWith(Mono.just(out.bufferFactory().wrap(bytes)));
                    });
        }

        // 중간 프록시(nginx/ingress)가 SSE를 모아두지 못하게 막는다.
        out.getHeaders().setCacheControl("no-cache");
        out.getHeaders().set("X-Accel-Buffering", "no");
        // 비용과 토큰 수는 여기에 담을 수 없다. 응답 헤더는 첫 토큰보다 먼저 나가는데
        // 그 값들은 스트림이 끝나야 알 수 있기 때문이다. 대신 접근 로그에 남긴다.

        UsageSniffer sniffer = new UsageSniffer(format, content::event,
                contentLog.maxEventBytes(), content::skippedEvent);

        Flux<DataBuffer> bodyFlux = resp.bodyToFlux(DataBuffer.class)
                .map(buffer -> {
                    // 바이트를 우리 배열로 복사한 뒤 원본 버퍼는 즉시 반납한다.
                    // (netty 버퍼를 들고 있으면 누수가 난다)
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);

                    record.markFirstByte();
                    sniffer.feed(bytes);           // 나가는 바이트는 그대로, 복사본만 엿본다
                    return out.bufferFactory().wrap(bytes);
                });

        Runnable finishUsage = () -> {
            sniffer.finish();
            if (sniffer.model() != null) {
                record.setModel(sniffer.model());
            }
            if (sniffer.upstreamError() != null) {
                record.setUpstreamError(sniffer.upstreamError());
            } else if (sniffer.incompleteResponseStream()) {
                record.setUpstreamError("stream_incomplete");
            }
            record.finish(sniffer.usage());
        };
        // Finalize observation before the terminal signal reaches the single access-log hook.
        // Transport failures must reach the outer handler: before commit they become 502,
        // after commit they abort the response instead of silently completing a truncated SSE.
        return out.writeWith(bodyFlux)
                .doOnTerminate(finishUsage)
                .doOnCancel(finishUsage);
    }

    /** 비스트리밍 경로. 본문을 다 받은 뒤이므로 비용을 응답 헤더에 담을 수 있다. */
    private Mono<Void> aggregateBack(ServerWebExchange exchange,
                                     RequestRecord record,
                                     ClientResponse resp,
                                     ApiFormat format,
                                     boolean generation, ContentLog.Exchange content) {

        ServerHttpResponse out = exchange.getResponse();
        record.setStatus(resp.statusCode().value());
        record.setUpstreamRequestId(providerRequestId(resp));

        return resp.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    Usage usage = null;
                    if (resp.statusCode().isError()) {
                        UpstreamDiagnostics.observe(record, format, bytes,
                                resp.headers().asHttpHeaders(), resp.statusCode().value());
                    }
                    try {
                        JsonNode json = MAPPER.readTree(bytes);
                        content.response(json);
                        if (generation && !resp.statusCode().isError() && json != null && json.isObject()) {
                            String model = format.modelFrom(json);
                            if (model != null) {
                                record.setModel(model);
                            }
                            usage = format.mergeUsage(null, json);
                            record.setUpstreamError(format.errorFrom(json));
                        }
                    } catch (Exception ignored) {
                        content.skippedEvent();
                        // 본문이 JSON이 아니어도 프록시는 계속 동작해야 한다.
                    }

                    record.finish(usage);

                    out.setStatusCode(resp.statusCode());
                    ProxyHeaders.copyResponse(resp.headers().asHttpHeaders(), out.getHeaders());
                    out.getHeaders().set("x-gw-request-id", record.getRequestId());
                    if (record.getModel() != null) {
                        out.getHeaders().set("x-gw-model", record.getModel());
                    }
                    if (record.getCostUsd() != null) {
                        out.getHeaders().set("x-gw-cost-usd", String.format(java.util.Locale.ROOT, "%.6f", record.getCostUsd()));
                    }
                    if (record.getTotalMs() != null) {
                        out.getHeaders().set("x-gw-upstream-ms", String.valueOf(record.getTotalMs()));
                    }
                    out.getHeaders().setContentLength(bytes.length);

                    return out.writeWith(Mono.just(out.bufferFactory().wrap(bytes)));
                });
    }

    private static String providerRequestId(ClientResponse response) {
        HttpHeaders headers = response.headers().asHttpHeaders();
        String id = headers.getFirst("request-id");
        return id != null ? id : headers.getFirst("x-request-id");
    }

    private static void observeContentResponse(ContentLog.Exchange content, byte[] bytes) {
        if (!content.enabled()) return;
        try { content.response(MAPPER.readTree(bytes)); }
        catch (Exception ignored) { content.skippedEvent(); }
    }

    /** 커밋 전 전송 실패는 502, 커밋 후 실패는 연결을 종료한다. */
    private Mono<Void> badGateway(ServerWebExchange exchange, RequestRecord record, Throwable error) {
        record.setUpstreamError(error.getClass().getSimpleName());

        ServerHttpResponse out = exchange.getResponse();
        if (out.isCommitted()) {
            return Mono.error(error);
        }
        record.setStatus(502);
        out.getHeaders().clear();
        byte[] payload = ("{\"error\":{\"message\":\"upstream request failed\","
                + "\"type\":\"bad_gateway\",\"request_id\":\"" + record.getRequestId() + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        out.setStatusCode(HttpStatus.BAD_GATEWAY);
        out.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        out.getHeaders().set("x-gw-request-id", record.getRequestId());
        out.getHeaders().setContentLength(payload.length);
        return out.writeWith(Mono.just(out.bufferFactory().wrap(payload)));
    }
}
