package dev.rojae.llmgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기존 /v1/** 단일 업스트림과 공통 전송 설정. 공급자별 경로는 ProviderProperties를 사용한다.
 *
 * @param upstreamBaseUrl  기존 /v1/** 요청의 업스트림 (예: https://api.openai.com/v1)
 * @param upstreamApiKey   값이 있으면 게이트웨이가 키를 소유한다(managed mode).
 *                         비어 있으면 호출자의 Authorization 헤더를 그대로 흘려보낸다(passthrough mode).
 *                         passthrough가 온보딩 마찰을 0으로 만드는 핵심이다 — 팀은 base_url만 바꾸면 된다.
 * @param connectTimeout   TCP 연결 수립까지 허용 시간. 짧아야 한다.
 * @param readTimeout      "청크와 청크 사이" 최대 간격. 전체 응답 시간이 아니다.
 *                         LLM 스트리밍은 전체가 수 분일 수 있으므로 총시간으로 끊으면 안 된다.
 * @param injectStreamUsage 스트리밍 요청에 stream_options.include_usage 를 주입할지 여부.
 *                          끄면 스트리밍 요청의 토큰 수를 영영 알 수 없다.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue("https://api.openai.com/v1") String upstreamBaseUrl,
        @DefaultValue("") String upstreamApiKey,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout,
        @DefaultValue("true") boolean injectStreamUsage) {

    public String normalizedBaseUrl() {
        return upstreamBaseUrl.endsWith("/")
                ? upstreamBaseUrl.substring(0, upstreamBaseUrl.length() - 1)
                : upstreamBaseUrl;
    }

    public boolean hasManagedKey() {
        return upstreamApiKey != null && !upstreamApiKey.isBlank();
    }
}
