package dev.rojae.llmgateway.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL", matches=".+")
class JsonlIngestorTest {
    @TempDir Path dir;
    AnalyticsProperties props;
    AnalyticsDatabase db;
    @BeforeEach void setup() {
        props = new AnalyticsProperties();
        props.setEnabled(true);
        props.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));
        props.setUsername(System.getenv("GW_TEST_DB_USER"));
        props.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        props.setAdminToken("test-admin-token-at-least-thirty-two-characters");
        props.setSourceId("ingest-test-" + UUID.randomUUID());
        props.setAccessLog(dir.resolve("access.log").toString());
        props.setContentLog(dir.resolve("content.log").toString());
        db = new AnalyticsDatabase(props);
    }
    @Test void initializesRequiredSchemaRepeatably() throws Exception {
        db.initialize(); db.initialize();
        try (var c = db.open(); var q = c.createStatement(); var r = q.executeQuery("SELECT to_regclass('llm_gateway_analytics.requests') IS NOT NULL AND to_regclass('llm_gateway_analytics.contents') IS NOT NULL AND to_regclass('llm_gateway_analytics.checkpoints') IS NOT NULL")) {
            r.next(); assertThat(r.getBoolean(1)).isTrue();
        }
    }
    @Test void restartDeduplicatesRequestsAndContentAndWaitsForNewline() throws Exception {
        db.initialize();
        String id = UUID.randomUUID().toString();
        String line = access(id);
        Files.writeString(Path.of(props.getAccessLog()), line + "\n" + line);
        Files.writeString(Path.of(props.getContentLog()), "{\"requestId\":\""+id+"\",\"phase\":\"response\",\"text\":\"hello\",\"partial\":true,\"truncated\":false}\n");
        var ingestor = new JsonlIngestor(props, db); ingestor.runOnce();
        assertThat(count("requests", id)).isEqualTo(1);
        assertThat(count("contents", id)).isEqualTo(1);
        assertThat(offset("access")).isEqualTo(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
        Files.writeString(Path.of(props.getAccessLog()), "\n", java.nio.file.StandardOpenOption.APPEND);
        new JsonlIngestor(props, db).runOnce();
        assertThat(count("requests", id)).isEqualTo(1);
        assertThat(offset("access")).isEqualTo(Files.size(Path.of(props.getAccessLog())));
        try (var c=db.open(); var q=c.prepareStatement("SELECT total_tokens,cost_usd,record->>'usageAvailability' FROM llm_gateway_analytics.requests WHERE request_id=?")) {
            q.setString(1,id); try(var r=q.executeQuery()) { r.next(); assertThat(r.getObject(1)).isNull(); assertThat(r.getObject(2)).isNull(); assertThat(r.getString(3)).isEqualTo("missing"); }
        }
    }
    @Test void ingestsGzipAndRejectsMalformedOversizedLinesWithoutRawDiagnostics() throws Exception {
        db.initialize(); props.setMaxLineBytes(1024);
        String id=UUID.randomUUID().toString();
        try(var out=new GZIPOutputStream(Files.newOutputStream(dir.resolve("access.log.2026-09-25.0.gz")))) {
            out.write(("PRIVATE_SECRET malformed\n"+"X".repeat(2048)+"\n"+access(id)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var ingest=new JsonlIngestor(props,db); ingest.runOnce(); ingest.runOnce();
        assertThat(count("requests",id)).isEqualTo(1);
        assertThat(((Number)ingest.status().get("rejectedLines")).longValue()).isEqualTo(2);
        assertThat(ingest.status().toString()).doesNotContain("PRIVATE_SECRET");
    }
    @Test void databaseFailureDoesNotAdvanceCheckpointAndRecoveryReplaysBatch() throws Exception {
        db.initialize(); String id=UUID.randomUUID().toString();
        Files.writeString(Path.of(props.getAccessLog()),access(id)+"\n");
        try(var c=db.open();var q=c.createStatement()) {
            q.execute("CREATE OR REPLACE FUNCTION llm_gateway_analytics.reject_test_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_id = '"+props.getSourceId()+"' THEN RAISE EXCEPTION 'PRIVATE_FAILURE'; END IF; RETURN NEW; END $$");
            q.execute("CREATE TRIGGER reject_test_checkpoint BEFORE INSERT OR UPDATE ON llm_gateway_analytics.checkpoints FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics.reject_test_checkpoint()");
        }
        var ingest=new JsonlIngestor(props,db);
        try { ingest.runOnce(); assertThat(count("requests",id)).isZero(); assertThat(ingest.status().get("status")).isEqualTo("error"); assertThat(ingest.status().toString()).doesNotContain("PRIVATE_FAILURE"); }
        finally {try(var c=db.open();var q=c.createStatement()){q.execute("DROP TRIGGER reject_test_checkpoint ON llm_gateway_analytics.checkpoints");q.execute("DROP FUNCTION llm_gateway_analytics.reject_test_checkpoint()");}}
        ingest.runOnce(); assertThat(count("requests",id)).isEqualTo(1); assertThat(ingest.status().get("status")).isEqualTo("ok");
    }

    @Test void perSourceAdvisoryLockPreventsConcurrentCheckpointWriters() throws Exception {
        db.initialize();String id=UUID.randomUUID().toString();
        Files.writeString(Path.of(props.getAccessLog()),access(id)+"\n");
        try(var lock=db.open();var query=lock.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            lock.setAutoCommit(false);query.setString(1,"llm-gateway-analytics:"+props.getTenantId()+":"+props.getSourceId());query.execute();
            new JsonlIngestor(props,db).runOnce();assertThat(count("requests",id)).isZero();lock.rollback();
        }
        new JsonlIngestor(props,db).runOnce();assertThat(count("requests",id)).isEqualTo(1);
    }
    @Test void startupReturnsImmediatelyDuringOutageAndRestartWithCorrectDatabaseRecovers() throws Exception {
        String url=props.getJdbcUrl();props.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/unavailable");props.setPollInterval(Duration.ofMillis(100));
        var ingest=new JsonlIngestor(props,db);
        long start=System.nanoTime();ingest.start();
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(!"error".equals(ingest.status().get("status")) && System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(ingest.status().get("lastError")).isEqualTo("database_unavailable");
        } finally {ingest.stop();db.close();}
        props.setJdbcUrl(url);
        var recoveredDb=new AnalyticsDatabase(props);
        try{var recovered=new JsonlIngestor(props,recoveredDb);recovered.runOnce();assertThat(recovered.status().get("status")).isEqualTo("ok");}
        finally{recoveredDb.close();}
    }
    @Test void truncatedAndReplacedFilesReplayWithoutLosingNewRecords() throws Exception {
        db.initialize();String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        Path file=Path.of(props.getAccessLog());Files.writeString(file,access(first)+"\n");
        var ingest=new JsonlIngestor(props,db);ingest.runOnce();
        // A copy-truncate followed by rapid growth can exceed the old checkpoint before the next poll.
        Files.writeString(file,access(second)+"\n"+" ".repeat(100)+"\n");
        ingest.runOnce();assertThat(count("requests",second)).isEqualTo(1);
    }
    @Test void boundedBatchAndStrictTypedRejectionsPreserveDecimalAmounts() throws Exception {
        db.initialize();props.setBatchSize(2);
        String id=UUID.randomUUID().toString();
        String priced=access(id).replace("\"usageAvailability\":\"missing\"", "\"usageAvailability\":\"reported\",\"promptTokens\":10,\"completionTokens\":2,\"totalTokens\":12,\"costUsd\":0.000001234567");
        Files.writeString(Path.of(props.getAccessLog()),"{\"requestId\":\"not-a-uuid\"}\n"+access(UUID.randomUUID().toString()).replace("200", "\"BAD_PRIVATE_VALUE\"")+"\n"+priced+"\n");
        var ingest=new JsonlIngestor(props,db);ingest.runOnce();assertThat(count("requests",id)).isZero();
        ingest.runOnce();assertThat(count("requests",id)).isEqualTo(1);
        assertThat(((Number)ingest.status().get("rejectedLines")).longValue()).isEqualTo(2);
        assertThat(ingest.status().toString()).doesNotContain("BAD_PRIVATE_VALUE");
        try(var c=db.open();var q=c.prepareStatement("SELECT cost_usd FROM llm_gateway_analytics.requests WHERE request_id=?")){q.setString(1,id);try(var r=q.executeQuery()){r.next();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("0.000001234567");}}
    }

    @Test void postgresIncompatibleRecordDoesNotPoisonFollowingValidLines() throws Exception {
        db.initialize();String before=UUID.randomUUID().toString(),bad=UUID.randomUUID().toString(),after=UUID.randomUUID().toString();
        String invalid=access(bad).replace("}",",\"unknown\":\"\\uD800\"}");
        Files.writeString(Path.of(props.getAccessLog()),access(before)+"\n"+invalid+"\n"+access(after)+"\n");
        var ingest=new JsonlIngestor(props,db);ingest.runOnce();
        assertThat(count("requests",before)).isEqualTo(1);assertThat(count("requests",after)).isEqualTo(1);
        assertThat(count("requests",bad)).isZero();assertThat(((Number)ingest.status().get("rejectedLines")).longValue()).isEqualTo(1);
    }

    @Test void completedGzipIsNotReprocessedAfterRestartButReplacementIsRead() throws Exception {
        db.initialize();String id=UUID.randomUUID().toString();Path archive=dir.resolve("access.log.2026-09-25.1.gz");
        try(var out=new GZIPOutputStream(Files.newOutputStream(archive))){out.write((access(id)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        var ingest=new JsonlIngestor(props,db);ingest.runOnce();
        String updated;
        try(var c=db.open();var q=c.prepareStatement("SELECT updated_at::text FROM llm_gateway_analytics.checkpoints WHERE source_id=?")){q.setString(1,props.getSourceId());try(var r=q.executeQuery()){r.next();updated=r.getString(1);}}
        new JsonlIngestor(props,db).runOnce();
        try(var c=db.open();var q=c.prepareStatement("SELECT updated_at::text FROM llm_gateway_analytics.checkpoints WHERE source_id=?")){q.setString(1,props.getSourceId());try(var r=q.executeQuery()){r.next();assertThat(r.getString(1)).isEqualTo(updated);}}
        String second=UUID.randomUUID().toString();
        try(var out=new GZIPOutputStream(Files.newOutputStream(archive))){out.write((access(second)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ingest.runOnce();assertThat(count("requests",second)).isEqualTo(1);
    }

    @Test void postgresNumericOverflowRejectsOnlyBadRecordAndCommitsFollowingRows() throws Exception {
        db.initialize();String before=UUID.randomUUID().toString(),bad=UUID.randomUUID().toString(),after=UUID.randomUUID().toString();
        // This is below 1e12 before rounding but exceeds NUMERIC(24,12) after rounding.
        String invalid=access(bad).replace("}",",\"costUsd\":999999999999.9999999999999}");
        Files.writeString(Path.of(props.getAccessLog()),access(before)+"\n"+invalid+"\n"+access(after)+"\n");
        var ingest=new JsonlIngestor(props,db);ingest.runOnce();
        assertThat(count("requests",before)).isEqualTo(1);assertThat(count("requests",after)).isEqualTo(1);
        assertThat(count("requests",bad)).isZero();assertThat(((Number)ingest.status().get("rejectedLines")).longValue()).isEqualTo(1);
        assertThat(ingest.status().get("status")).isEqualTo("ok");
    }
    private long count(String table,String id) throws Exception {
        try(var c=db.open();var q=c.prepareStatement("SELECT count(*) FROM llm_gateway_analytics."+table+" WHERE request_id=?")){q.setString(1,id);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
    }
    private long offset(String kind) throws Exception {
        try(var c=db.open();var q=c.prepareStatement("SELECT sum(offset_bytes) FROM llm_gateway_analytics.checkpoints WHERE source_id=? AND kind=?")){q.setString(1,props.getSourceId());q.setString(2,kind);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
    }
    private String access(String id) {return "{\"requestId\":\""+id+"\",\"timestamp\":\"2026-09-25T01:02:03Z\",\"traffic\":\"test\",\"provider\":\"openai\",\"status\":200,\"outcome\":\"success\",\"usageAvailability\":\"missing\"}";}
}
