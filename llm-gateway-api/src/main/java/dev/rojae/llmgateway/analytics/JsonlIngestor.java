package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import dev.rojae.llmgateway.security.GatewaySecurityProperties;

/** Resumable, best-effort analytics. No database or file errors escape into provider proxy requests. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public class JsonlIngestor {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_FILES=512;
    private final AnalyticsProperties properties;
    private final AnalyticsDatabase database;
    private final GatewaySecurityProperties security;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"analytics-ingestion");thread.setDaemon(true);return thread;});
    private boolean initialized;
    private volatile boolean running;
    private volatile String state="starting",lastError,lastSuccessAt;
    private volatile List<Map<String,Object>> files=List.of();
    private volatile long rejectedLines;
    private volatile Map<String,Long> rejectionCounts=Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public JsonlIngestor(AnalyticsProperties properties,AnalyticsDatabase database,GatewaySecurityProperties security){this.properties=properties;this.database=database;this.security=security;}
    public JsonlIngestor(AnalyticsProperties properties,AnalyticsDatabase database){this(properties,database,new GatewaySecurityProperties());}
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(this::runOnce,0,properties.getPollInterval().toMillis(),TimeUnit.MILLISECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}

    public synchronized void runOnce() {
        if(!properties.isEnabled())return;
        running=true;
        try {
            if(!initialized){database.initialize();initialized=true;}
            pruneExpired();
            ingestSources("access",properties.getAccessLog());
            ingestSources("content",properties.getContentLog());
            refreshStatus();
            lastSuccessAt=Instant.now().toString();lastError=null;state="ok";
        } catch(SQLException error){lastError="database_unavailable";state="error";}
        catch(IOException error){lastError="source_unavailable";state="error";}
        catch(Exception error){lastError="ingestion_failed";state="error";}
        finally {running=false;recordHeartbeat();}
    }

    public Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",properties.isEnabled());result.put("running",running);result.put("status",state);
        result.put("lastSuccessAt",lastSuccessAt);result.put("lastError",lastError);result.put("files",files);
        result.put("rejectedLines",rejectedLines);result.put("rejectionCounts",rejectionCounts);return result;
    }

    private void recordHeartbeat() {
        if(!database.isInitialized())return;
        try(var connection=database.open();var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.ingestion_status (tenant_id,source_id,state,last_error,last_success_at,poll_interval_ms,rejected_lines,rejection_counts) VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (tenant_id,source_id) DO UPDATE SET state=EXCLUDED.state,last_error=EXCLUDED.last_error,last_success_at=EXCLUDED.last_success_at,observed_at=now(),poll_interval_ms=EXCLUDED.poll_interval_ms,rejected_lines=EXCLUDED.rejected_lines,rejection_counts=EXCLUDED.rejection_counts")){
            query.setString(1,properties.getTenantId());query.setString(2,properties.getSourceId());
            query.setString(3,state);query.setString(4,lastError);
            if(lastSuccessAt==null)query.setTimestamp(5,null);else query.setTimestamp(5,java.sql.Timestamp.from(Instant.parse(lastSuccessAt)));
            query.setLong(6,properties.getPollInterval().toMillis());query.setLong(7,rejectedLines);
            query.setString(8,JSON.writeValueAsString(rejectionCounts));query.executeUpdate();
        }catch(Exception ignored){/* No analytics maintenance failure reaches provider proxy requests. */}
    }

    private void ingestSources(String kind,String configured) throws IOException,SQLException {
        Path active=Path.of(configured).toAbsolutePath().normalize();Path directory=active.getParent();
        if(!Files.exists(directory))return;
        List<Path> candidates=new ArrayList<>();String base=active.getFileName().toString();
        try(var entries=Files.newDirectoryStream(directory)) {
            for(Path entry:entries){String name=entry.getFileName().toString();
                if((name.equals(base)||name.startsWith(base+"."))&&Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) {
                    if(candidates.size()>=MAX_FILES)throw new IOException("source_file_limit");
                    candidates.add(entry);
                }
            }
        }
        candidates.sort(Comparator.comparing((Path path)->path.equals(active)).thenComparing(path->path.getFileName().toString()));
        for(Path path:candidates){if(Thread.currentThread().isInterrupted())return;ingestFile(kind,path);}
    }

    private void ingestFile(String kind,Path path) throws IOException,SQLException {
        BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        String identity=digest(path.toString()+"|"+attributes.fileKey()+"|"+attributes.creationTime());
        try(Connection connection=database.open()) {
            connection.setAutoCommit(false);
            try {
                try(var query=connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
                    query.setString(1,"llm-gateway-analytics:"+properties.getTenantId()+":"+properties.getSourceId());
                    try(var result=query.executeQuery()){result.next();if(!result.getBoolean(1)){connection.rollback();return;}}
                }
                long offset=0,rejected=0;String storedAnchor=null,storedVersion=null;boolean completed=false;Map<String,Long> reasons=new LinkedHashMap<>();
                try(var query=connection.prepareStatement("SELECT offset_bytes,rejected_lines,rejection_counts,anchor_hash,observed_version,completed FROM llm_gateway_analytics.checkpoints WHERE tenant_id=? AND source_id=? AND kind=? AND file_id=? FOR UPDATE")) {
                    query.setString(1,properties.getTenantId());query.setString(2,properties.getSourceId());query.setString(3,kind);query.setString(4,identity);
                    try(var result=query.executeQuery()){if(result.next()){
                        offset=result.getLong(1);rejected=result.getLong(2);
                        storedAnchor=result.getString(4);storedVersion=result.getString(5);completed=result.getBoolean(6);
                        var counts=JSON.readTree(result.getString(3));counts.fields().forEachRemaining(entry->reasons.put(entry.getKey(),entry.getValue().longValue()));
                    }}
                }
                boolean gzip=path.getFileName().toString().endsWith(".gz");
                String observedVersion=digest(attributes.size()+"|"+attributes.lastModifiedTime());
                if(gzip&&completed&&observedVersion.equals(storedVersion)){connection.rollback();return;}
                if(gzip&&storedVersion!=null&&!observedVersion.equals(storedVersion))offset=0;
                if(!gzip&&(attributes.size()<offset||(storedAnchor!=null&&!storedAnchor.equals(anchor(path,offset)))))offset=0;
                long end=offset;boolean finished=false;
                try(InputStream raw=new BufferedInputStream(Files.newInputStream(path));InputStream input=gzip?new BufferedInputStream(new GZIPInputStream(raw)):raw) {
                    input.skipNBytes(offset);
                    BoundedJsonlReader reader=new BoundedJsonlReader(input,properties.getMaxLineBytes(),offset);
                    for(int n=0;n<properties.getBatchSize();n++) {
                        BoundedJsonlReader.Line line=reader.next();if(line==null)break;
                        end=line.endOffset();
                        String reason=null;
                        if(line.oversized())reason="line_too_large";
                        else {
                            var savepoint=connection.setSavepoint();
                            try{AnalyticsRecord.insert(connection,kind,line.bytes(),properties.getTenantId());}
                            catch(AnalyticsRecord.Rejected error){reason=error.code;}
                            catch(SQLException error){
                                if(error.getSQLState()==null||!error.getSQLState().startsWith("22"))throw error;
                                connection.rollback(savepoint);reason="invalid_record";
                            }
                            connection.releaseSavepoint(savepoint);
                        }
                        if(reason!=null){rejected++;reasons.merge(reason,1L,Long::sum);}
                    }
                    finished=reader.cleanEof();
                }
                BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!java.util.Objects.equals(attributes.fileKey(),after.fileKey())||!attributes.creationTime().equals(after.creationTime())
                        ||(gzip&&!attributes.lastModifiedTime().equals(after.lastModifiedTime()))||(!gzip&&after.size()<end))throw new IOException("source_changed");
                try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.checkpoints (tenant_id,source_id,kind,file_id,file_name,offset_bytes,rejected_lines,rejection_counts,anchor_hash,observed_version,completed) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT (tenant_id,source_id,kind,file_id) DO UPDATE SET file_name=EXCLUDED.file_name,offset_bytes=EXCLUDED.offset_bytes,rejected_lines=EXCLUDED.rejected_lines,rejection_counts=EXCLUDED.rejection_counts,anchor_hash=EXCLUDED.anchor_hash,observed_version=EXCLUDED.observed_version,completed=EXCLUDED.completed,updated_at=now()")) {
                    query.setString(1,properties.getTenantId());query.setString(2,properties.getSourceId());query.setString(3,kind);query.setString(4,identity);query.setString(5,safeName(path.getFileName().toString()));query.setLong(6,end);query.setLong(7,rejected);query.setString(8,JSON.writeValueAsString(reasons));query.setString(9,gzip?null:anchor(path,end));query.setString(10,observedVersion);query.setBoolean(11,finished);query.executeUpdate();
                }
                connection.commit();
            } catch(Exception error) {connection.rollback();if(error instanceof SQLException sql)throw sql;if(error instanceof IOException io)throw io;throw new IOException("ingestion_failed");}
        }
    }

    private void refreshStatus() throws SQLException,IOException {
        List<Map<String,Object>> latest=new ArrayList<>();Map<String,Long> reasons=new LinkedHashMap<>();long rejected=0;
        try(var connection=database.open();var query=connection.prepareStatement("SELECT file_name,offset_bytes,updated_at,rejected_lines,rejection_counts FROM llm_gateway_analytics.checkpoints WHERE tenant_id=? AND source_id=? ORDER BY updated_at DESC")) {
            query.setString(1,properties.getTenantId());query.setString(2,properties.getSourceId());query.setFetchSize(100);
            // A cursor keeps historic checkpoint scans bounded in memory.
            connection.setAutoCommit(false);
            try(var result=query.executeQuery()) {while(result.next()) {
                rejected+=result.getLong(4);
                JSON.readTree(result.getString(5)).fields().forEachRemaining(entry->reasons.merge(entry.getKey(),entry.getValue().longValue(),Long::sum));
                if(latest.size()<100)latest.add(Map.of("sourceId",properties.getSourceId(),"fileName",result.getString(1),"offsetBytes",result.getLong(2),"updatedAt",result.getTimestamp(3).toInstant().toString()));
            }}connection.commit();
        }
        files=List.copyOf(latest);rejectedLines=rejected;rejectionCounts=Map.copyOf(reasons);
    }
    /** Bounded payload cleanup. Identity tables are deliberately retained for replay safety. */
    private void pruneExpired() throws SQLException {
        if(properties.getRequestRetentionDays()==0&&properties.getContentRetentionDays()==0)return;
        for(String tenantId:security.isEnterprise()?security.getAllowedTenants():List.of(properties.getTenantId()))pruneExpired(tenantId);
    }
    private void pruneExpired(String tenantId) throws SQLException {
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")){
                    lock.setString(1,"llm-gateway-analytics-retention:"+tenantId);
                    try(var result=lock.executeQuery()){result.next();if(!result.getBoolean(1)){connection.rollback();return;}}
                }
                if(properties.getContentRetentionDays()>0)deleteBatch(connection,
                        "DELETE FROM llm_gateway_analytics.contents WHERE (tenant_id,request_id,phase) IN (SELECT tenant_id,request_id,phase FROM llm_gateway_analytics.contents WHERE tenant_id=? AND ingested_at<? ORDER BY ingested_at LIMIT 250 FOR UPDATE SKIP LOCKED)",
                        properties.getContentRetentionDays(),tenantId);
                if(properties.getRequestRetentionDays()>0)deleteRequestBatch(connection,properties.getRequestRetentionDays(),tenantId);
                MonitoringStore.prune(connection,tenantId,properties.getRequestRetentionDays(),properties.getContentRetentionDays());
                connection.commit();
            }catch(SQLException error){connection.rollback();throw error;}
        }
    }
    private void deleteBatch(Connection connection,String sql,int days,String tenantId) throws SQLException {
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,tenantId);
            query.setTimestamp(2,java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(days))));
            query.executeUpdate();
        }
    }
    private void deleteRequestBatch(Connection connection,int days,String tenantId) throws SQLException {
        List<String> ids=new ArrayList<>();
        try(var select=connection.prepareStatement("SELECT request_id FROM llm_gateway_analytics.requests WHERE tenant_id=? AND started_at<? ORDER BY started_at LIMIT 250 FOR UPDATE SKIP LOCKED")){
            select.setString(1,tenantId);
            select.setTimestamp(2,java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(days))));
            try(var result=select.executeQuery()){while(result.next())ids.add(result.getString(1));}
        }
        if(ids.isEmpty())return;
        java.sql.Array selected=connection.createArrayOf("text",ids.toArray(String[]::new));
        try {
            // Lock identities first. Content insert locks the same identity, so after this
            // update completes the following DELETE sees every committed content row.
            executeForRequests(connection,"INSERT INTO llm_gateway_analytics.request_identities (tenant_id,request_id) SELECT tenant_id,request_id FROM llm_gateway_analytics.requests WHERE tenant_id=? AND request_id=ANY(?) ON CONFLICT DO NOTHING",tenantId,selected);
            executeForRequests(connection,"UPDATE llm_gateway_analytics.request_identities SET purged_at=now() WHERE tenant_id=? AND request_id=ANY(?)",tenantId,selected);
            executeForRequests(connection,"DELETE FROM llm_gateway_analytics.contents WHERE tenant_id=? AND request_id=ANY(?)",tenantId,selected);
            executeForRequests(connection,"DELETE FROM llm_gateway_analytics.requests WHERE tenant_id=? AND request_id=ANY(?)",tenantId,selected);
        }finally{selected.free();}
    }
    private static void executeForRequests(Connection connection,String sql,String tenantId,java.sql.Array ids) throws SQLException {
        try(var query=connection.prepareStatement(sql)){query.setString(1,tenantId);query.setArray(2,ids);query.executeUpdate();}
    }
    private static String anchor(Path path,long offset) throws IOException {
        if(offset==0)return null;
        var buffer=java.nio.ByteBuffer.allocate((int)Math.min(256,offset));
        try(var channel=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ)) {
            channel.position(Math.max(0,offset-256));
            while(buffer.hasRemaining()&&channel.read(buffer)!=-1){}
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(buffer.array(),buffer.position())));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("identity_unavailable");}
    }
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException("identity_unavailable");}}
    private static String safeName(String value){String safe=value.replaceAll("(?i)sk-[a-z0-9_-]*","[REDACTED]").replaceAll("[\\p{Cc}\\p{Cf}]","?");return safe.substring(0,Math.min(safe.length(),160));}
}
