package dev.rojae.llmgateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient upstreamWebClient(GatewayProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) props.connectTimeout().toMillis())
                // ReadTimeoutHandler 는 "마지막으로 바이트를 읽은 뒤 경과 시간"을 잰다.
                // 즉 청크 간 간격 타임아웃이다. 전체 응답 시간을 제한하는 것이 아니므로
                // 10분짜리 긴 생성도 청크만 꾸준히 오면 끊기지 않는다. 이게 SSE에 맞는 설정이다.
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(props.readTimeout().toMillis(), TimeUnit.MILLISECONDS)))
                // 리다이렉트를 따라가면 프록시가 의도치 않은 호스트로 요청을 보낼 수 있다.
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 비스트리밍 응답을 통째로 메모리에 올릴 때의 상한. 기본 256KB는 LLM 응답에 작다.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
