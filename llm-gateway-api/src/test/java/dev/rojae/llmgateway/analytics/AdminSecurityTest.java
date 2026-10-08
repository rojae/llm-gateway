package dev.rojae.llmgateway.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class AdminSecurityTest {
    private static final String TOKEN = "test-admin-token-only-at-least-32-characters";

    @Test void disabledAndWeakConfigurationFailClosed() {
        AnalyticsProperties props = new AnalyticsProperties();
        props.setAdminToken(TOKEN);
        assertThat(status(props, MockServerHttpRequest.get("/admin/api/session").header("Authorization", "Bearer " + TOKEN).build())).isEqualTo(503);
        props.setEnabled(true);
        props.setAdminToken("weak");
        assertThat(status(props, MockServerHttpRequest.get("/admin/api/requests").header("Authorization", "Bearer weak").build())).isEqualTo(503);
    }

    @Test void missingWrongDuplicateAndMalformedCredentialsCannotReadAnyAdminApi() {
        AnalyticsProperties props = enabled();
        for (String path : new String[]{"/admin/api", "/admin/api/session", "/admin/api/requests/missing", "/admin/api/unknown"}) {
            assertThat(status(props, MockServerHttpRequest.get(path).build())).isEqualTo(401);
            assertThat(status(props, MockServerHttpRequest.get(path).header("Authorization", "Bearer wrong").build())).isEqualTo(401);
            assertThat(status(props, MockServerHttpRequest.get(path).header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN).build())).isEqualTo(401);
            assertThat(status(props, MockServerHttpRequest.get(path).header("Authorization", "Basic " + TOKEN).build())).isEqualTo(401);
        }
    }

    @Test void authenticatedApiAndPublicShellPassWithoutTokenReflection() {
        AnalyticsProperties props = enabled();
        assertThat(status(props, MockServerHttpRequest.get("/admin/api/session").header("Authorization", "Bearer " + TOKEN).build())).isEqualTo(200);
        assertThat(status(props, MockServerHttpRequest.get("/admin/").build())).isEqualTo(200);
    }

    private AnalyticsProperties enabled() {
        var props = new AnalyticsProperties(); props.setEnabled(true); props.setAdminToken(TOKEN); return props;
    }

    private int status(AnalyticsProperties props, org.springframework.http.server.reactive.ServerHttpRequest request) {
        var exchange = MockServerWebExchange.from((MockServerHttpRequest) request);
        var passed = new AtomicBoolean();
        new AdminAuthFilter(props).filter(exchange, ex -> { passed.set(true); return ex.getResponse().setComplete(); }).block();
        if (request.getPath().value().startsWith("/admin/api")) {
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(TOKEN);
        }
        return passed.get() ? 200 : exchange.getResponse().getStatusCode().value();
    }
}
