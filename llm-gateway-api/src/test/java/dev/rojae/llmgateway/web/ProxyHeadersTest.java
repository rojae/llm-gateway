package dev.rojae.llmgateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyHeadersTest {
    @Test void gatewaySessionAndCsrfDoNotReachProviderOrReturnFromIt() {
        HttpHeaders inbound = new HttpHeaders();
        inbound.add(HttpHeaders.COOKIE, "LLMGATEWAYSESSION=session-secret");
        inbound.add("X-CSRF-TOKEN", "csrf-secret");
        inbound.add("X-Forwarded-Host", "admin.example.com");
        inbound.add("anthropic-version", "2023-06-01");
        inbound.add(HttpHeaders.AUTHORIZATION, "Bearer provider-key");
        HttpHeaders forwarded = new HttpHeaders();
        ProxyHeaders.copyRequest(inbound, forwarded);
        assertThat(forwarded.containsKey(HttpHeaders.COOKIE)).isFalse();
        assertThat(forwarded.containsKey("X-CSRF-TOKEN")).isFalse();
        assertThat(forwarded.containsKey("X-Forwarded-Host")).isFalse();
        assertThat(forwarded.getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(forwarded.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer provider-key");

        HttpHeaders upstream = new HttpHeaders();
        upstream.add(HttpHeaders.SET_COOKIE, "LLMGATEWAYSESSION=attacker; Path=/");
        upstream.add("anthropic-ratelimit-unified-requests-remaining", "2");
        HttpHeaders response = new HttpHeaders();
        ProxyHeaders.copyResponse(upstream, response);
        assertThat(response.containsKey(HttpHeaders.SET_COOKIE)).isFalse();
        assertThat(response.getFirst("anthropic-ratelimit-unified-requests-remaining")).isEqualTo("2");
    }
}
