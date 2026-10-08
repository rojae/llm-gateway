package dev.rojae.llmgateway.analytics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Append-only operator events. Callers must offload JDBC and propagate audit failures. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public class AuditService {
    private static final Set<String> ACTIONS=Set.of("collector.create","collector.revoke","collector.authenticate","content.read","access.denied","monitoring.account.create","monitoring.cost.configure");
    private static final Set<String> OUTCOMES=Set.of("success","denied","failure");
    private final AnalyticsDatabase database;
    public record Event(UUID id,Instant time,String actor,String action,String target,String outcome){}
    public record Page(List<Event> items,long total,int limit,int offset){}
    public AuditService(AnalyticsDatabase database){this.database=database;}

    public void record(String tenant,String actor,String action,String target,String outcome)throws SQLException{
        try(var connection=database.open()){record(connection,tenant,actor,action,target,outcome);}
    }
    public void record(Connection connection,String tenant,String actor,String action,String target,String outcome)throws SQLException{
        validateTenant(tenant);
        if(!ACTIONS.contains(action)||!OUTCOMES.contains(outcome))throw new IllegalArgumentException("Invalid audit classification");
        String safeActor=safeLabel(actor),safeTarget=safeLabel(target);
        try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.audit_events (id,tenant_id,actor,action,target,outcome) VALUES (?,?,?,?,?,?)")){
            query.setObject(1,UUID.randomUUID());query.setString(2,tenant);query.setString(3,safeActor);query.setString(4,action);query.setString(5,safeTarget);query.setString(6,outcome);query.executeUpdate();
        }
    }
    public Page list(String tenant,int limit,int offset)throws SQLException{
        validateTenant(tenant);
        if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("Invalid audit pagination");
        List<Event> events=new ArrayList<>();long total;
        try(var connection=database.open()){
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try(var count=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.audit_events WHERE tenant_id=?")){
                count.setString(1,tenant);try(var result=count.executeQuery()){result.next();total=result.getLong(1);}
            }
            try(var query=connection.prepareStatement("SELECT id,time,actor,action,target,outcome FROM llm_gateway_analytics.audit_events WHERE tenant_id=? ORDER BY time DESC,id DESC LIMIT ? OFFSET ?")){
                query.setString(1,tenant);query.setInt(2,limit);query.setInt(3,offset);
                try(var result=query.executeQuery()){while(result.next())events.add(new Event(result.getObject(1,UUID.class),result.getTimestamp(2).toInstant(),result.getString(3),result.getString(4),result.getString(5),result.getString(6)));}
            }
            connection.commit();
        }
        return new Page(List.copyOf(events),total,limit,offset);
    }
    static void validateTenant(String tenant){if(tenant==null||!tenant.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Invalid tenant identifier");}
    private static String safeLabel(String value){
        if(value==null||value.isBlank()||value.length()>512)throw new IllegalArgumentException("Invalid audit identity");
        return value.replaceAll("(?i)sk-[a-z0-9_-]+","[REDACTED]").replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [REDACTED]").replaceAll("(?:llmgw_col_|jev_col_)[A-Za-z0-9_.-]+","[REDACTED]").replaceAll("[\\p{Cc}\\p{Cf}]","?");
    }
}
