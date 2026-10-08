package dev.rojae.llmgateway.analytics;

import java.util.UUID;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Creates an isolated database with the original unversioned V1 SQL. */
@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class LegacyMigrationTest {
    @Test void adoptionRequiresExplicitTenantAndKnownSchema() throws Exception {
        String base=System.getenv("GW_TEST_DB_URL");
        String prefix=base.substring(0,base.lastIndexOf('/')+1);
        String databaseName="llm_gateway_legacy_"+UUID.randomUUID().toString().replace("-","");
        String adminUrl=prefix+"postgres";
        try(var admin=DriverManager.getConnection(adminUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var create=admin.createStatement()){
            create.execute("CREATE DATABASE "+databaseName);
        }
        try{exerciseAdoption(prefix+databaseName);}
        finally{
            try(var admin=DriverManager.getConnection(adminUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var drop=admin.createStatement()){
                drop.execute("DROP DATABASE "+databaseName);
            }
        }
    }
    private void exerciseAdoption(String jdbcUrl) throws Exception {
        try(var connection=DriverManager.getConnection(jdbcUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var statement=connection.createStatement();var resource=getClass().getResourceAsStream("/db/analytics/V1__analytics.sql")){
            String sql=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
            for(String command:sql.split(";"))if(!command.isBlank())statement.execute(command);
        }
        AnalyticsProperties props=new AnalyticsProperties();
        props.setEnabled(true);props.setJdbcUrl(jdbcUrl);
        props.setUsername(System.getenv("GW_TEST_DB_USER"));props.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        props.setMaxPoolSize(2);
        var withoutMapping=new AnalyticsDatabase(props);
        assertThatThrownBy(withoutMapping::initialize).isInstanceOf(java.sql.SQLException.class);
        assertThat(withoutMapping.isInitialized()).isFalse();withoutMapping.close();
        String id=UUID.randomUUID().toString();
        var before=new AnalyticsDatabase(props);
        try{
            try(var connection=before.open();var query=connection.prepareStatement("INSERT INTO jev_analytics.requests (request_id,started_at,traffic,outcome,usage_availability,record) VALUES (?,now(),'test','success','missing','{}'::jsonb)")){
                query.setString(1,id);query.executeUpdate();
            }
        }finally{before.close();}
        props.setLegacyTenantId("legacy_org");
        try(var connection=DriverManager.getConnection(jdbcUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var drift=connection.createStatement()){
            drift.execute("ALTER TABLE jev_analytics.requests ADD COLUMN unknown_schema_marker TEXT");
        }
        var unknown=new AnalyticsDatabase(props);
        try{assertThatThrownBy(unknown::initialize).isInstanceOf(java.sql.SQLException.class);assertThat(unknown.isInitialized()).isFalse();}
        finally{unknown.close();}
        try(var connection=DriverManager.getConnection(jdbcUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var repair=connection.createStatement()){
            repair.execute("ALTER TABLE jev_analytics.requests DROP COLUMN unknown_schema_marker");
        }
        var adopted=new AnalyticsDatabase(props);
        try{
            adopted.initialize();assertThat(adopted.isInitialized()).isTrue();
            try(var connection=adopted.open();var query=connection.prepareStatement("SELECT tenant_id FROM llm_gateway_analytics.requests WHERE request_id=?")){
                query.setString(1,id);try(var result=query.executeQuery()){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("legacy_org");}
            }
        }finally{adopted.close();}
        try(var connection=DriverManager.getConnection(jdbcUrl,System.getenv("GW_TEST_DB_USER"),System.getenv("GW_TEST_DB_PASSWORD"));var corrupt=connection.createStatement()){
            assertThat(corrupt.executeUpdate("UPDATE llm_gateway_analytics.schema_history SET checksum=checksum+1 WHERE version='2'")).isEqualTo(1);
        }
        var drifted=new AnalyticsDatabase(props);
        try{assertThatThrownBy(drifted::initialize).isInstanceOf(java.sql.SQLException.class);assertThat(drifted.isInitialized()).isFalse();}
        finally{drifted.close();}
    }
}
