package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only prepared SQL. Callers must execute these blocking methods on the admin scheduler. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class AnalyticsQueries {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String TABLE = "llm_gateway_analytics.requests";
    private static final String TOKEN_SUM = "COALESCE(prompt_tokens,0)+COALESCE(completion_tokens,0)+COALESCE(cache_write_tokens,0)+COALESCE(cache_write_1h_tokens,0)+COALESCE(cache_read_tokens,0)";
    private static final String MISSING = "usage_availability='missing' OR (usage_availability IS NULL AND COALESCE(record->>'generation','true')<>'false' AND (status BETWEEN 200 AND 299 OR outcome='cancelled') AND cost_usd IS NULL AND prompt_tokens IS NULL AND completion_tokens IS NULL AND cache_write_tokens IS NULL AND cache_write_1h_tokens IS NULL AND cache_read_tokens IS NULL)";
    private static final String STATS = """
            count(*) AS "requests",
            count(*) FILTER (WHERE status>=400 OR outcome IN ('http_error','upstream_error')) AS "errors",
            count(*) FILTER (WHERE outcome='cancelled') AS "cancelled",
            COALESCE(sum(prompt_tokens),0) AS "inputTokens",
            COALESCE(sum(completion_tokens),0) AS "outputTokens",
            COALESCE(sum(COALESCE(cache_write_tokens,0)+COALESCE(cache_write_1h_tokens,0)),0) AS "cacheWriteTokens",
            COALESCE(sum(cache_read_tokens),0) AS "cacheReadTokens",
            COALESCE(sum(COALESCE(total_tokens,%s)),0) AS "totalTokens",
            COALESCE(sum(cost_usd) FILTER (WHERE source='gateway'),0) AS "costUsd",
            COALESCE(sum(api_equivalent_cost_usd) FILTER (WHERE source='claude_code'),0) AS "apiEquivalentCostUsd",
            count(*) FILTER (WHERE source='claude_code' AND api_equivalent_cost_usd IS NULL
                AND COALESCE(usage_availability,'')<>'not_applicable') AS "missingEquivalentCostRequests",
            count(*) FILTER (WHERE source='claude_code' AND usage_availability IN ('missing','partial')) AS "_equivalentIncompleteRequests",
            count(*) FILTER (WHERE source='gateway' AND usage_availability IN ('missing','partial')) AS "_apiIncompleteRequests",
            count(*) FILTER (WHERE source='gateway' AND cost_usd IS NULL AND COALESCE(record->>'generation','true')<>'false'
                AND COALESCE(usage_availability,'')<>'not_applicable' AND (%s)>0) AS "unpricedRequests",
            count(*) FILTER (WHERE %s) AS "missingUsageRequests",
            count(*) FILTER (WHERE usage_availability='partial') AS "partialUsageRequests",
            percentile_cont(0.95) WITHIN GROUP (ORDER BY total_ms) AS "p95TotalMs"
            """.formatted(TOKEN_SUM,TOKEN_SUM,MISSING);
    private final AnalyticsDatabase database;
    public AnalyticsQueries(AnalyticsDatabase database) { this.database=database; }

    public Map<String,Object> overview(AdminFilters filters) throws SQLException {return overview(filters,"local");}
    public Map<String,Object> overview(AdminFilters filters,String tenantId) throws SQLException {
        Where where=where(filters,tenantId);
        try(Connection c=readConnection()) {
            Map<String,Object> totals=stats(rows(c,"SELECT "+STATS+" FROM "+TABLE+where.sql,where.params).getFirst());
            List<Map<String,Object>> models=rows(c,"SELECT model,provider,"+STATS+" FROM "+TABLE+where.sql+" GROUP BY model,provider ORDER BY count(*) DESC,model NULLS LAST,provider NULLS LAST LIMIT 1001",where.params);
            boolean truncated=models.size()>1000;
            if(truncated) models=new ArrayList<>(models.subList(0,1000));
            models.forEach(AnalyticsQueries::stats);
            var dayParams=new ArrayList<Object>();dayParams.add(filters.zone().getId());dayParams.addAll(where.params);
            List<Map<String,Object>> days=rows(c,"SELECT timezone(?,started_at)::date AS day,"+STATS+" FROM "+TABLE+where.sql+" GROUP BY 1 ORDER BY 1",dayParams);
            days.forEach(AnalyticsQueries::stats);
            List<Map<String,Object>> sources=rows(c,"SELECT source,billing_mode AS \"billingMode\","+STATS+" FROM "+TABLE+where.sql+" GROUP BY source,billing_mode ORDER BY source,billing_mode",where.params);
            sources.forEach(AnalyticsQueries::stats);
            c.commit();
            var result=new LinkedHashMap<String,Object>();
            result.put("from",filters.from().toString());result.put("to",filters.to().toString());result.put("zone",filters.zone().getId());
            result.put("source",filters.source());result.put("bySource",sources);result.put("totals",totals);result.put("byModel",models);result.put("byModelTruncated",truncated);result.put("byDay",days);
            return result;
        }
    }

    public Map<String,Object> requests(AdminFilters filters) throws SQLException {return requests(filters,"local");}
    public Map<String,Object> requests(AdminFilters filters,String tenantId) throws SQLException {
        Where where=where(filters,tenantId);
        try(Connection c=readConnection()) {
            Object total=rows(c,"SELECT count(*) AS total FROM "+TABLE+where.sql,where.params).getFirst().get("total");
            var params=new ArrayList<>(where.params);params.add(filters.limit());params.add(filters.offset());
            List<Map<String,Object>> items=rows(c,"""
                    SELECT request_id AS "requestId",started_at AS "startedAt",provider,model,key_id AS "keyId",
                    source,billing_mode AS "billingMode",api_equivalent_cost_usd AS "apiEquivalentCostUsd",
                    session_id AS "sessionId",status,outcome,total_tokens AS "totalTokens",cost_usd AS "costUsd",
                    total_ms AS "totalMs",usage_availability AS "usageAvailability"
                    FROM llm_gateway_analytics.requests
                    """+where.sql+" ORDER BY started_at DESC,request_id DESC LIMIT ? OFFSET ?",params);
            c.commit();
            return Map.of("items",items,"total",total,"limit",filters.limit(),"offset",filters.offset());
        }
    }

    public Map<String,Object> detail(String requestId) throws Exception {return detail(requestId,"local",true);}
    public Map<String,Object> detail(String requestId,String tenantId,boolean includeContent) throws Exception {
        if(!requestId.matches("(?:[a-fA-F0-9]{32}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")) throw new IllegalArgumentException("Invalid request ID");
        requireTenant(tenantId);
        try(Connection c=readConnection()) {
            List<Map<String,Object>> access=rows(c,"SELECT record::text AS record FROM "+TABLE+" WHERE tenant_id=? AND request_id=?",List.of(tenantId,requestId));
            if(access.isEmpty()) throw new NoSuchElementException();
            var contents=new LinkedHashMap<String,Object>();
            if(includeContent){
                contents.put("request",null);contents.put("response",null);
                for(var row:rows(c,"SELECT phase,record::text AS record FROM llm_gateway_analytics.contents WHERE tenant_id=? AND request_id=?",List.of(tenantId,requestId))) {
                    contents.put((String)row.get("phase"),JSON.readTree((String)row.get("record")));
                }
            }
            c.commit();
            return Map.of("request",JSON.readTree((String)access.getFirst().get("record")),"contents",contents);
        }
    }

    /** Checkpoint status is queried by authenticated tenant, never by a client-supplied source ID. */
    public Map<String,Object> ingestion(String tenantId) throws SQLException {
        requireTenant(tenantId);
        try(Connection c=readConnection()) {
            var files=rows(c,"SELECT source_id AS \"sourceId\",file_name AS \"fileName\",offset_bytes AS \"offsetBytes\",updated_at AS \"updatedAt\" FROM llm_gateway_analytics.checkpoints WHERE tenant_id=? ORDER BY updated_at DESC LIMIT 100",List.of(tenantId));
            var sources=rows(c,"SELECT source_id AS \"sourceId\",state,last_error AS \"lastError\",last_success_at AS \"lastSuccessAt\",observed_at AS \"observedAt\",rejected_lines AS \"rejectedLines\",rejection_counts::text AS \"rejectionCounts\",now()-observed_at > (greatest(30000,3*poll_interval_ms) * interval '1 millisecond') AS stale FROM llm_gateway_analytics.ingestion_status WHERE tenant_id=? ORDER BY observed_at DESC",List.of(tenantId));
            long rejected=0;var reasons=new LinkedHashMap<String,Long>();
            String state=sources.isEmpty()?"unknown":"ok",lastError=null,lastSuccessAt=null,observedAt=null;
            for(var source:sources){
                rejected+=((Number)source.get("rejectedLines")).longValue();
                try{JSON.readTree((String)source.remove("rejectionCounts")).fields().forEachRemaining(entry->reasons.merge(entry.getKey(),entry.getValue().longValue(),Long::sum));}
                catch(Exception error){throw new SQLException("invalid_checkpoint_counts",error);}
                String success=(String)source.get("lastSuccessAt");
                if(success!=null&&(lastSuccessAt==null||success.compareTo(lastSuccessAt)>0))lastSuccessAt=success;
                String seen=(String)source.get("observedAt");
                if(seen!=null&&(observedAt==null||seen.compareTo(observedAt)>0))observedAt=seen;
                if(Boolean.TRUE.equals(source.get("stale"))){state="stale";lastError="status_stale";}
                else if(!"stale".equals(state)&&"error".equals(source.get("state"))){state="error";lastError=(String)source.get("lastError");}
            }
            c.commit();
            var response=new LinkedHashMap<String,Object>();
            response.put("enabled",true);response.put("running",null);response.put("status",state);
            response.put("lastSuccessAt",lastSuccessAt);response.put("lastError",lastError);response.put("observedAt",observedAt);
            response.put("sources",sources);
            response.put("files",files);response.put("rejectedLines",rejected);response.put("rejectionCounts",reasons);
            return response;
        }
    }

    private Connection readConnection() throws SQLException {
        Connection c=database.open();
        try { c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);return c; }
        catch(SQLException e){c.close();throw e;}
    }
    private static List<Map<String,Object>> rows(Connection c,String sql,List<Object> params) throws SQLException {
        try(PreparedStatement statement=c.prepareStatement(sql)) {
            statement.setQueryTimeout(10);statement.setFetchSize(200);
            for(int i=0;i<params.size();i++)statement.setObject(i+1,params.get(i));
            try(ResultSet result=statement.executeQuery()) {
                var rows=new ArrayList<Map<String,Object>>();ResultSetMetaData meta=result.getMetaData();
                while(result.next()) {
                    var row=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=meta.getColumnCount();i++) {
                        Object value=result.getObject(i);
                        if(value instanceof Timestamp timestamp)value=timestamp.toInstant().toString();
                        else if(value instanceof java.sql.Date date)value=date.toLocalDate().toString();
                        row.put(meta.getColumnLabel(i),value);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private static Map<String,Object> stats(Map<String,Object> value) {
        value.put("costComplete",((Number)value.get("unpricedRequests")).longValue()==0
                && ((Number)value.remove("_apiIncompleteRequests")).longValue()==0);
        value.put("equivalentCostComplete",((Number)value.get("missingEquivalentCostRequests")).longValue()==0
                && ((Number)value.remove("_equivalentIncompleteRequests")).longValue()==0);
        return value;
    }
    private record Where(String sql,List<Object> params) {}
    private static Where where(AdminFilters f,String tenantId) {
        requireTenant(tenantId);
        StringBuilder sql=new StringBuilder(" WHERE tenant_id=? AND started_at>=? AND started_at<?");
        var params=new ArrayList<Object>();params.add(tenantId);params.add(f.start().atOffset(ZoneOffset.UTC));params.add(f.end().atOffset(ZoneOffset.UTC));
        if(!"all".equals(f.source()))append(sql,params,"source",f.source());
        append(sql,params,"billing_mode",f.billingMode());
        if(!"all".equals(f.traffic()))append(sql,params,"traffic",f.traffic());
        append(sql,params,"key_id",f.keyId());append(sql,params,"model",f.model());append(sql,params,"session_id",f.sessionId());
        append(sql,params,"provider",f.provider());append(sql,params,"outcome",f.outcome());
        return new Where(sql.toString(),params);
    }
    private static void requireTenant(String tenantId){if(tenantId==null||!tenantId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid tenant");}
    private static void append(StringBuilder sql,List<Object> params,String column,String value) {
        if(value!=null){sql.append(" AND ").append(column).append("=?");params.add(value);}
    }
}
