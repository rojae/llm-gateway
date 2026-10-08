package dev.rojae.llmgateway.analytics;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class CollectorCredentialServiceTest {
    AnalyticsDatabase database;CollectorCredentialService service;AuditService audit;
    String tenantA="collector-a-"+UUID.randomUUID(),tenantB="collector-b-"+UUID.randomUUID();
    @BeforeEach void setup()throws Exception{
        var properties=new AnalyticsProperties();properties.setEnabled(true);properties.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));properties.setUsername(System.getenv("GW_TEST_DB_USER"));properties.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));properties.setAdminToken("test-only-operator-token-thirty-two-characters");
        database=new AnalyticsDatabase(properties);database.initialize();audit=new AuditService(database);service=new CollectorCredentialService(database,audit);
    }
    @AfterEach void closeDatabase(){if(database!=null)database.close();}
    @Test void issuedSecretIsReturnedOnceAndBindsTenantAndBillingMode()throws Exception{
        var created=service.create(tenantA,"admin-a","build agents",30,"subscription_declared");
        assertThat(created).isNotNull();
        assertThat(created.token()).startsWith("llmgw_col_");
        var identity=service.authenticate(created.token()).orElseThrow();
        assertThat(identity.tenantId()).isEqualTo(tenantA);assertThat(identity.billingMode()).isEqualTo("subscription_declared");
        assertThat(service.list(tenantB)).isEmpty();assertThat(service.list(tenantA)).hasSize(1);
        assertThat(service.list(tenantA).toString()).doesNotContain(created.token());
        try(var c=database.open();var q=c.prepareStatement("SELECT token_hash,prefix FROM llm_gateway_analytics.collector_credentials WHERE id=?")){
            q.setObject(1,created.credential().id());try(var r=q.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getString(1)).hasSize(64).isNotEqualTo(created.token());assertThat(created.token()).startsWith(r.getString(2));}
        }
        assertThat(audit.list(tenantA,25,0).items()).anySatisfy(row->{assertThat(row.action()).isEqualTo("collector.create");assertThat(row.target()).isEqualTo(created.credential().id().toString());});
        assertThat(audit.list(tenantB,25,0).items()).isEmpty();
    }
    @Test void revokeIsTenantScopedAndEffectiveOnNextAuthentication()throws Exception{
        var created=service.create(tenantA,"admin-a","agent",30,"unknown");
        service.revoke(tenantB,"admin-b",created.credential().id());assertThat(service.authenticate(created.token())).isPresent();
        service.revoke(tenantA,"admin-a",created.credential().id());assertThat(service.authenticate(created.token())).isEmpty();
        service.revoke(tenantA,"admin-a",created.credential().id());
        assertThat(service.list(tenantA).getFirst().revokedAt()).isNotNull();
    }
    @Test void credentialsIssuedBeforeRenameRemainUsable()throws Exception{
        var created=service.create(tenantA,"admin-a","legacy agent",30,"unknown");
        String oldToken=created.token().replaceFirst("^llmgw_col_","jev_col_");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldToken.getBytes(StandardCharsets.UTF_8)));
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET token_hash=?,prefix=? WHERE id=?")){
            q.setString(1,hash);q.setString(2,oldToken.substring(0,16));q.setObject(3,created.credential().id());q.executeUpdate();
        }
        assertThat(service.authenticate(oldToken)).isPresent();
    }
    @Test void expiryUsesDatabaseTimeAndRejectsCredentialsWithoutReadingPayload()throws Exception{
        var created=service.create(tenantA,"admin-a","expired",1,"api_key_declared");
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET expires_at=now()-interval '1 second' WHERE id=?")){q.setObject(1,created.credential().id());q.executeUpdate();}
        assertThat(service.authenticate(created.token())).isEmpty();
    }
    @Test void failedAuditRollsBackKeyCreationAndRevocation()throws Exception{
        var created=service.create(tenantA,"admin-a","existing",30,"unknown");
        try(var c=database.open();var q=c.createStatement()){
            q.execute("CREATE OR REPLACE FUNCTION llm_gateway_analytics.reject_collector_audit_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenantA+"' THEN RAISE EXCEPTION 'PRIVATE_AUDIT_FAILURE'; END IF; RETURN NEW; END $$");
            q.execute("CREATE TRIGGER reject_collector_audit_test BEFORE INSERT ON llm_gateway_analytics.audit_events FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics.reject_collector_audit_test()");
        }
        try{
            assertThatThrownBy(()->service.create(tenantA,"admin-a","must rollback",30,"unknown")).isInstanceOf(SQLException.class);
            assertThat(service.list(tenantA)).hasSize(1);
            assertThatThrownBy(()->service.revoke(tenantA,"admin-a",created.credential().id())).isInstanceOf(SQLException.class);
            assertThat(service.authenticate(created.token())).isPresent();
        }finally{try(var c=database.open();var q=c.createStatement()){q.execute("DROP TRIGGER reject_collector_audit_test ON llm_gateway_analytics.audit_events");q.execute("DROP FUNCTION llm_gateway_analytics.reject_collector_audit_test()");}}
    }
}
