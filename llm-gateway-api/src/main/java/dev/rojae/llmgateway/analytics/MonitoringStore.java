package dev.rojae.llmgateway.analytics;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Runs inside the caller's acknowledgement transaction; all identities are scoped to trusted owner. */
final class MonitoringStore {
    static boolean insert(Connection connection,CollectorCredentialService.Identity owner,MonitoringNormalizer.Event event)throws SQLException{
        String tenant=owner.tenantId();UUID account=owner.ownerAccountId();
        try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_event_identities(tenant_id,account_id,source,identity) VALUES(?,?,?,?) ON CONFLICT DO NOTHING")){
            query.setString(1,tenant);query.setObject(2,account);query.setString(3,event.source());query.setString(4,event.identity());if(query.executeUpdate()==0)return false;
        }
        UUID session=null,question=null;
        if(event.session()!=null){
            session=id(tenant+"|"+account+"|"+event.source()+"|"+event.session());
            try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_sessions(tenant_id,id,account_id,source,external_session_id,cli_version,first_seen_at,last_seen_at,provider_account_hash,provider_account_status) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,id) DO UPDATE SET first_seen_at=LEAST(monitoring_sessions.first_seen_at,EXCLUDED.first_seen_at),last_seen_at=GREATEST(monitoring_sessions.last_seen_at,EXCLUDED.last_seen_at),cli_version=COALESCE(EXCLUDED.cli_version,monitoring_sessions.cli_version),provider_account_hash=COALESCE(monitoring_sessions.provider_account_hash,EXCLUDED.provider_account_hash),provider_account_status=CASE WHEN monitoring_sessions.provider_account_status='conflict' THEN 'conflict' WHEN monitoring_sessions.provider_account_hash IS NOT NULL AND EXCLUDED.provider_account_hash IS NOT NULL AND monitoring_sessions.provider_account_hash<>EXCLUDED.provider_account_hash THEN 'conflict' WHEN COALESCE(monitoring_sessions.provider_account_hash,EXCLUDED.provider_account_hash) IS NOT NULL THEN 'observed' ELSE 'unknown' END RETURNING purged_at")){
                query.setString(1,tenant);query.setObject(2,session);query.setObject(3,account);query.setString(4,event.source());query.setString(5,event.session());query.setString(6,event.cliVersion());query.setTimestamp(7,Timestamp.from(event.emittedAt()));query.setTimestamp(8,Timestamp.from(event.emittedAt()));query.setString(9,event.providerAccountHash()==null?null:MonitoringNormalizer.hash(tenant+"|"+account+"|"+event.providerAccountHash()));query.setString(10,event.providerAccountHash()==null?"unknown":"observed");
                try(var result=query.executeQuery()){result.next();if(result.getTimestamp(1)!=null)return false;}
            }
            if(event.question()||event.promptId()!=null){
                String questionIdentity=event.promptId()==null?"event:"+event.identity():"prompt:"+event.promptId();
                question=id(tenant+"|"+session+"|"+questionIdentity);
                try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_questions(tenant_id,id,session_id,external_prompt_id,question_identity,started_at,source,model) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,id) DO UPDATE SET started_at=LEAST(monitoring_questions.started_at,EXCLUDED.started_at),model=COALESCE(monitoring_questions.model,EXCLUDED.model) RETURNING purged_at,content_purged_at")){
                    query.setString(1,tenant);query.setObject(2,question);query.setObject(3,session);query.setString(4,event.promptId());query.setString(5,questionIdentity);query.setTimestamp(6,Timestamp.from(event.emittedAt()));query.setString(7,event.source());query.setString(8,event.model());
                    try(var result=query.executeQuery()){
                        result.next();if(result.getTimestamp(1)!=null)return false;
                        if(event.content()!=null&&result.getTimestamp(2)==null)try(var content=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_question_contents(tenant_id,question_id,text,truncated) VALUES(?,?,?,?) ON CONFLICT DO NOTHING")){
                            content.setString(1,tenant);content.setObject(2,question);content.setString(3,event.content());content.setBoolean(4,event.truncated());content.executeUpdate();
                        }
                    }
                }
            }
        }
        try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_events(tenant_id,id,session_id,question_id,account_id,source,event_kind,emitted_at,cli_version,request_identity,external_prompt_id,model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,total_tokens,api_equivalent_cost_usd,scope,semantics,attribution,usage_availability,supported) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            Object[] values={tenant,id(tenant+"|"+account+"|"+event.source()+"|"+event.identity()),session,question,account,event.source(),event.kind(),Timestamp.from(event.emittedAt()),event.cliVersion(),event.requestId(),event.promptId(),event.model(),event.input(),event.output(),event.cacheRead(),event.cacheWrite(),event.total(),event.cost(),event.scope(),event.semantics(),event.attribution(),event.availability(),event.supported()};
            for(int i=0;i<values.length;i++)query.setObject(i+1,values[i]);query.executeUpdate();
        }
        return true;
    }
    static void status(Connection connection,String tenant,int stored,int duplicate,int rejected,int unsupported,int unattributed)throws SQLException{
        try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_status(tenant_id,last_received_at,stored,duplicates,rejected,unsupported,unattributed) VALUES(?,CASE WHEN ?>0 THEN now() ELSE NULL END,?,?,?,?,?) ON CONFLICT(tenant_id) DO UPDATE SET last_received_at=CASE WHEN EXCLUDED.stored+EXCLUDED.duplicates>0 THEN now() ELSE monitoring_status.last_received_at END,stored=monitoring_status.stored+EXCLUDED.stored,duplicates=monitoring_status.duplicates+EXCLUDED.duplicates,rejected=monitoring_status.rejected+EXCLUDED.rejected,unsupported=monitoring_status.unsupported+EXCLUDED.unsupported,unattributed=monitoring_status.unattributed+EXCLUDED.unattributed")){
            query.setString(1,tenant);query.setInt(2,stored+duplicate);query.setInt(3,stored);query.setInt(4,duplicate);query.setInt(5,rejected);query.setInt(6,unsupported);query.setInt(7,unattributed);query.executeUpdate();
        }
    }
    static UUID id(String identity){return UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static void prune(Connection connection,String tenant,int requestDays,int contentDays)throws SQLException{
        if(contentDays>0){
            Timestamp before=Timestamp.from(Instant.now().minus(Duration.ofDays(contentDays)));
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_questions SET content_purged_at=now() WHERE tenant_id=? AND id IN(SELECT question_id FROM llm_gateway_analytics.monitoring_question_contents WHERE tenant_id=? AND received_at<? ORDER BY received_at LIMIT 250)")){
                query.setString(1,tenant);query.setString(2,tenant);query.setTimestamp(3,before);query.executeUpdate();
            }
            try(var query=connection.prepareStatement("DELETE FROM llm_gateway_analytics.monitoring_question_contents WHERE tenant_id=? AND question_id IN(SELECT c.question_id FROM llm_gateway_analytics.monitoring_question_contents c JOIN llm_gateway_analytics.monitoring_questions q ON q.tenant_id=c.tenant_id AND q.id=c.question_id WHERE c.tenant_id=? AND q.content_purged_at IS NOT NULL ORDER BY c.received_at LIMIT 250 FOR UPDATE OF c SKIP LOCKED)")){query.setString(1,tenant);query.setString(2,tenant);query.executeUpdate();}
        }
        if(requestDays>0){
            Timestamp before=Timestamp.from(Instant.now().minus(Duration.ofDays(requestDays)));
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET purged_at=now() WHERE tenant_id=? AND id IN(SELECT id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND purged_at IS NULL AND last_seen_at<? ORDER BY last_seen_at LIMIT 250 FOR UPDATE SKIP LOCKED)")){query.setString(1,tenant);query.setString(2,tenant);query.setTimestamp(3,before);query.executeUpdate();}
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_questions SET purged_at=now(),content_purged_at=now() WHERE tenant_id=? AND id IN(SELECT q.id FROM llm_gateway_analytics.monitoring_questions q JOIN llm_gateway_analytics.monitoring_sessions s ON s.tenant_id=q.tenant_id AND s.id=q.session_id WHERE q.tenant_id=? AND q.purged_at IS NULL AND (q.received_at<? OR s.purged_at IS NOT NULL) ORDER BY q.received_at LIMIT 250 FOR UPDATE OF q SKIP LOCKED)")){query.setString(1,tenant);query.setString(2,tenant);query.setTimestamp(3,before);query.executeUpdate();}
            try(var query=connection.prepareStatement("DELETE FROM llm_gateway_analytics.monitoring_question_contents WHERE tenant_id=? AND question_id IN(SELECT c.question_id FROM llm_gateway_analytics.monitoring_question_contents c JOIN llm_gateway_analytics.monitoring_questions q ON q.tenant_id=c.tenant_id AND q.id=c.question_id WHERE c.tenant_id=? AND q.purged_at IS NOT NULL ORDER BY c.received_at LIMIT 250 FOR UPDATE OF c SKIP LOCKED)")){query.setString(1,tenant);query.setString(2,tenant);query.executeUpdate();}
            // Native orphan events are aged independently; identities survive all payload deletions.
            try(var query=connection.prepareStatement("DELETE FROM llm_gateway_analytics.monitoring_events WHERE tenant_id=? AND id IN(SELECT id FROM llm_gateway_analytics.monitoring_events WHERE tenant_id=? AND (received_at<? OR question_id IN(SELECT id FROM llm_gateway_analytics.monitoring_questions WHERE tenant_id=? AND purged_at IS NOT NULL) OR session_id IN(SELECT id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND purged_at IS NOT NULL)) ORDER BY received_at LIMIT 250)")){
                query.setString(1,tenant);query.setString(2,tenant);query.setTimestamp(3,before);query.setString(4,tenant);query.setString(5,tenant);query.executeUpdate();
            }
        }
    }
}
