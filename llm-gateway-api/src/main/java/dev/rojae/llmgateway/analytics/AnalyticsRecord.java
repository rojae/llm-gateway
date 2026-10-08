package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

/** Validates the ledger's typed fields before any SQL; rejected input never enters diagnostics. */
final class AnalyticsRecord {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(4_194_304).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String INSERT = "INSERT INTO llm_gateway_analytics.requests (tenant_id,request_id,started_at,provider,model,key_id,session_id,traffic,outcome,status,usage_availability,prompt_tokens,completion_tokens,cache_write_tokens,cache_write_1h_tokens,cache_read_tokens,total_tokens,cost_usd,total_ms,first_byte_ms,source,billing_mode,api_equivalent_cost_usd,record) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (tenant_id,request_id) DO NOTHING";
    private static final Set<String> TRAFFIC = Set.of("live","test","demo");
    private static final Set<String> OUTCOMES = Set.of("success","http_error","upstream_error","cancelled");
    private static final Set<String> USAGE = Set.of("reported","missing","partial","not_applicable");

    static JsonNode parse(byte[] bytes) throws Rejected {
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || root.toString().contains("\\u0000")) throw new Rejected("invalid_record");
            String serialized = root.toString();
            for (int i = 0; i < serialized.length(); i++) {
                char c = serialized.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= serialized.length() || !Character.isLowSurrogate(serialized.charAt(i))) throw new Rejected("invalid_record");
                } else if (Character.isLowSurrogate(c)) throw new Rejected("invalid_record");
            }
            String id = text(root,"requestId",64);
            if (id == null || !id.matches("(?i)([0-9a-f]{32}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})")) throw new Rejected("invalid_request_id");
            return root;
        } catch (Rejected error) { throw error; }
        catch (Exception error) { throw new Rejected("invalid_json"); }
    }

    static void insert(Connection connection, String kind, byte[] bytes) throws SQLException, Rejected {insert(connection,kind,bytes,"local");}
    static void insert(Connection connection, String kind, byte[] bytes, String tenantId) throws SQLException, Rejected {
        if(tenantId==null||!tenantId.matches("[A-Za-z0-9._:-]{1,128}")||connection.getAutoCommit())throw new SQLException("invalid_analytics_insert_context");
        JsonNode root = parse(bytes);
        String requestId=root.path("requestId").textValue();
        if ("content".equals(kind)) {
            String phase = text(root,"phase",16);
            if (!"request".equals(phase) && !"response".equals(phase)) throw new Rejected("invalid_phase");
            if (!root.path("text").isTextual()) throw new Rejected("invalid_record");
            if(requestWasPurged(connection,tenantId,requestId))return;
            if(!claimIdentity(connection,"INSERT INTO llm_gateway_analytics.content_identities (tenant_id,request_id,phase) VALUES (?,?,?) ON CONFLICT DO NOTHING",tenantId,requestId,phase))return;
            try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.contents (tenant_id,request_id,phase,record) VALUES (?,?,?,?::jsonb) ON CONFLICT (tenant_id,request_id,phase) DO NOTHING")) {
                query.setString(1,tenantId);query.setString(2,requestId); query.setString(3,phase); query.setString(4,root.toString()); query.executeUpdate();
            }
            return;
        }
        // Validate all values before executing, so malformed fields cannot poison the batch transaction.
        Instant started;
        try {
            String timestamp = text(root,"timestamp",64);
            if (timestamp != null) started = Instant.parse(timestamp);
            else {
                Long millis = number(root,"ts");
                if (millis == null) throw new Rejected("invalid_timestamp");
                started = Instant.ofEpochMilli(millis);
            }
            if (started.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || started.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new Rejected("invalid_timestamp");
        } catch (Rejected error) { throw error; }
        catch (Exception error) { throw new Rejected("invalid_timestamp"); }
        String provider=text(root,"provider",128),model=text(root,"model",512),key=text(root,"keyId",256),session=text(root,"sessionId",512);
        Long status=number(root,"status");
        if(status != null && (status < 100 || status > 599)) throw new Rejected("invalid_record");
        Long prompt=number(root,"promptTokens"),completion=number(root,"completionTokens"),write=number(root,"cacheWriteTokens"),write1h=number(root,"cacheWrite1hTokens"),read=number(root,"cacheReadTokens"),total=number(root,"totalTokens");
        BigDecimal cost=decimal(root,"costUsd");
        if(cost != null && cost.compareTo(new BigDecimal("1000000000000")) >= 0) throw new Rejected("invalid_record");
        String source=oneOf(root,"source",Set.of("gateway","claude_code"),"gateway");
        String billingMode=oneOf(root,"billingMode",Set.of("api_key","subscription_declared","api_key_declared","unknown"),
                "claude_code".equals(source)?"unknown":"api_key");
        BigDecimal equivalent=decimal(root,"apiEquivalentCostUsd");
        if (equivalent!=null && equivalent.compareTo(new BigDecimal("1000000000000"))>=0) throw new Rejected("invalid_record");
        if ("claude_code".equals(source) && cost!=null) throw new Rejected("invalid_cost_attribution");
        if ("gateway".equals(source) && equivalent!=null) throw new Rejected("invalid_cost_attribution");
        Double elapsed=duration(root,"totalMs"),firstByte=duration(root,"ttftMs");
        String traffic=oneOf(root,"traffic",TRAFFIC,"live");
        boolean cancelled=root.path("clientDisconnected").asBoolean(false);
        boolean failure=root.hasNonNull("upstreamError");
        String outcome=oneOf(root,"outcome",OUTCOMES,cancelled?"cancelled":status!=null&&status>=400?"http_error":failure?"upstream_error":status!=null&&status>=200&&status<400?"success":"http_error");
        String usage=oneOf(root,"usageAvailability",USAGE,!root.path("generation").asBoolean(true)?"not_applicable":total==null?"missing":cancelled||failure?"partial":"reported");
        if(!claimIdentity(connection,"INSERT INTO llm_gateway_analytics.request_identities (tenant_id,request_id) VALUES (?,?) ON CONFLICT DO NOTHING",tenantId,requestId))return;
        try(var query=connection.prepareStatement(INSERT)) {
            Object[] values={tenantId,requestId,Timestamp.from(started),provider,model,key,session,traffic,outcome,status==null?null:status.intValue(),usage,prompt,completion,write,write1h,read,total,cost,elapsed,firstByte,source,billingMode,equivalent,root.toString()};
            for(int i=0;i<values.length;i++) query.setObject(i+1,values[i]);
            query.executeUpdate();
        }
    }
    private static boolean claimIdentity(Connection connection,String sql,String... values) throws SQLException {
        try(var query=connection.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)query.setString(i+1,values[i]);
            return query.executeUpdate()==1;
        }
    }
    private static boolean requestWasPurged(Connection connection,String tenantId,String requestId) throws SQLException {
        try(var query=connection.prepareStatement("SELECT purged_at FROM llm_gateway_analytics.request_identities WHERE tenant_id=? AND request_id=? FOR UPDATE")){
            query.setString(1,tenantId);query.setString(2,requestId);
            try(var result=query.executeQuery()){return result.next()&&result.getTimestamp(1)!=null;}
        }
    }
    private static String text(JsonNode root,String field,int max) throws Rejected {
        JsonNode value=root.get(field); if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.textValue().length()>max)throw new Rejected("invalid_record");
        return value.textValue();
    }
    private static String oneOf(JsonNode root,String field,Set<String> allowed,String fallback) throws Rejected {
        String value=text(root,field,32); if(value==null)return fallback;
        if(!allowed.contains(value))throw new Rejected("invalid_record"); return value;
    }
    private static Long number(JsonNode root,String field) throws Rejected {
        JsonNode value=root.get(field); if(value==null||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw new Rejected("invalid_record");return value.longValue();
    }
    private static BigDecimal decimal(JsonNode root,String field) throws Rejected {
        JsonNode value=root.get(field);if(value==null||value.isNull())return null;
        if(!value.isNumber()||!Double.isFinite(value.doubleValue())||value.decimalValue().signum()<0)throw new Rejected("invalid_record");return value.decimalValue();
    }
    private static Double duration(JsonNode root,String field) throws Rejected {
        BigDecimal value=decimal(root,field);return value==null?null:value.doubleValue();
    }
    static final class Rejected extends Exception {
        final String code;
        Rejected(String code){super(code);this.code=code;}
    }
}
