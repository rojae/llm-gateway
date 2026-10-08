package dev.rojae.llmgateway.analytics;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class MonitoringStorageTest {
    @TempDir Path dir;
    private AnalyticsProperties properties;
    private AnalyticsDatabase database;
    private CollectorCredentialService credentials;
    private MonitoringQueries queries;
    private String tenant;
    @BeforeEach void setup()throws Exception{
        tenant="monitor-store-"+UUID.randomUUID();properties=new AnalyticsProperties();properties.setEnabled(true);properties.setTenantId(tenant);
        properties.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));properties.setUsername(System.getenv("GW_TEST_DB_USER"));properties.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        properties.setAccessLog(dir.resolve("access.log").toString());properties.setContentLog(dir.resolve("content.log").toString());
        database=new AnalyticsDatabase(properties);database.initialize();var audit=new AuditService(database);queries=new MonitoringQueries(database,audit);credentials=new CollectorCredentialService(database,audit);
    }
    @AfterEach void close(){database.close();}
    @Test void capturedCredentialCannotAuthorizeAfterOwnerOrRegistrationChanges()throws Exception{
        UUID account=(UUID)queries.createAccount(tenant,"admin","owner").get("id");var created=credentials.create(tenant,"admin","key",30,"subscription_declared",account,null);
        var identity=credentials.authenticate(created.token()).orElseThrow();assertThat(identity.ownerAccountId()).isEqualTo(account);
        UUID alternate=UUID.randomUUID();try(var connection=database.open()){
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_registrations(tenant_id,id,account_id) VALUES(?,?,?)")){query.setString(1,tenant);query.setObject(2,alternate);query.setObject(3,account);query.executeUpdate();}
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET registration_id=? WHERE tenant_id=? AND id=?")){query.setObject(1,alternate);query.setString(2,tenant);query.setObject(3,identity.credentialId());query.executeUpdate();}connection.commit();
            assertThat(credentials.stillAuthorized(connection,identity)).isFalse();
        }
        UUID other=(UUID)queries.createAccount(tenant,"admin","other").get("id");
        assertThatThrownBy(()->credentials.create(tenant,"admin","bad-rotation",30,"subscription_declared",other,alternate)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void corruptFileCannotBlockDatabaseReadinessRetentionOrReplayMarkers()throws Exception{
        UUID account=(UUID)queries.createAccount(tenant,"admin","owner").get("id");var created=credentials.create(tenant,"admin","key",30,"subscription_declared",account,null);var identity=credentials.authenticate(created.token()).orElseThrow();
        var log=TelemetryNormalizerTest.record("user_prompt");TelemetryNormalizerTest.attr(log,"prompt.id","stringValue","prompt-retention");TelemetryNormalizerTest.attr(log,"prompt_text","stringValue","safe test body");
        var event=new MonitoringNormalizer(new TelemetryProperties()).normalize(TelemetryNormalizerTest.envelope(log)).events().getFirst();
        try(var connection=database.open()){
            connection.setAutoCommit(false);assertThat(MonitoringStore.insert(connection,identity,event)).isTrue();connection.commit();
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET last_seen_at='2020-01-01T00:00:00Z' WHERE tenant_id=?")){query.setString(1,tenant);query.executeUpdate();}connection.commit();
        }
        properties.setRequestRetentionDays(1);properties.setContentRetentionDays(1);Files.writeString(dir.resolve("access.log.1.gz"),"not gzip");
        var worker=new JsonlIngestor(properties,database);worker.runOnce();assertThat(database.isInitialized()).isTrue();assertThat(worker.status().get("lastError")).isEqualTo("source_unavailable");
        try(var connection=database.open()){
            for(String table:List.of("monitoring_events","monitoring_question_contents"))try(var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics."+table+" WHERE tenant_id=?")){query.setString(1,tenant);try(var result=query.executeQuery()){result.next();assertThat(result.getLong(1)).isZero();}}
            connection.setAutoCommit(false);assertThat(MonitoringStore.insert(connection,identity,event)).isFalse();connection.commit();
            try(var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.monitoring_event_identities WHERE tenant_id=?")){query.setString(1,tenant);try(var result=query.executeQuery()){result.next();assertThat(result.getLong(1)).isEqualTo(1);}}
        }
    }
}
