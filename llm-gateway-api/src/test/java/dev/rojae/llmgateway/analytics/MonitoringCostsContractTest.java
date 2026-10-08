package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.*;
import dev.rojae.llmgateway.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL plus HTTP authorization, validation and tenant-owner contract. */
@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class MonitoringCostsContractTest {
    static final ObjectMapper JSON=new ObjectMapper();
    final String tenant="cost-a-"+UUID.randomUUID(),foreign="cost-b-"+UUID.randomUUID();
    AnalyticsDatabase db;AuditService audit;AdminAccess access;MonitoringCostsController controller;MonitoringController monitoring;WebTestClient client;
    UUID owner,other,foreignOwner,session,otherSession,question,outside;
    @BeforeEach void setup()throws Exception{
        var props=new AnalyticsProperties();props.setEnabled(true);props.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));props.setUsername(System.getenv("GW_TEST_DB_USER"));props.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        db=new AnalyticsDatabase(props);db.initialize();audit=new AuditService(db);
        var security=new GatewaySecurityProperties();var beans=new StaticListableBeanFactory();beans.addBean("audit",audit);
        access=new AdminAccess(security,new TenantClaims(security),beans.getBeanProvider(AuditService.class));
        controller=new MonitoringCostsController(new MonitoringCosts(db,audit),access);monitoring=new MonitoringController(new MonitoringQueries(db,audit),access);
        client=WebTestClient.bindToController(controller,monitoring).webFilter((e,c)->{String t=e.getRequest().getHeaders().getFirst("Test-Tenant");if(t!=null)e.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,new TenantPrincipal(t,"fixture",Set.of(Optional.ofNullable(e.getRequest().getHeaders().getFirst("Test-Role")).orElse("admin"))));return c.filter(e);}).configureClient().responseTimeout(Duration.ofSeconds(10)).build();
        var queries=new MonitoringQueries(db,audit);owner=(UUID)queries.createAccount(tenant,"fixture","Owner").get("id");other=(UUID)queries.createAccount(tenant,"fixture","Other").get("id");foreignOwner=(UUID)queries.createAccount(foreign,"fixture","Foreign").get("id");
        session=session(owner,"codex");otherSession=session(other,"codex");question=question(session,"2026-10-05T03:00:00Z");outside=question(session,"2026-09-05T03:00:00Z");
        question(session(owner,"claude_code"),"2026-10-06T03:00:00Z");question(session,"2026-10-07T03:00:00Z");
    }
    @AfterEach void close(){if(controller!=null)controller.close();if(monitoring!=null)monitoring.close();if(access!=null)access.close();if(db!=null)db.close();}
    @Test void missingZeroMonthIsolationAndAllCliDenominator()throws Exception{
        var missing=get(owner,scope(session,question));assertThat(missing.path("subscription").path("amount").isNull()).isTrue();assertThat(missing.path("subscription").path("perQuestion").isNull()).isTrue();
        put(owner,"admin","{\"month\":\"2026-10\",\"amount\":20,\"currency\":\"USD\"}").expectStatus().isOk();
        var report=get(owner,scope(session,question));assertThat(report.path("subscription").path("questionCount").asLong()).isEqualTo(3);assertThat(report.path("subscription").path("sessionCount").asLong()).isEqualTo(2);
        assertThat(report.path("subscription").path("perQuestion").decimalValue()).isEqualByComparingTo("6.666667");assertThat(report.path("session").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("13.333333");
        assertThat(get(owner,scope(session,outside)).path("question").path("subscriptionAllocation").isNull()).isTrue();assertThat(get(owner,"?month=2026-09&zone=Asia/Seoul").path("subscription").path("amount").isNull()).isTrue();
        put(owner,"admin","{\"month\":\"2026-10\",\"amount\":0,\"currency\":\"KRW\"}").expectStatus().isOk();assertThat(get(owner,scope(session,question)).path("subscription").path("perQuestion").decimalValue()).isEqualByComparingTo("0");
        put(other,"admin","{\"month\":\"2026-10\",\"amount\":0,\"currency\":\"USD\"}").expectStatus().isOk();assertThat(get(other,"?month=2026-10&zone=Asia/Seoul").path("subscription").path("perQuestion").isNull()).isTrue();
        put(owner,"admin","{\"month\":\"2026-10\",\"amount\":null,\"currency\":\"USD\"}").expectStatus().isOk();assertThat(get(owner,scope(session,question)).path("subscription").path("amount").isNull()).isTrue();
        assertThat(audit.list(tenant,100,0).items()).anyMatch(e->e.action().equals("monitoring.cost.configure"));
    }
    @Test void latestObservationIsIndependentOfEventPaginationAndDoesNotSum()throws Exception{
        UUID older=event("2026-10-01T03:00:00Z",12497,0,null),latest=event("2026-10-08T03:00:00Z",28985,10,12288L);event("2026-11-01T03:00:00Z",99999,1,null);
        var report=get(owner,scope(session,question));var observation=report.path("session").path("latestObservation");assertThat(observation.path("id").asText()).isEqualTo(latest.toString());assertThat(observation.path("apiEstimate").path("amountUsd").decimalValue()).isEqualByComparingTo("0.034723");
        var page=JSON.readTree(client.get().uri("/admin/api/monitoring/sessions/"+session+"/events?limit=1").header("Test-Tenant",tenant).header("Test-Role","viewer").exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody());
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(older.toString());assertThat(page.path("items").get(0).path("apiEstimate").path("amountUsd").decimalValue()).isEqualByComparingTo("0.024994");
        assertThat(report.toString()).doesNotContain("totalTokens","promptPreview","\"text\"","PRIVATE_TEST_PROMPT");
    }
    @Test void permissionTenantOwnerAndStrictRequestValidation()throws Exception{
        client.get().uri(path(owner)+"?month=2026-10&zone=UTC").exchange().expectStatus().isUnauthorized();
        for(String role:List.of("viewer","operator"))put(owner,role,"{\"month\":\"2026-10\",\"amount\":20,\"currency\":\"USD\"}").expectStatus().isForbidden();
        for(UUID wrong:List.of(other,foreignOwner))client.get().uri(path(wrong)+scope(session,question)).header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
        client.get().uri(path(owner)+scope(otherSession,question)).header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
        client.get().uri(path(owner)+scope(session,UUID.randomUUID())).header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
        for(String query:List.of("?month=2026-10&zone=UTC&month=2026-10","?month=2026-13&zone=UTC","?month=0000-01&zone=UTC","?month=2101-01&zone=UTC","?month=2026-10&zone=BAD","?month=2026-10&zone=UTC&unknown=1","?month=2026-10&zone=UTC&questionId="+question))client.get().uri(path(owner)+query).header("Test-Tenant",tenant).exchange().expectStatus().isBadRequest();
        for(String amount:List.of("-1","1000000001","0.1234567","\"20\"","true"))put(owner,"admin","{\"month\":\"2026-10\",\"amount\":"+amount+",\"currency\":\"USD\"}").expectStatus().isBadRequest();
        for(String body:List.of("{\"month\":\"2026-10\",\"amount\":1,\"amount\":2,\"currency\":\"USD\"}","{\"month\":\"2026-10\",\"amount\":1,\"currency\":\"USD\",\"extra\":1}","{\"month\":\"2026-10\",\"amount\":1,\"currency\":\"USD\"} {}"))put(owner,"admin",body).expectStatus().isBadRequest();
        put(foreignOwner,"admin","{\"month\":\"2026-10\",\"amount\":1,\"currency\":\"USD\"}").expectStatus().isNotFound();
    }
    @Test void auditFailureRollsBackMonthlyOverwrite()throws Exception{
        put(owner,"admin","{\"month\":\"2026-10\",\"amount\":20,\"currency\":\"USD\"}").expectStatus().isOk();
        AuditService failingAudit=new AuditService(db){
            @Override public void record(Connection connection,String tenant,String actor,String action,String target,String outcome)throws SQLException{throw new SQLException("Synthetic audit failure");}
        };
        var failingController=new MonitoringCostsController(new MonitoringCosts(db,failingAudit),access);
        try{
            var failingClient=WebTestClient.bindToController(failingController).webFilter((e,c)->{
                e.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,new TenantPrincipal(tenant,"fixture",Set.of("admin")));return c.filter(e);
            }).configureClient().responseTimeout(Duration.ofSeconds(10)).build();
            failingClient.put().uri(path(owner)+"/subscription").header("Content-Type","application/json")
                .bodyValue("{\"month\":\"2026-10\",\"amount\":99,\"currency\":\"KRW\"}").exchange().expectStatus().isEqualTo(503);
        }finally{failingController.close();}
        var subscription=get(owner,"?month=2026-10&zone=UTC").path("subscription");
        assertThat(subscription.path("amount").decimalValue()).isEqualByComparingTo("20");
        assertThat(subscription.path("currency").asText()).isEqualTo("USD");
        assertThat(audit.list(tenant,100,0).items().stream().filter(e->e.action().equals("monitoring.cost.configure")).count()).isEqualTo(1);
    }
    @Test void purgedRecordsDoNotParticipate()throws Exception{
        put(owner,"admin","{\"month\":\"2026-10\",\"amount\":30,\"currency\":\"USD\"}").expectStatus().isOk();
        try(var c=db.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_questions SET purged_at=now() WHERE tenant_id=? AND id=?")){q.setString(1,tenant);q.setObject(2,question);q.executeUpdate();}
        assertThat(get(owner,"?month=2026-10&zone=UTC").path("subscription").path("questionCount").asLong()).isEqualTo(2);
        client.get().uri(path(owner)+scope(session,question)).header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
        try(var c=db.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET purged_at=now() WHERE tenant_id=? AND id=?")){q.setString(1,tenant);q.setObject(2,session);q.executeUpdate();}
        assertThat(get(owner,"?month=2026-10&zone=UTC").path("subscription").path("questionCount").asLong()).isEqualTo(1);
    }
    UUID session(UUID account,String source)throws Exception{UUID id=UUID.randomUUID();try(var c=db.open();var q=c.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_sessions(tenant_id,id,account_id,source,external_session_id,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,now(),now())")){q.setString(1,tenant);q.setObject(2,id);q.setObject(3,account);q.setString(4,source);q.setString(5,id.toString());q.executeUpdate();}return id;}
    UUID question(UUID session,String time)throws Exception{UUID id=UUID.randomUUID();try(var c=db.open();var q=c.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_questions(tenant_id,id,session_id,question_identity,started_at,source) VALUES(?,?,?,?,?,'codex')")){q.setString(1,tenant);q.setObject(2,id);q.setObject(3,session);q.setString(4,id.toString());q.setTimestamp(5,Timestamp.from(Instant.parse(time)));q.executeUpdate();}return id;}
    UUID event(String time,long input,long output,Long cached)throws Exception{UUID id=UUID.randomUUID();try(var c=db.open();var q=c.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_events(tenant_id,id,session_id,account_id,source,event_kind,emitted_at,model,input_tokens,output_tokens,cache_read_tokens,scope,semantics,attribution,usage_availability,supported) VALUES(?,?,?,?,'codex','response.completed',?,'gpt-6.1-sol',?,?,?,'response','observation','unattributed','reported',true)")){q.setString(1,tenant);q.setObject(2,id);q.setObject(3,session);q.setObject(4,owner);q.setTimestamp(5,Timestamp.from(Instant.parse(time)));q.setLong(6,input);q.setLong(7,output);q.setObject(8,cached);q.executeUpdate();}return id;}
    static String path(UUID account){return "/admin/api/monitoring/accounts/"+account+"/costs";}
    static String scope(UUID session,UUID question){return "?month=2026-10&zone=Asia/Seoul&sessionId="+session+"&questionId="+question;}
    JsonNode get(UUID account,String query)throws Exception{return JSON.readTree(client.get().uri(path(account)+query).header("Test-Tenant",tenant).header("Test-Role","viewer").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody(byte[].class).returnResult().getResponseBody());}
    WebTestClient.ResponseSpec put(UUID account,String role,String body){return client.put().uri(path(account)+"/subscription").header("Test-Tenant",tenant).header("Test-Role",role).header("Content-Type","application/json").bodyValue(body).exchange();}
}
