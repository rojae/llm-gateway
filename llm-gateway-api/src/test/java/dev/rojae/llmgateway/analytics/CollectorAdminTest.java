package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class CollectorAdminTest {
    AnalyticsDatabase database;CollectorCredentialService service;AuditService audit;AdminAccess access;
    CollectorCredentialController collectors;AuditController audits;WebTestClient client;
    String a="admin-a-"+UUID.randomUUID(),b="admin-b-"+UUID.randomUUID();
    @BeforeEach void setup()throws Exception{
        var props=new AnalyticsProperties();props.setEnabled(true);props.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));props.setUsername(System.getenv("GW_TEST_DB_USER"));props.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        database=new AnalyticsDatabase(props);database.initialize();audit=new AuditService(database);service=new CollectorCredentialService(database,audit);
        var beans=new StaticListableBeanFactory();beans.addBean("audit",audit);var security=new GatewaySecurityProperties();access=new AdminAccess(security,new TenantClaims(security),beans.getBeanProvider(AuditService.class));
        collectors=new CollectorCredentialController(service,access);audits=new AuditController(audit,access);
        client=WebTestClient.bindToController(collectors,audits).webFilter((exchange,chain)->{
            String tenant=exchange.getRequest().getHeaders().getFirst("Test-Tenant"),role=exchange.getRequest().getHeaders().getFirst("Test-Role");
            if(tenant!=null)exchange.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,new TenantPrincipal(tenant,"test-admin",Set.of(role==null?"admin":role)));
            return chain.filter(exchange);
        }).build();
    }
    @AfterEach void close(){if(collectors!=null)collectors.close();if(audits!=null)audits.close();if(access!=null)access.close();if(database!=null)database.close();}
    @Test void createReturnsSecretOnceAndTenantCannotBeForged()throws Exception{
        client.post().uri("/admin/api/collectors").header("Test-Tenant",a).bodyValue(Map.of("name","agent","tenantId",b)).exchange().expectStatus().isBadRequest();
        var body=client.post().uri("/admin/api/collectors").header("Test-Tenant",a).bodyValue(Map.of("name","agent")).exchange().expectStatus().isCreated().expectHeader().valueEquals("Cache-Control","no-store").expectBody(Map.class).returnResult().getResponseBody();
        String token=(String)body.get("token");assertThat(service.authenticate(token).orElseThrow().tenantId()).isEqualTo(a);
        client.get().uri("/admin/api/collectors").header("Test-Tenant",a).exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1).consumeWith(r->assertThat(new String(r.getResponseBody())).doesNotContain(token,"token_hash"));
        client.get().uri("/admin/api/collectors").header("Test-Tenant",b).exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
    }
    @Test void revokeAndAuditRemainTenantScoped()throws Exception{
        var key=service.create(a,"owner","agent",30,"unknown");
        client.delete().uri("/admin/api/collectors/"+key.credential().id()).header("Test-Tenant",b).exchange().expectStatus().isNoContent();
        assertThat(service.authenticate(key.token())).isPresent();
        client.get().uri("/admin/api/audit").header("Test-Tenant",b).exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].action").isEqualTo("collector.revoke").jsonPath("$.items[0].outcome").isEqualTo("denied");
        client.delete().uri("/admin/api/collectors/"+key.credential().id()).header("Test-Tenant",a).exchange().expectStatus().isNoContent();assertThat(service.authenticate(key.token())).isEmpty();
        client.get().uri("/admin/api/audit?limit=101").header("Test-Tenant",a).exchange().expectStatus().isBadRequest();
    }
    @Test void paginationRetainsOlderKeysAndCreateBodyIsBounded()throws Exception{
        var older=service.create(a,"owner","older",30,"unknown");service.create(a,"owner","newer",30,"unknown");
        client.get().uri("/admin/api/collectors?limit=1&offset=1").header("Test-Tenant",a).exchange().expectStatus().isOk().expectBody().jsonPath("$.total").isEqualTo(2).jsonPath("$.items[0].id").isEqualTo(older.credential().id().toString());
        client.get().uri("/admin/api/collectors?limit=101").header("Test-Tenant",a).exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/api/collectors").header("Test-Tenant",a).header("Content-Type","application/json").bodyValue("x".repeat(9000)).exchange().expectStatus().isEqualTo(413);
        assertThat(service.list(a)).hasSize(2);
    }
    @Test void permissionCheckPrecedesBodyAndDenialIsAudited()throws Exception{
        client.post().uri("/admin/api/collectors").header("Test-Tenant",a).header("Test-Role","viewer").header("Content-Type","application/json").bodyValue("not-json").exchange().expectStatus().isForbidden();
        assertThat(service.list(a)).isEmpty();assertThat(audit.list(a,25,0).items()).anySatisfy(e->assertThat(e.action()).isEqualTo("access.denied"));
        client.get().uri("/admin/api/audit").header("Test-Tenant",a).header("Test-Role","operator").exchange().expectStatus().isForbidden();
        client.get().uri("/admin/api/collectors").exchange().expectStatus().isUnauthorized();
    }
}
