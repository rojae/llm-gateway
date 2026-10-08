package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.config.GatewayProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class TelemetrySecurityTest {
    static final String TOKEN="telemetry-test-token-at-least-thirty-two-characters";
    private final AnalyticsProperties analytics=new AnalyticsProperties();
    private final TelemetryProperties telemetry=new TelemetryProperties();
    private final GatewayProperties gateway=new GatewayProperties("http://localhost", "", Duration.ofSeconds(1),Duration.ofSeconds(1),true);
    TelemetrySecurityTest(){analytics.setEnabled(true);analytics.setAdminToken("operator-test-token-at-least-thirty-two-characters");telemetry.setEnabled(true);telemetry.setToken(TOKEN);}
    @Test void authenticationRunsBeforeBodyReadOnEncodedAndMatrixPaths() {
        for(String path:new String[]{"/telemetry/v1/logs","/tele%6Detry/v1/logs","/telemetry/v1/logs;v=1"}) {
            var read=new AtomicBoolean();var passed=new AtomicBoolean();
            var request=MockServerHttpRequest.method(org.springframework.http.HttpMethod.POST,java.net.URI.create(path)).body(Flux.defer(()->{read.set(true);return Flux.error(new AssertionError("body must not be subscribed"));}));
            var exchange=MockServerWebExchange.from(request);
            new TelemetryAuthFilter(telemetry,analytics,gateway).filter(exchange,e->{passed.set(true);return Mono.empty();}).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);assertThat(read).isFalse();assertThat(passed).isFalse();
        }
    }
    @Test void dedicatedTokenCannotReuseOperatorOrKnownProviderCredentials() {
        telemetry.setToken(analytics.getAdminToken());
        assertThatThrownBy(()->new TelemetryAuthFilter(telemetry,analytics,gateway)).isInstanceOf(IllegalArgumentException.class);
        telemetry.setToken("sk-ant-api03-provider-secret-long-enough-for-thirty-two");
        assertThatThrownBy(()->new TelemetryAuthFilter(telemetry,analytics,gateway)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validDedicatedTokenIsAcceptedAndDisabledCollectorIsUnavailable() {
        var request=MockServerHttpRequest.post("/telemetry/v1/logs").header("Authorization","Bearer "+TOKEN).build();
        var passed=new AtomicBoolean();
        new TelemetryAuthFilter(telemetry,analytics,gateway).filter(MockServerWebExchange.from(request),e->{passed.set(true);return Mono.empty();}).block();
        assertThat(passed).isTrue();
        telemetry.setEnabled(false);var exchange=MockServerWebExchange.from(request);
        new TelemetryAuthFilter(telemetry,analytics,gateway).filter(exchange,e->Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
