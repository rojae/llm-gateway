package dev.rojae.llmgateway.analytics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A bounded JDBC pool; callers use dedicated analytics workers, never the Netty event loop. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public class AnalyticsDatabase {
    private static final String LEGACY_SCHEMA="jev_analytics";
    private static final String SCHEMA="llm_gateway_analytics";
    // Keep the lock identity shared with older app versions during rolling upgrades.
    private static final String MIGRATION_LOCK="jev_analytics_migrations";
    private final AnalyticsProperties properties;
    private volatile HikariDataSource pool;
    private volatile boolean initialized;

    public AnalyticsDatabase(AnalyticsProperties properties) { this.properties=properties; }
    public boolean isInitialized(){return initialized;}

    public Connection open() throws SQLException {
        Connection connection=dataSource().getConnection();
        try (var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("SET statement_timeout = '5s'");
            statement.execute("SET lock_timeout = '2s'");
            return connection;
        } catch(SQLException error){connection.close();throw error;}
    }

    private synchronized HikariDataSource dataSource() {
        if(pool!=null)return pool;
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(properties.getConnectionTimeout().toMillis());
        config.setInitializationFailTimeout(-1);
        config.setPoolName("llm-gateway-analytics");
        config.addDataSourceProperty("connectTimeout","3");
        config.addDataSourceProperty("socketTimeout","10");
        config.addDataSourceProperty("ApplicationName","llm-gateway-analytics");
        pool=new HikariDataSource(config);
        return pool;
    }

    /** Migrations run off the request path and are serialized across replicas. */
    public synchronized void initialize() throws SQLException {
        if(initialized)return;
        try(Connection lock=migrationLockConnection();var statement=lock.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute("SELECT pg_advisory_lock(hashtextextended('"+MIGRATION_LOCK+"',0))");
            try {
                boolean oldSchema=schemaExists(lock,LEGACY_SCHEMA);
                boolean newSchema=schemaExists(lock,SCHEMA);
                if(oldSchema&&newSchema)throw new SQLException("analytics_schema_rename_conflict");
                String migrationSchema=newSchema?SCHEMA:LEGACY_SCHEMA;
                boolean legacy=legacyUnversioned(lock,migrationSchema);
                if(newSchema&&legacy)throw new SQLException("analytics_unversioned_renamed_schema");
                if(newSchema&&!exists(lock,SCHEMA+".schema_history"))
                    throw new SQLException("analytics_renamed_schema_without_history");
                Flyway flyway=Flyway.configure()
                        .dataSource(dataSource())
                        .locations("classpath:db/migration")
                        .schemas(migrationSchema)
                        .defaultSchema(migrationSchema)
                        .table("schema_history")
                        .placeholders(java.util.Map.of("legacyTenantId",legacy?properties.getLegacyTenantId():properties.getTenantId()))
                        .load();
                if(legacy)flyway.baseline();
                flyway.migrate();
                flyway.validate();
                if(!newSchema)statement.execute("ALTER SCHEMA "+LEGACY_SCHEMA+" RENAME TO "+SCHEMA);
                initialized=true;
            } catch(Exception failure){
                initialized=false;
                throw new SQLException("analytics_migration_failed",failure);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(hashtextextended('"+MIGRATION_LOCK+"',0))");
            }
        }
    }

    private Connection migrationLockConnection() throws SQLException {
        Properties options=new Properties();
        if(properties.getUsername()!=null)options.setProperty("user",properties.getUsername());
        if(properties.getPassword()!=null)options.setProperty("password",properties.getPassword());
        options.setProperty("connectTimeout","3");options.setProperty("socketTimeout","30");
        options.setProperty("ApplicationName","llm-gateway-analytics-migration");
        return DriverManager.getConnection(properties.getJdbcUrl(),options);
    }

    private boolean legacyUnversioned(Connection connection,String schema) throws SQLException {
        boolean requests=exists(connection,schema+".requests");
        boolean contents=exists(connection,schema+".contents");
        boolean checkpoints=exists(connection,schema+".checkpoints");
        boolean history=exists(connection,schema+".schema_history");
        if(history)return false;
        if(!requests&&!contents&&!checkpoints)return false;
        if(!requests||!contents||!checkpoints||properties.getLegacyTenantId()==null)
            throw new SQLException("analytics_legacy_baseline_requires_explicit_tenant");
        requireColumns(connection,schema,"requests",Set.of("request_id","started_at","provider","model","key_id","session_id","traffic","outcome","status","usage_availability","prompt_tokens","completion_tokens","cache_write_tokens","cache_write_1h_tokens","cache_read_tokens","total_tokens","cost_usd","total_ms","first_byte_ms","record","ingested_at","source","billing_mode","api_equivalent_cost_usd"));
        requireColumns(connection,schema,"contents",Set.of("request_id","phase","record","ingested_at"));
        requireColumns(connection,schema,"checkpoints",Set.of("source_id","kind","file_id","file_name","offset_bytes","rejected_lines","rejection_counts","updated_at","anchor_hash","observed_version","completed"));
        return true;
    }
    private static boolean schemaExists(Connection connection,String schema) throws SQLException {
        try(var query=connection.prepareStatement("SELECT to_regnamespace(?) IS NOT NULL")){
            query.setString(1,schema);try(var result=query.executeQuery()){result.next();return result.getBoolean(1);}
        }
    }
    private static boolean exists(Connection connection,String name) throws SQLException {
        try(var query=connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")){
            query.setString(1,name);try(var result=query.executeQuery()){result.next();return result.getBoolean(1);}
        }
    }
    private static void requireColumns(Connection connection,String schema,String table,Set<String> expected) throws SQLException {
        Set<String> found=new HashSet<>();
        try(var query=connection.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=?")){
            query.setString(1,schema);query.setString(2,table);try(var result=query.executeQuery()){while(result.next())found.add(result.getString(1));}
        }
        if(!found.equals(expected))throw new SQLException("analytics_unknown_legacy_schema:"+table);
    }

    @PreDestroy public synchronized void close(){initialized=false;if(pool!=null){pool.close();pool=null;}}
}
