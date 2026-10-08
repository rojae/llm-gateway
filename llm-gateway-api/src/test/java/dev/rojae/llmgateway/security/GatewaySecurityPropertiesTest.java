package dev.rojae.llmgateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewaySecurityPropertiesTest {
    @Test void adminUiRedirectAcceptsConfiguredFrontendOrigins() {
        for (String url : new String[]{"/admin/", "https://console.example.com/admin", "http://localhost:3000/admin"}) {
            var properties = new GatewaySecurityProperties();
            properties.setAdminUiUrl(url);
            assertThatCode(properties::validate).as(url).doesNotThrowAnyException();
        }
    }

    @Test void adminUiRedirectRejectsUnsafeDestinations() {
        for (String url : new String[]{"//evil.example/admin", "http://evil.example/admin", "https://user@evil.example/admin", "https://console.example/admin?next=evil", "javascript:alert(1)"}) {
            var properties = new GatewaySecurityProperties();
            properties.setAdminUiUrl(url);
            assertThatThrownBy(properties::validate).as(url).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
