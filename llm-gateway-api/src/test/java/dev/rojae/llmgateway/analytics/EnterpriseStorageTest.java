package dev.rojae.llmgateway.analytics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class EnterpriseStorageTest {
    @TempDir Path dir;
    private final List<String> tenants=new ArrayList<>();
    private AnalyticsProperties properties;
    private AnalyticsDatabase database;

    @BeforeEach void setup() throws Exception {
        properties=new AnalyticsProperties();properties.setEnabled(true);
        properties.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));
        properties.setUsername(System.getenv("GW_TEST_DB_USER"));
        properties.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        properties.setAdminToken("operator-test-token-at-least-thirty-two-characters");
        properties.setMaxPoolSize(2);
        properties.setSourceId("storage-"+UUID.randomUUID());
        properties.setAccessLog(dir.resolve("access.log").toString());
        properties.setContentLog(dir.resolve("content.log").toString());
        database=new AnalyticsDatabase(properties);database.initialize();
    }

    @AfterEach void cleanup() throws Exception {
        try(var connection=database.open()) {
            for(String tenant:tenants)for(String table:List.of("contents","requests","content_identities","request_identities","checkpoints","ingestion_status")){
                try(var delete=connection.prepareStatement("DELETE FROM llm_gateway_analytics."+table+" WHERE tenant_id=?")){
                    delete.setString(1,tenant);delete.executeUpdate();
                }
            }
        }finally{database.close();}
    }

    @Test void sameRequestIdIsIndependentAndViewerDetailNeverLoadsOtherTenantsContent() throws Exception {
        String a=tenant(),b=tenant(),id=UUID.randomUUID().toString();
        insert(a,id,"alpha", "2026-09-25T01:02:03Z");
        insert(b,id,"bravo", "2026-09-25T01:02:03Z");
        var queries=new AnalyticsQueries(database);
        assertThat(queries.detail(id,a,true).get("contents").toString()).contains("alpha").doesNotContain("bravo");
        assertThat(queries.detail(id,b,true).get("contents").toString()).contains("bravo").doesNotContain("alpha");
        assertThat(queries.detail(id,a,false).get("contents")).isEqualTo(java.util.Map.of());
        var filters=new AdminFilters(LocalDate.of(2026,9,25),LocalDate.of(2026,9,25),ZoneId.of("UTC"),"all",null,null,null,null,null,"all",null,100,0);
        assertThat(((Number)((java.util.Map<?,?>)queries.overview(filters,a).get("totals")).get("requests")).longValue()).isEqualTo(1);
        assertThat(((Number)queries.requests(filters,a).get("total")).longValue()).isEqualTo(1);
        assertThat(((Number)queries.requests(filters,b).get("total")).longValue()).isEqualTo(1);
    }

    @Test void retentionDeletesPayloadInBoundedBatchAndReplayCannotResurrectIt() throws Exception {
        String tenant=tenant(),id=UUID.randomUUID().toString();properties.setTenantId(tenant);
        properties.setRequestRetentionDays(1);properties.setContentRetentionDays(1);
        insert(tenant,id,"old-body","2020-01-01T00:00:00Z");
        var worker=new JsonlIngestor(properties,database);worker.runOnce();
        assertThat(worker.status().get("status")).isEqualTo("ok");
        assertThat(count("requests",tenant,id)).isZero();
        assertThat(count("contents",tenant,id)).isZero();
        insert(tenant,id,"old-body","2020-01-01T00:00:00Z");
        assertThat(count("requests",tenant,id)).isZero();
        assertThat(count("contents",tenant,id)).isZero();
        assertThat(count("request_identities",tenant,id)).isEqualTo(1);
        assertThat(count("content_identities",tenant,id)).isEqualTo(1);
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            String late="{\"requestId\":\""+id+"\",\"phase\":\"request\",\"text\":\"late\"}";
            AnalyticsRecord.insert(connection,"content",late.getBytes(StandardCharsets.UTF_8),tenant);
            connection.commit();
        }
        assertThat(count("contents",tenant,id)).isZero();
    }

    @Test void sharedCollectorRetentionOnlyPrunesAllowedTenants() throws Exception {
        String a=tenant(),b=tenant(),unauthorized=tenant();
        String idA=UUID.randomUUID().toString(),idB=UUID.randomUUID().toString(),idC=UUID.randomUUID().toString();
        insert(a,idA,"a","2020-01-01T00:00:00Z");
        insert(b,idB,"b","2020-01-01T00:00:00Z");
        insert(unauthorized,idC,"c","2020-01-01T00:00:00Z");
        properties.setTenantId(a);properties.setRequestRetentionDays(1);
        var security=new dev.rojae.llmgateway.security.GatewaySecurityProperties();
        security.setMode("enterprise");security.setAllowedTenants(List.of(a,b));
        new JsonlIngestor(properties,database,security).runOnce();
        assertThat(count("requests",a,idA)).isZero();
        assertThat(count("requests",b,idB)).isZero();
        assertThat(count("requests",unauthorized,idC)).isEqualTo(1);
    }

    @Test void fileFailureHeartbeatIsVisibleOnlyToItsTenant() throws Exception {
        String a=tenant(),b=tenant();properties.setTenantId(a);
        Path blocked=dir.resolve("not-a-directory");Files.writeString(blocked,"x");
        properties.setAccessLog(blocked.resolve("access.log").toString());
        new JsonlIngestor(properties,database).runOnce();
        var queries=new AnalyticsQueries(database);
        assertThat(queries.ingestion(a).get("status")).isEqualTo("error");
        assertThat(queries.ingestion(a).get("lastError")).isEqualTo("source_unavailable");
        assertThat(queries.ingestion(b).get("status")).isEqualTo("unknown");
        assertThat(queries.ingestion(b).get("files").toString()).doesNotContain(a);
    }

    @Test void purgeWaitsForInFlightContentThenDeletesItWithFreshSnapshot() throws Exception {
        String tenant=tenant(),id=UUID.randomUUID().toString();
        insert(tenant,id,"earlier","2020-01-01T00:00:00Z");
        properties.setTenantId(tenant);properties.setRequestRetentionDays(1);
        var writer=database.open();writer.setAutoCommit(false);
        var workers=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            String late="{\"requestId\":\""+id+"\",\"phase\":\"request\",\"text\":\"in-flight\"}";
            AnalyticsRecord.insert(writer,"content",late.getBytes(StandardCharsets.UTF_8),tenant);
            var ingestor=new JsonlIngestor(properties,database);
            var pruning=workers.submit(ingestor::runOnce);
            Thread.sleep(250);
            assertThat(pruning.isDone()).isFalse();
            writer.commit();
            pruning.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ingestor.status().get("status")).isEqualTo("ok");
            assertThat(count("requests",tenant,id)).isZero();
            assertThat(count("contents",tenant,id)).isZero();
        }finally{writer.close();workers.shutdownNow();}
    }

    private String tenant(){String value="storage_"+UUID.randomUUID().toString().replace("-","");tenants.add(value);return value;}
    private void insert(String tenant,String id,String body,String timestamp) throws Exception {
        String access="{\"requestId\":\""+id+"\",\"timestamp\":\""+timestamp+"\",\"traffic\":\"test\",\"status\":200,\"outcome\":\"success\",\"usageAvailability\":\"missing\"}";
        String content="{\"requestId\":\""+id+"\",\"phase\":\"response\",\"text\":\""+body+"\"}";
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            AnalyticsRecord.insert(connection,"access",access.getBytes(StandardCharsets.UTF_8),tenant);
            AnalyticsRecord.insert(connection,"content",content.getBytes(StandardCharsets.UTF_8),tenant);
            connection.commit();
        }
    }
    private long count(String table,String tenant,String id) throws Exception {
        try(var connection=database.open();var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics."+table+" WHERE tenant_id=? AND request_id=?")){
            query.setString(1,tenant);query.setString(2,id);
            try(var result=query.executeQuery()){result.next();return result.getLong(1);}
        }
    }
}
