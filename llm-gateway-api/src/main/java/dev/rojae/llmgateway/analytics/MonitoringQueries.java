package dev.rojae.llmgateway.analytics;

import java.sql.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.*;

/** Explicit safe projections: prompt text can only be read from the separate content relation. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class MonitoringQueries {
    private final AnalyticsDatabase database;
    private final AuditService audit;
    public MonitoringQueries(AnalyticsDatabase database,AuditService audit){this.database=database;this.audit=audit;}
    public record Page(List<Map<String,Object>> items,long total,int limit,int offset){}
    private static final String QUESTION="""
        SELECT q.id,q.external_prompt_id,q.source,q.started_at,q.model,
          (SELECT count(*)::text||':'||COALESCE(max(r.received_at)::text,'')||':'||count(r.content_purged_at)::text||':'||count(r.purged_at)::text FROM llm_gateway_analytics.monitoring_responses r WHERE r.tenant_id=q.tenant_id AND r.question_id=q.id) AS response_revision,
          CASE WHEN a.n>0 AND a.input_known=a.n THEN a.input_tokens END AS input_tokens,
          CASE WHEN a.n>0 AND a.output_known=a.n THEN a.output_tokens END AS output_tokens,
          CASE WHEN a.n>0 THEN a.cache_read_tokens END AS cache_read_tokens,
          CASE WHEN a.n>0 THEN a.cache_write_tokens END AS cache_write_tokens,
          CASE WHEN a.n>0 AND a.total_known=a.n THEN a.total_tokens END AS total_tokens,
          CASE WHEN a.n>0 AND a.cost_known=a.n THEN a.cost END AS api_equivalent_cost_usd,
          CASE WHEN a.n>0 AND a.cost_known=a.n THEN 'claude_client_estimate' END AS cost_source,
          CASE WHEN q.source='codex' THEN 'unattributed' WHEN a.n>0 AND a.total_known=a.n THEN 'reported' ELSE 'missing' END AS usage_availability,
          CASE WHEN q.source='claude_code' AND a.n>0 THEN 'linked' ELSE 'unattributed' END AS attribution,
          CASE WHEN a.n>0 THEN 'observed' ELSE 'unknown' END AS coverage,COALESCE(a.n,0) AS call_count
        FROM llm_gateway_analytics.monitoring_questions q
        LEFT JOIN LATERAL (
          SELECT count(*) AS n,count(e.input_tokens) AS input_known,count(e.output_tokens) AS output_known,
            count(e.total_tokens) AS total_known,count(e.api_equivalent_cost_usd) AS cost_known,
            sum(e.input_tokens) AS input_tokens,sum(e.output_tokens) AS output_tokens,
            sum(COALESCE(e.cache_read_tokens,0)) AS cache_read_tokens,sum(COALESCE(e.cache_write_tokens,0)) AS cache_write_tokens,
            sum(e.total_tokens) AS total_tokens,sum(e.api_equivalent_cost_usd) AS cost
          FROM llm_gateway_analytics.monitoring_events e
          WHERE e.tenant_id=q.tenant_id AND e.question_id=q.id AND e.source='claude_code'
            AND e.event_kind='api_request' AND e.attribution='linked' AND e.semantics='delta' AND e.scope='response'
        ) a ON true
        """;
    public Map<String,Object> createAccount(String tenant,String actor,String name)throws SQLException{
        AuditService.validateTenant(tenant);if(name==null||name.isBlank()||name.length()>80||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid account");
        try(var connection=database.open()){
            connection.setAutoCommit(false);try{
                Map<String,Object> result;
                try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_accounts(tenant_id,id,name,created_by) VALUES(?,?,?,?) RETURNING id,name,created_at")){
                    query.setString(1,tenant);query.setObject(2,UUID.randomUUID());query.setString(3,name.strip());query.setString(4,actor);try(var rows=query.executeQuery()){rows.next();result=row(rows);}
                }
                audit.record(connection,tenant,actor,"monitoring.account.create",result.get("id").toString(),"success");connection.commit();return result;
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    public Page accounts(String tenant,int limit,int offset)throws SQLException{
        return page("""
            SELECT a.id,a.name,a.created_at,
             (SELECT count(*) FROM llm_gateway_analytics.monitoring_sessions s WHERE s.tenant_id=a.tenant_id AND s.account_id=a.id AND s.purged_at IS NULL) AS session_count,
             (SELECT count(*) FROM llm_gateway_analytics.monitoring_questions q JOIN llm_gateway_analytics.monitoring_sessions s ON s.tenant_id=q.tenant_id AND s.id=q.session_id WHERE s.tenant_id=a.tenant_id AND s.account_id=a.id AND q.purged_at IS NULL AND s.purged_at IS NULL) AS question_count,
             (SELECT max(e.received_at) FROM llm_gateway_analytics.monitoring_events e WHERE e.tenant_id=a.tenant_id AND e.account_id=a.id) AS last_received_at
            FROM llm_gateway_analytics.monitoring_accounts a WHERE a.tenant_id=? ORDER BY a.created_at DESC,a.id
            """,List.of(tenant),limit,offset);
    }
    public Page sessions(String tenant,MultiValueMap<String,String> params)throws SQLException{
        Set<String> allowed=Set.of("accountId","sessionId","source","from","to","zone","limit","offset");
        for(var field:params.entrySet())if(!allowed.contains(field.getKey())||field.getValue().size()!=1)throw new IllegalArgumentException("Invalid filter");
        String source=params.getFirst("source");if(source!=null&&!Set.of("all","claude_code","codex").contains(source))throw new IllegalArgumentException("Invalid source");
        var dates=new LinkedMultiValueMap<String,String>(params);dates.remove("accountId");dates.remove("sessionId");dates.remove("source");var filters=AdminFilters.parse(dates);
        var values=new ArrayList<Object>();values.add(tenant);values.add(Timestamp.from(filters.start()));values.add(Timestamp.from(filters.end()));
        String where="s.tenant_id=? AND s.purged_at IS NULL AND s.last_seen_at>=? AND s.first_seen_at<?";
        if(params.getFirst("accountId")!=null){where+=" AND s.account_id=?";values.add(UUID.fromString(params.getFirst("accountId")));}
        if(params.getFirst("sessionId")!=null){
            String value=params.getFirst("sessionId");UUID session=UUID.fromString(value);
            if(!session.toString().equalsIgnoreCase(value))throw new IllegalArgumentException("Invalid session");
            where+=" AND s.id=?";values.add(session);
        }
        if(source!=null&&!source.equals("all")){where+=" AND s.source=?";values.add(source);}
        String sql="""
            SELECT s.id,s.account_id,s.source,s.external_session_id,s.cli_version,s.first_seen_at,s.last_seen_at,
             (SELECT count(*)::text||':'||COALESCE(max(r.received_at)::text,'')||':'||count(r.content_purged_at)::text||':'||count(r.purged_at)::text FROM llm_gateway_analytics.monitoring_responses r WHERE r.tenant_id=s.tenant_id AND r.session_id=s.id) AS response_revision,
             CASE WHEN s.provider_account_hash IS NOT NULL THEN 'sha256:'||substring(s.provider_account_hash,1,12) END AS provider_account_id,s.provider_account_status,
             (SELECT count(*) FROM llm_gateway_analytics.monitoring_questions q WHERE q.tenant_id=s.tenant_id AND q.session_id=s.id AND q.purged_at IS NULL) AS question_count,
             CASE WHEN s.source='claude_code' AND a.n>0 AND a.total_known=a.n THEN a.total_tokens END AS total_tokens,
             CASE WHEN s.source='claude_code' AND a.n>0 AND a.cost_known=a.n THEN a.cost END AS api_equivalent_cost_usd,
             CASE WHEN s.source='claude_code' AND a.n>0 AND a.cost_known=a.n THEN 'claude_client_estimate' END AS cost_source,
             CASE WHEN s.source='codex' THEN 'unattributed' WHEN a.n>0 AND a.total_known=a.n THEN 'reported' ELSE 'missing' END AS usage_availability,
             (SELECT count(*) FROM llm_gateway_analytics.monitoring_events e WHERE e.tenant_id=s.tenant_id AND e.session_id=s.id AND e.attribution<>'linked') AS unattributed_events
            FROM llm_gateway_analytics.monitoring_sessions s
            LEFT JOIN LATERAL(SELECT count(*) AS n,count(e.total_tokens) AS total_known,count(e.api_equivalent_cost_usd) AS cost_known,sum(e.total_tokens) AS total_tokens,sum(e.api_equivalent_cost_usd) AS cost
             FROM llm_gateway_analytics.monitoring_events e WHERE e.tenant_id=s.tenant_id AND e.session_id=s.id AND e.source='claude_code' AND e.event_kind='api_request' AND e.attribution='linked' AND e.scope='response' AND e.semantics='delta' AND EXISTS(SELECT 1 FROM llm_gateway_analytics.monitoring_questions q WHERE q.tenant_id=e.tenant_id AND q.id=e.question_id AND q.purged_at IS NULL)) a ON true
            WHERE
            """+where+" ORDER BY s.last_seen_at DESC,s.id";
        return page(sql,values,filters.limit(),filters.offset());
    }
    public Page questions(String tenant,UUID session,int limit,int offset)throws SQLException{
        requireSession(tenant,session);return page(QUESTION+" WHERE q.tenant_id=? AND q.session_id=? AND q.purged_at IS NULL ORDER BY q.started_at,q.id",List.of(tenant,session),limit,offset);
    }
    public Map<String,Object> detail(String tenant,UUID question,boolean includeContent,String actor)throws SQLException{
        try(var connection=database.open()){
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try{
                var result=new LinkedHashMap<String,Object>();
                try(var query=connection.prepareStatement(QUESTION+" WHERE q.tenant_id=? AND q.id=? AND q.purged_at IS NULL AND EXISTS(SELECT 1 FROM llm_gateway_analytics.monitoring_sessions s WHERE s.tenant_id=q.tenant_id AND s.id=q.session_id AND s.purged_at IS NULL)")){
                    query.setString(1,tenant);query.setObject(2,question);try(var rows=query.executeQuery()){if(!rows.next())throw new NoSuchElementException();result.put("question",row(rows));}
                }
                result.put("content",null);result.put("contentAvailability",includeContent?"not_collected":"permission_required");
                if(includeContent){
                    try(var query=connection.prepareStatement("SELECT text,truncated FROM llm_gateway_analytics.monitoring_question_contents WHERE tenant_id=? AND question_id=?")){
                        query.setString(1,tenant);query.setObject(2,question);try(var rows=query.executeQuery()){if(rows.next()){result.put("content",row(rows));result.put("contentAvailability","available");}}
                    }
                    audit.record(connection,tenant,actor,"content.read","monitoring-question:"+question,"success");
                }
                var responses=responseRows(connection,tenant,"r.question_id=?",question,includeContent,null,null);
                result.put("responses",responses);
                String availability=includeContent?"not_collected":"permission_required";
                if(includeContent){
                    if(responses.stream().anyMatch(r->"available".equals(r.get("contentAvailability"))))availability="available";
                    else try(var query=connection.prepareStatement("SELECT 1 FROM llm_gateway_analytics.monitoring_responses WHERE tenant_id=? AND question_id=? AND (content_purged_at IS NOT NULL OR purged_at IS NOT NULL) LIMIT 1")){
                        query.setString(1,tenant);query.setObject(2,question);try(var rows=query.executeQuery()){if(rows.next())availability="purged";}
                    }
                }
                result.put("responseAvailability",availability);
                connection.commit();return result;
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    public Page responses(String tenant,UUID session,boolean includeContent,String actor,int limit,int offset)throws SQLException{
        if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("Invalid pagination");
        try(var connection=database.open()){
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try{
                try(var query=connection.prepareStatement("SELECT id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND id=? AND purged_at IS NULL")){
                    query.setString(1,tenant);query.setObject(2,session);try(var rows=query.executeQuery()){if(!rows.next())throw new NoSuchElementException();}
                }
                long total;
                try(var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.monitoring_responses r WHERE r.tenant_id=? AND r.session_id=? AND "+RESPONSE_VISIBLE)){
                    query.setString(1,tenant);query.setObject(2,session);try(var rows=query.executeQuery()){rows.next();total=rows.getLong(1);}
                }
                var items=responseRows(connection,tenant,"r.session_id=?",session,includeContent,limit,offset);
                if(includeContent)audit.record(connection,tenant,actor,"content.read","monitoring-session-responses:"+session,"success");
                connection.commit();return new Page(items,total,limit,offset);
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    private static final String RESPONSE_VISIBLE="r.purged_at IS NULL AND (r.question_id IS NULL OR EXISTS(SELECT 1 FROM llm_gateway_analytics.monitoring_questions q WHERE q.tenant_id=r.tenant_id AND q.id=r.question_id AND q.purged_at IS NULL))";
    private static List<Map<String,Object>> responseRows(Connection connection,String tenant,String filter,UUID id,boolean includeContent,Integer limit,Integer offset)throws SQLException{
        String sql="SELECT r.id,r.question_id,r.source,r.emitted_at,r.sequence,r.model,r.attribution,r.content_purged_at"+
            (includeContent?",c.text,c.truncated":"")+" FROM llm_gateway_analytics.monitoring_responses r "+
            (includeContent?"LEFT JOIN llm_gateway_analytics.monitoring_response_contents c ON c.tenant_id=r.tenant_id AND c.response_id=r.id ":"")+
            "WHERE r.tenant_id=? AND "+filter+" AND "+RESPONSE_VISIBLE+" ORDER BY r.emitted_at,r.sequence NULLS LAST,r.id"+(limit==null?"":" LIMIT ? OFFSET ?");
        var items=new ArrayList<Map<String,Object>>();
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,tenant);query.setObject(2,id);if(limit!=null){query.setInt(3,limit);query.setInt(4,offset);}
            try(var rows=query.executeQuery()){while(rows.next()){
                var item=row(rows);boolean purged=item.remove("contentPurgedAt")!=null;
                Object text=item.remove("text"),truncated=item.remove("truncated");
                item.put("content",includeContent&&!purged&&text!=null?Map.of("text",text,"truncated",truncated):null);
                item.put("contentAvailability",!includeContent?"permission_required":purged?"purged":text==null?"not_collected":"available");items.add(item);
            }}
        }
        return List.copyOf(items);
    }
    public Page events(String tenant,UUID session,int limit,int offset)throws SQLException{
        requireSession(tenant,session);
        Page result=page("SELECT id,event_kind,emitted_at,source,model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,api_equivalent_cost_usd,scope,semantics,attribution FROM llm_gateway_analytics.monitoring_events WHERE tenant_id=? AND session_id=? AND (question_id IS NULL OR EXISTS(SELECT 1 FROM llm_gateway_analytics.monitoring_questions q WHERE q.tenant_id=monitoring_events.tenant_id AND q.id=monitoring_events.question_id AND q.purged_at IS NULL)) ORDER BY emitted_at,id",List.of(tenant,session),limit,offset);
        for(var event:result.items()){event.put("apiEstimate",MonitoringCostEstimator.estimate(event));event.remove("apiEquivalentCostUsd");}return result;
    }
    public Map<String,Object> status(String tenant)throws SQLException{
        try(var connection=database.open();var query=connection.prepareStatement("SELECT last_received_at,stored,duplicates,rejected,unsupported,unattributed FROM llm_gateway_analytics.monitoring_status WHERE tenant_id=?")){
            query.setString(1,tenant);try(var rows=query.executeQuery()){
                Map<String,Object> result;if(rows.next())result=row(rows);else{result=new LinkedHashMap<>();result.put("lastReceivedAt",null);for(String key:List.of("stored","duplicates","rejected","unsupported","unattributed"))result.put(key,0L);}
                result.put("ready",database.isInitialized());return result;
            }
        }
    }
    private void requireSession(String tenant,UUID id)throws SQLException{
        try(var connection=database.open();var query=connection.prepareStatement("SELECT id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND id=? AND purged_at IS NULL")){
            query.setString(1,tenant);query.setObject(2,id);try(var rows=query.executeQuery()){if(!rows.next())throw new NoSuchElementException();}
        }
    }
    private Page page(String sql,List<?> values,int limit,int offset)throws SQLException{
        if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("Invalid pagination");
        try(var connection=database.open()){
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);long total;List<Map<String,Object>> items=new ArrayList<>();
            try(var query=connection.prepareStatement("SELECT count(*) FROM ("+sql+") rows")){bind(query,values);try(var rows=query.executeQuery()){rows.next();total=rows.getLong(1);}}
            try(var query=connection.prepareStatement(sql+" LIMIT ? OFFSET ?")){bind(query,values);query.setInt(values.size()+1,limit);query.setInt(values.size()+2,offset);try(var rows=query.executeQuery()){while(rows.next())items.add(row(rows));}}
            connection.commit();return new Page(List.copyOf(items),total,limit,offset);
        }
    }
    private static void bind(PreparedStatement query,List<?> values)throws SQLException{for(int i=0;i<values.size();i++)query.setObject(i+1,values.get(i));}
    private static Map<String,Object> row(ResultSet row)throws SQLException{
        var result=new LinkedHashMap<String,Object>();var columns=row.getMetaData();for(int i=1;i<=columns.getColumnCount();i++){
            String name=columns.getColumnLabel(i);StringBuilder camel=new StringBuilder();boolean upper=false;for(char c:name.toCharArray()){if(c=='_'){upper=true;continue;}camel.append(upper?Character.toUpperCase(c):c);upper=false;}
            Object value=row.getObject(i);if(value instanceof Timestamp timestamp)value=timestamp.toInstant().toString();result.put(camel.toString(),value);
        }return result;
    }
}
