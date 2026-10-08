package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.security.GatewaySecurityProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class EnterpriseTelemetryTest {
    AnalyticsProperties analytics;TelemetryProperties telemetry;GatewaySecurityProperties security;AnalyticsDatabase database;CollectorCredentialService credentials;
    String tenantA="telemetry-a-"+UUID.randomUUID(),tenantB="telemetry-b-"+UUID.randomUUID();
    GatewayProperties gateway=new GatewayProperties("http://localhost","",Duration.ofSeconds(1),Duration.ofSeconds(1),true);
    @BeforeEach void setup()throws Exception{
        analytics=new AnalyticsProperties();analytics.setEnabled(true);analytics.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));analytics.setUsername(System.getenv("GW_TEST_DB_USER"));analytics.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        telemetry=new TelemetryProperties();telemetry.setEnabled(true);telemetry.setToken("");
        security=new GatewaySecurityProperties();security.setMode("enterprise");security.setAllowedTenants(List.of(tenantA,tenantB));
        database=new AnalyticsDatabase(analytics);database.initialize();credentials=new CollectorCredentialService(database,new AuditService(database));
    }
    @AfterEach void closeDatabase(){if(database!=null)database.close();}
    @Test void databaseAuthenticationPrecedesBodyAndRevocationHasNoCachedGracePeriod()throws Exception{
        var issued=credentials.create(tenantA,"admin","agent",30,"subscription_declared");
        var filter=new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials);
        try{
            var accepted=exchange(issued.token());var passed=new AtomicBoolean();
            filter.filter(accepted,e->{passed.set(true);return Mono.empty();}).block();assertThat(passed).isTrue();
            credentials.revoke(tenantA,"admin",issued.credential().id());
            var read=new AtomicBoolean();var request=MockServerHttpRequest.post("/telemetry/v1/logs").header("Authorization","Bearer "+issued.token()).body(Flux.defer(()->{read.set(true);return Flux.error(new AssertionError("revoked body must not be read"));}));
            var denied=MockServerWebExchange.from(request);filter.filter(denied,e->Mono.error(new AssertionError("revoked key passed"))).block();
            assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(read).isFalse();
        }finally{filter.stop();}
    }
    @Test void keyBindingOverridesForgedPayloadTenantAndBillingAndDedupIsTenantScoped()throws Exception{
        var a=credentials.create(tenantA,"admin-a","a",30,"subscription_declared");var b=credentials.create(tenantB,"admin-b","b",30,"unknown");
        var record=TelemetryNormalizerTest.record("api_request");TelemetryNormalizerTest.attr(record,"request_id","stringValue",UUID.randomUUID().toString());
        TelemetryNormalizerTest.attr(record,"tenant_id","stringValue","forged-tenant");TelemetryNormalizerTest.attr(record,"billingMode","stringValue","api_key_declared");
        byte[] payload=TelemetryNormalizerTest.envelope(record);
        var filter=new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials);
        var controller=new TelemetryController(database,telemetry,credentials);
        try{
            for(var issued:List.of(a,b)){
                var request=MockServerHttpRequest.post("/telemetry/v1/logs").header("Authorization","Bearer "+issued.token()).header("Content-Type","application/json").body(new String(payload,java.nio.charset.StandardCharsets.UTF_8));
                var exchange=MockServerWebExchange.from(request);
                filter.filter(exchange,e->controller.logs(e).doOnNext(response->assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK)).then()).block();
            }
            String id=TelemetryNormalizerTest.JSON.readTree(new TelemetryNormalizer(telemetry).normalize(payload).records().getFirst()).path("requestId").asText();
            try(var c=database.open();var q=c.prepareStatement("SELECT tenant_id,billing_mode,record::text FROM llm_gateway_analytics.requests WHERE request_id=? ORDER BY tenant_id")){
                q.setString(1,id);try(var r=q.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(tenantA);assertThat(r.getString(2)).isEqualTo("subscription_declared");assertThat(r.getString(3)).doesNotContain("forged-tenant");assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(tenantB);assertThat(r.getString(2)).isEqualTo("unknown");assertThat(r.next()).isFalse();}
            }
        }finally{filter.stop();controller.stop();}
    }
    @Test void enterpriseDoesNotAcceptStaticFallbackAndDatabaseOutageFailsBeforeBody()throws Exception{
        telemetry.setToken("legacy-static-token-at-least-thirty-two-characters");
        assertThatThrownBy(()->new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials)).isInstanceOf(IllegalArgumentException.class);telemetry.setToken("");
        var issued=credentials.create(tenantA,"admin","agent",30,"unknown");var filter=new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials);
        String original=analytics.getJdbcUrl();
        try{
            analytics.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/PRIVATE_DATABASE");
            // Existing pools may remain alive after configuration mutation; close it before opening the bad endpoint.
            var unavailable=new AnalyticsDatabase(analytics);var bad=new CollectorCredentialService(unavailable,new AuditService(unavailable));var failing=new TelemetryAuthFilter(telemetry,analytics,gateway,security,bad);
            try{var exchange=exchange(issued.token());failing.filter(exchange,e->Mono.error(new AssertionError("DB failure passed body"))).block();assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("PRIVATE_DATABASE");}finally{failing.stop();unavailable.close();}
        }finally{analytics.setJdbcUrl(original);filter.stop();}
    }
    @Test void removedTenantIsRejectedBeforeBody()throws Exception{
        var issued=credentials.create(tenantA,"admin","agent",30,"unknown");
        security.setAllowedTenants(List.of(tenantB));var filter=new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials);
        try{var denied=exchange(issued.token());filter.filter(denied,e->Mono.error(new AssertionError("Removed tenant passed"))).block();assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}finally{filter.stop();}
    }
    @Test void revocationDuringBodyDeliveryPreventsPersistence()throws Exception{
        var issued=credentials.create(tenantA,"admin","agent",30,"unknown");
        var record=TelemetryNormalizerTest.record("api_request");TelemetryNormalizerTest.attr(record,"request_id","stringValue",UUID.randomUUID().toString());byte[] payload=TelemetryNormalizerTest.envelope(record);
        var body=Flux.defer(()->{try{credentials.revoke(tenantA,"admin",issued.credential().id());return Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(payload));}catch(Exception failure){return Flux.error(failure);}});
        var request=MockServerHttpRequest.post("/telemetry/v1/logs").header("Authorization","Bearer "+issued.token()).header("Content-Type","application/json").body(body);
        var filter=new TelemetryAuthFilter(telemetry,analytics,gateway,security,credentials);var controller=new TelemetryController(database,telemetry,credentials);
        try{
            filter.filter(MockServerWebExchange.from(request),e->controller.logs(e).doOnNext(response->assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)).then()).block();
            try(var c=database.open();var q=c.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.requests WHERE tenant_id=?")){q.setString(1,tenantA);try(var r=q.executeQuery()){r.next();assertThat(r.getLong(1)).isZero();}}
        }finally{filter.stop();controller.stop();}
    }
    private MockServerWebExchange exchange(String token){return MockServerWebExchange.from(MockServerHttpRequest.post("/telemetry/v1/logs").header("Authorization","Bearer "+token).build());}
}
