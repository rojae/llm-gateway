package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class AdminApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "admin-api-integration-separate-operator-token";
    private static final String SESSION = "admin-test-" + UUID.randomUUID();
    private static final String TENANT = "admin-api-" + UUID.randomUUID();
    private static final String ID = UUID.randomUUID().toString();
    private static final Path SOURCES;
    static { try { SOURCES=Files.createTempDirectory("llm-gateway-admin-api-"); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @LocalServerPort int port;
    @Autowired AnalyticsDatabase database;
    @Autowired AnalyticsProperties analyticsProperties;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("gateway.analytics.enabled",()->true);
        p.add("gateway.analytics.tenant-id",()->TENANT);
        p.add("gateway.analytics.jdbc-url",()->System.getenv("GW_TEST_DB_URL"));
        p.add("gateway.analytics.username",()->System.getenv("GW_TEST_DB_USER"));
        p.add("gateway.analytics.password",()->System.getenv("GW_TEST_DB_PASSWORD"));
        p.add("gateway.analytics.admin-token",()->TOKEN);
        p.add("gateway.analytics.access-log",()->SOURCES.resolve("access.log").toString());
        p.add("gateway.analytics.content-log",()->SOURCES.resolve("content.log").toString());
        p.add("gateway.analytics.poll-interval",()->"1h");
    }
    @BeforeAll void seed() throws Exception {
        database.initialize();
        try (Connection c=database.open()) {
            c.setAutoCommit(false);
            insert(c,ID,"2026-01-01T15:00:00Z","success","reported","0.100000000001",15L);
            insert(c,UUID.randomUUID().toString(),"2026-01-02T14:59:59Z","success","reported","0.200000000002",15L);
            insert(c,UUID.randomUUID().toString(),"2026-01-02T01:00:00Z","success","missing",null,null);
            insert(c,UUID.randomUUID().toString(),"2026-01-02T02:00:00Z","cancelled","partial","0.050000000000",35L);
            insert(c,UUID.randomUUID().toString(),"2026-01-02T03:00:00Z","success","reported",null,10L);
            insert(c,UUID.randomUUID().toString(),"2026-01-02T15:00:00Z","success","reported","999",999L);
            String telemetryId=UUID.randomUUID().toString();
            byte[] telemetry=telemetry(telemetryId,"1.250000000001","reported");
            AnalyticsRecord.insert(c,"access",telemetry,TENANT);
            AnalyticsRecord.insert(c,"access",telemetry,TENANT); // Collector retries must not inflate usage.
            AnalyticsRecord.insert(c,"access",telemetry(UUID.randomUUID().toString(),null,"partial"),TENANT);
            try(var s=c.prepareStatement("INSERT INTO llm_gateway_analytics.contents(tenant_id,request_id,phase,record) VALUES (?, ?, 'request',?::jsonb)")) {
                s.setString(1,TENANT);s.setString(2,ID); s.setString(3,"{\"text\":\"<script>alert(1)</script>\",\"phase\":\"request\"}"); s.executeUpdate();
            }
            c.commit();
        }
    }
    private void insert(Connection c,String id,String time,String outcome,String usage,String cost,Long tokens) throws Exception {
        try(var s=c.prepareStatement("INSERT INTO llm_gateway_analytics.requests(request_id,started_at,provider,model,key_id,session_id,traffic,outcome,status,usage_availability,prompt_tokens,completion_tokens,total_tokens,cost_usd,total_ms,record,tenant_id) VALUES (?,?::timestamptz,'anthropic','test-model','key-test',?,'test',?,200,?,?,0,?,?,10,?::jsonb,?)")) {
            s.setString(1,id);s.setString(2,time);s.setString(3,SESSION);s.setString(4,outcome);s.setString(5,usage);
            s.setObject(6,tokens);s.setObject(7,tokens);s.setBigDecimal(8,cost==null?null:new java.math.BigDecimal(cost));
            s.setString(9,"{\"requestId\":\""+id+"\",\"generation\":true,\"model\":\"test-model\"}");s.setString(10,TENANT);s.executeUpdate();
        }
    }
    private byte[] telemetry(String id,String equivalent,String usage) throws Exception {
        var node=JSON.createObjectNode();node.put("requestId",id);node.put("timestamp","2026-01-02T05:00:00Z");
        node.put("source","claude_code");node.put("billingMode","subscription_declared");
        node.put("provider","anthropic");node.put("model","test-model");node.put("sessionId",SESSION);
        node.put("traffic","test");node.put("outcome","success");node.put("status",200);
        node.put("usageAvailability",usage);node.put("promptTokens",7);node.put("completionTokens",3);node.put("totalTokens",10);
        if(equivalent!=null)node.put("apiEquivalentCostUsd",new java.math.BigDecimal(equivalent));
        return JSON.writeValueAsBytes(node);
    }
    private String filters(){return "?from=2026-01-02&to=2026-01-02&zone=Asia/Seoul&traffic=test&sessionId="+SESSION;}
    private ResponseEntity<String> get(String path,String token) {
        var request=WebClient.create("http://127.0.0.1:"+port).get().uri(path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        return request.exchangeToMono(r->r.toEntity(String.class)).block(Duration.ofSeconds(10));
    }
    @Test void httpAuthenticationAndNoStoreProtectActualServer() {
        for(String token:new String[]{null,"wrong"}) {
            var response=get("/admin/api/overview"+filters(),token);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).doesNotContain(TOKEN);
        }
        var response=get("/admin/api/session",TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"authenticated\":true");
    }
    @Test void decodedAndMatrixAdminPathsCannotBypassAuthentication() throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        for(String path:new String[]{"/admin/api;v=1/requests","/admin/%61pi/requests","/admin/api/unknown"}) {
            var response=client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:"+port+path)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        }
        assertThat(get("/admin/",null).getStatusCode().value()).isEqualTo(404);
        var config=get("/admin/auth/config",null);
        assertThat(config.getStatusCode().value()).isEqualTo(200);
        assertThat(config.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(config.getBody()).contains("\"mode\":\"local\"");
    }
    @Test void overviewUsesInclusiveSeoulDatesAndExactDecimalKnownSubtotal() throws Exception {
        var response=get("/admin/api/overview"+filters(),TOKEN);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode body=JSON.readTree(response.getBody());JsonNode total=body.path("totals");
        assertThat(total.path("requests").asInt()).isEqualTo(5);
        assertThat(total.path("costUsd").decimalValue()).isEqualByComparingTo("0.350000000003");
        assertThat(total.path("totalTokens").asLong()).isEqualTo(75);
        assertThat(total.path("missingUsageRequests").asInt()).isEqualTo(1);
        assertThat(total.path("partialUsageRequests").asInt()).isEqualTo(1);
        assertThat(total.path("unpricedRequests").asInt()).isEqualTo(1);
        assertThat(total.path("cancelled").asInt()).isEqualTo(1);
        assertThat(total.path("costComplete").asBoolean()).isFalse();
        assertThat(body.path("byDay").get(0).path("day").asText()).isEqualTo("2026-01-02");
    }
    @Test void requestPaginationExactFiltersAndDetailsRemainSeparated() throws Exception {
        JsonNode list=JSON.readTree(get("/admin/api/requests"+filters()+"&limit=2&offset=1",TOKEN).getBody());
        assertThat(list.path("total").asLong()).isEqualTo(5);
        assertThat(list.path("items")).hasSize(2);
        JsonNode detail=JSON.readTree(get("/admin/api/requests/"+ID,TOKEN).getBody());
        assertThat(detail.path("request").path("requestId").asText()).isEqualTo(ID);
        assertThat(detail.path("contents").path("request").path("text").asText()).contains("<script>");
        assertThat(detail.path("contents").path("response").isNull()).isTrue();
        assertThat(get("/admin/api/requests/"+UUID.randomUUID(),TOKEN).getStatusCode().value()).isEqualTo(404);
        var injection=get("/admin/api/requests"+filters()+"&model='OR'1'='1",TOKEN);
        assertThat(injection.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(injection.getBody()).path("total").asInt()).isZero();
        assertThat(get("/admin/api/requests"+filters()+"&limit=101",TOKEN).getStatusCode().value()).isEqualTo(400);
    }
    @Test void databaseFailureReturnsSafeUnavailableInsteadOfConnectionDetails() {
        String original=analyticsProperties.getJdbcUrl();
        try {
            analyticsProperties.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/nonexistent-secret-database");
            database.close();
            var response=get("/admin/api/overview"+filters(),TOKEN);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).contains("analytics_unavailable")
                    .doesNotContain("postgres", "nonexistent-secret-database", TOKEN, System.getenv("GW_TEST_DB_PASSWORD"));
        } finally { database.close();analyticsProperties.setJdbcUrl(original); }
    }

    @Test void contentAuditFailureBlocksResponseUntilAuditCanCommit() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String name="reject_content_audit_"+suffix;
        try(Connection c=database.open();var statement=c.createStatement()) {
            statement.execute("CREATE FUNCTION llm_gateway_analytics."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+TENANT+"' AND NEW.action='content.read' THEN RAISE EXCEPTION 'PRIVATE_AUDIT_FAILURE'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON llm_gateway_analytics.audit_events FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics."+name+"()");
        }
        try {
            var response=get("/admin/api/requests/"+ID,TOKEN);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).contains("analytics_unavailable").doesNotContain("<script>","alert(1)","PRIVATE_AUDIT_FAILURE",TOKEN);
        } finally {
            try(Connection c=database.open();var statement=c.createStatement()) {
                statement.execute("DROP TRIGGER "+name+" ON llm_gateway_analytics.audit_events");
                statement.execute("DROP FUNCTION llm_gateway_analytics."+name+"()");
            }
        }
        var recovered=get("/admin/api/requests/"+ID,TOKEN);
        assertThat(recovered.getStatusCode().value()).isEqualTo(200);
        assertThat(recovered.getBody()).contains("alert(1)");
        try(Connection c=database.open();var query=c.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.audit_events WHERE tenant_id=? AND action='content.read' AND target=? AND outcome='success'")) {
            query.setString(1,TENANT);query.setString(2,ID);
            try(var result=query.executeQuery()){result.next();assertThat(result.getLong(1)).isPositive();}
        }
    }

    @Test void telemetryCostsRemainSeparateAndSourceBillingFiltersAreExplicit() throws Exception {
        JsonNode all=JSON.readTree(get("/admin/api/overview"+filters()+"&source=all",TOKEN).getBody());
        var totals=all.path("totals");
        assertThat(totals.path("requests").asInt()).isEqualTo(7);
        assertThat(totals.path("costUsd").decimalValue()).isEqualByComparingTo("0.350000000003");
        assertThat(totals.path("apiEquivalentCostUsd").decimalValue()).isEqualByComparingTo("1.250000000001");
        assertThat(totals.path("unpricedRequests").asInt()).isEqualTo(1);
        assertThat(totals.path("missingEquivalentCostRequests").asInt()).isEqualTo(1);
        assertThat(totals.path("equivalentCostComplete").asBoolean()).isFalse();
        assertThat(all.path("bySource")).hasSize(2);
        JsonNode list=JSON.readTree(get("/admin/api/requests"+filters()+"&source=claude_code&billingMode=subscription_declared",TOKEN).getBody());
        assertThat(list.path("total").asInt()).isEqualTo(2);
        for(JsonNode row:list.path("items")) {
            assertThat(row.path("source").asText()).isEqualTo("claude_code");
            assertThat(row.path("billingMode").asText()).isEqualTo("subscription_declared");
            assertThat(row.path("costUsd").isNull()).isTrue();
        }
        JsonNode legacy=JSON.readTree(get("/admin/api/requests"+filters(),TOKEN).getBody()).path("items").get(0);
        assertThat(legacy.path("source").asText()).isEqualTo("gateway");
        assertThat(legacy.path("billingMode").asText()).isEqualTo("api_key");
    }

    @Test void telemetryCannotEnterApiCostColumn() throws Exception {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(telemetry(UUID.randomUUID().toString(),"1","reported"));
        node.put("costUsd",new java.math.BigDecimal("99.99"));
        try(Connection c=database.open()) {
            c.setAutoCommit(false);
            assertThatThrownBy(()->AnalyticsRecord.insert(c,"access",JSON.writeValueAsBytes(node),TENANT))
                    .isInstanceOf(AnalyticsRecord.Rejected.class);
        }
    }

    @Test void legacyJsonDefaultsToGatewayAndUndeclaredTelemetryBillingStaysUnknown() throws Exception {
        try(Connection c=database.open()) {
            c.setAutoCommit(false);
            try {
                var legacy=JSON.createObjectNode();legacy.put("requestId",UUID.randomUUID().toString());
                legacy.put("timestamp","2026-01-02T05:00:00Z");legacy.put("status",200);
                legacy.put("costUsd",new java.math.BigDecimal("0.012345678901"));
                AnalyticsRecord.insert(c,"access",JSON.writeValueAsBytes(legacy),TENANT);
                var telemetry=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(telemetry(UUID.randomUUID().toString(),"1","reported"));
                telemetry.remove("billingMode");AnalyticsRecord.insert(c,"access",JSON.writeValueAsBytes(telemetry),TENANT);
                try(var statement=c.prepareStatement("SELECT source,billing_mode,cost_usd FROM llm_gateway_analytics.requests WHERE request_id=? AND tenant_id=?")) {
                    statement.setString(2,TENANT);
                    statement.setString(1,legacy.path("requestId").asText());
                    try(var row=statement.executeQuery()) {
                        assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("gateway");
                        assertThat(row.getString(2)).isEqualTo("api_key");
                        assertThat(row.getBigDecimal(3)).isEqualByComparingTo("0.012345678901");
                    }
                    statement.setString(1,telemetry.path("requestId").asText());
                    try(var row=statement.executeQuery()) {
                        assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("claude_code");
                        assertThat(row.getString(2)).isEqualTo("unknown");assertThat(row.getBigDecimal(3)).isNull();
                    }
                }
            } finally { c.rollback(); }
        }
    }

    @AfterAll void cleanup() throws Exception {
        try(Connection c=database.open()) {
            try(var s=c.prepareStatement("DELETE FROM llm_gateway_analytics.contents WHERE request_id=? AND tenant_id=?")){s.setString(1,ID);s.setString(2,TENANT);s.executeUpdate();}
            try(var s=c.prepareStatement("DELETE FROM llm_gateway_analytics.requests WHERE session_id=? AND tenant_id=?")){s.setString(1,SESSION);s.setString(2,TENANT);s.executeUpdate();}
        }
        Files.deleteIfExists(SOURCES.resolve("access.log"));Files.deleteIfExists(SOURCES.resolve("content.log"));Files.deleteIfExists(SOURCES);
    }
}
