package dev.rojae.llmgateway.security;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantClaimsTest {
    @Test void trustedClaimMapsFiniteRolesAndNeverGrantsViewerContent() {
        var p=new GatewaySecurityProperties();p.setAllowedTenants(List.of("tenant-a"));
        var claims=new TenantClaims(p);
        var viewer=claims.resolve(Map.of("sub","user-1","tenant_id","tenant-a","roles",List.of("viewer")));
        assertThat(viewer.tenantId()).isEqualTo("tenant-a");assertThat(viewer.hasPermission("usage:read")).isTrue();
        assertThat(viewer.hasPermission("content:read")).isFalse();
        assertThat(claims.resolve(Map.of("sub","admin","tenant_id","tenant-a","roles",List.of("admin"))).hasPermission("collectors:manage")).isTrue();
        for(var invalid:List.of(Map.of("sub","u","tenant_id","other","roles",List.of("admin")),
                Map.of("sub","u","tenant_id","tenant-a","roles",List.of("global-admin")),
                Map.of("sub","u","tenant_id","tenant-a","roles","admin"),
                Map.of("sub","u","tenant_id",List.of("tenant-a"),"roles",List.of("admin")),
                Map.of("sub","","tenant_id","tenant-a","roles",List.of("viewer")))) {
            assertThatThrownBy(()->claims.resolve((Map)invalid)).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        }
    }
    @Test void enterpriseConfigRejectsUntrustedHttpAndRequiresExplicitLoopbackException() {
        var p=new GatewaySecurityProperties();p.setMode("enterprise");p.setClientId("app");p.setClientSecret("test-only");p.setAudience("api");
        p.setAllowedTenants(List.of("tenant-a"));p.setIssuerUri("http://127.0.0.1:9999/issuer");p.setExternalBaseUrl("http://127.0.0.1:9998");
        assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
        p.setAllowInsecureLocalOidc(true);assertThatCode(p::validate).doesNotThrowAnyException();
        p.setExternalBaseUrl("http://public.example");assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
        p.setExternalBaseUrl("https://app.example?redirect=evil");assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
