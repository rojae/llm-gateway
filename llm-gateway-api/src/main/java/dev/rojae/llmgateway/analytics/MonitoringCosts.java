package dev.rojae.llmgateway.analytics;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/** Coherent retained-record allocation. Values are provisional as records arrive or expire. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class MonitoringCosts {
    private final AnalyticsDatabase database;private final AuditService audit;
    public MonitoringCosts(AnalyticsDatabase database,AuditService audit){this.database=database;this.audit=audit;}
    public record Subscription(BigDecimal amount,String currency,String allocationBasis,long questionCount,long sessionCount,BigDecimal perQuestion){}
    public record Observation(UUID id,String emittedAt,String source,String model,Long inputTokens,Long outputTokens,Long cacheReadTokens,Long cacheWriteTokens,MonitoringCostEstimator.Estimate apiEstimate){}
    public record Session(UUID id,long questionCount,BigDecimal subscriptionAllocation,Observation latestObservation){}
    public record Question(UUID id,boolean inMonth,BigDecimal subscriptionAllocation){}
    public record Report(String month,String zone,Subscription subscription,Session session,Question question){}
    public record Setting(String month,BigDecimal amount,String currency){}
    public record Filter(YearMonth month,ZoneId zone,UUID session,UUID question){}
    public static Filter parse(MultiValueMap<String,String> params){
        for(var p:params.entrySet())if(!Set.of("month","zone","sessionId","questionId").contains(p.getKey())||p.getValue().size()!=1)throw new IllegalArgumentException("Invalid costs filter");
        YearMonth month=month(params.getFirst("month"));String zone=params.getFirst("zone");
        if(zone==null||!ZoneId.getAvailableZoneIds().contains(zone))throw new IllegalArgumentException("Invalid zone");
        UUID session=uuid(params.getFirst("sessionId")),question=uuid(params.getFirst("questionId"));
        if(question!=null&&session==null)throw new IllegalArgumentException("Question requires session");
        return new Filter(month,ZoneId.of(zone),session,question);
    }
    static YearMonth month(String value){if(value==null||!value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException("Invalid month");YearMonth parsed=YearMonth.parse(value);if(parsed.getYear()<2000||parsed.getYear()>2100)throw new IllegalArgumentException("Invalid month");return parsed;}
    private static UUID uuid(String value){if(value==null)return null;UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException("Invalid id");return id;}
    public Setting configure(String tenant,UUID account,String actor,String month,BigDecimal amount,String currency)throws SQLException{
        AuditService.validateTenant(tenant);YearMonth period=month(month);
        if(!Set.of("USD","KRW").contains(currency)||amount!=null&&(amount.signum()<0||amount.compareTo(new BigDecimal("1000000000"))>0||amount.scale()>6))throw new IllegalArgumentException("Invalid subscription amount");
        try(var c=database.open()){c.setAutoCommit(false);try{
            require(c,"SELECT id FROM llm_gateway_analytics.monitoring_accounts WHERE tenant_id=? AND id=?",tenant,account);
            try(var q=c.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_subscription_costs(tenant_id,account_id,month,amount,currency) VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,account_id,month) DO UPDATE SET amount=excluded.amount,currency=excluded.currency")){
                bind(q,tenant,account,java.sql.Date.valueOf(period.atDay(1)),amount,currency);q.executeUpdate();
            }
            audit.record(c,tenant,actor,"monitoring.cost.configure",account+":"+month,"success");c.commit();return new Setting(month,amount,currency);
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    public Report report(String tenant,UUID account,Filter f)throws SQLException{
        AuditService.validateTenant(tenant);
        Timestamp start=Timestamp.from(f.month().atDay(1).atStartOfDay(f.zone()).toInstant()),end=Timestamp.from(f.month().plusMonths(1).atDay(1).atStartOfDay(f.zone()).toInstant());
        try(var c=database.open()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);try{
            require(c,"SELECT id FROM llm_gateway_analytics.monitoring_accounts WHERE tenant_id=? AND id=?",tenant,account);
            if(f.session()!=null)require(c,"SELECT id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND account_id=? AND id=? AND purged_at IS NULL",tenant,account,f.session());
            Instant questionTime=null;
            if(f.question()!=null){try(var q=c.prepareStatement("SELECT q.started_at FROM llm_gateway_analytics.monitoring_questions q JOIN llm_gateway_analytics.monitoring_sessions s ON s.tenant_id=q.tenant_id AND s.id=q.session_id WHERE q.tenant_id=? AND s.account_id=? AND q.session_id=? AND q.id=? AND q.purged_at IS NULL AND s.purged_at IS NULL")){bind(q,tenant,account,f.session(),f.question());try(var r=q.executeQuery()){if(!r.next())throw new NoSuchElementException();questionTime=r.getTimestamp(1).toInstant();}}}
            BigDecimal amount=null;String currency="USD";
            try(var q=c.prepareStatement("SELECT amount,currency FROM llm_gateway_analytics.monitoring_subscription_costs WHERE tenant_id=? AND account_id=? AND month=?")){bind(q,tenant,account,java.sql.Date.valueOf(f.month().atDay(1)));try(var r=q.executeQuery()){if(r.next()){amount=r.getBigDecimal(1);currency=r.getString(2);}}}
            String counts="FROM llm_gateway_analytics.monitoring_questions q JOIN llm_gateway_analytics.monitoring_sessions s ON s.tenant_id=q.tenant_id AND s.id=q.session_id WHERE q.tenant_id=? AND s.account_id=? AND s.purged_at IS NULL AND q.purged_at IS NULL AND q.started_at>=? AND q.started_at<?";
            long questions,sessions;
            try(var q=c.prepareStatement("SELECT count(*),count(DISTINCT q.session_id) "+counts)){bind(q,tenant,account,start,end);try(var r=q.executeQuery()){r.next();questions=r.getLong(1);sessions=r.getLong(2);}}
            BigDecimal perQuestion=allocation(amount,1,questions);Session session=null;Question question=null;
            if(f.session()!=null){long count;try(var q=c.prepareStatement("SELECT count(*) "+counts+" AND q.session_id=?")){bind(q,tenant,account,start,end,f.session());try(var r=q.executeQuery()){r.next();count=r.getLong(1);}}
                session=new Session(f.session(),count,allocation(amount,count,questions),latest(c,tenant,account,f.session(),start,end));}
            if(f.question()!=null){boolean inMonth=!questionTime.isBefore(start.toInstant())&&questionTime.isBefore(end.toInstant());question=new Question(f.question(),inMonth,inMonth?perQuestion:null);}
            c.commit();return new Report(f.month().toString(),f.zone().getId(),new Subscription(amount,currency,"recorded_questions",questions,sessions,perQuestion),session,question);
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    private static Observation latest(Connection c,String tenant,UUID account,UUID session,Timestamp start,Timestamp end)throws SQLException{
        try(var q=c.prepareStatement("""
          SELECT e.id,e.emitted_at,e.source,e.model,e.input_tokens,e.output_tokens,e.cache_read_tokens,e.cache_write_tokens,e.api_equivalent_cost_usd
          FROM llm_gateway_analytics.monitoring_events e WHERE e.tenant_id=? AND e.account_id=? AND e.session_id=? AND e.emitted_at>=? AND e.emitted_at<?
          AND ((e.source='codex' AND e.event_kind='response.completed') OR (e.source='claude_code' AND e.event_kind='api_request'))
          AND (e.input_tokens IS NOT NULL OR e.output_tokens IS NOT NULL OR e.cache_read_tokens IS NOT NULL OR e.cache_write_tokens IS NOT NULL OR e.api_equivalent_cost_usd IS NOT NULL)
          AND (e.question_id IS NULL OR EXISTS(SELECT 1 FROM llm_gateway_analytics.monitoring_questions p WHERE p.tenant_id=e.tenant_id AND p.id=e.question_id AND p.session_id=e.session_id AND p.purged_at IS NULL))
          ORDER BY e.emitted_at DESC,e.id DESC LIMIT 1
          """)){bind(q,tenant,account,session,start,end);try(var r=q.executeQuery()){if(!r.next())return null;
            String source=r.getString(3),model=r.getString(4);Long input=nullableLong(r,5),output=nullableLong(r,6),read=nullableLong(r,7),write=nullableLong(r,8);
            return new Observation(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant().toString(),source,model,input,output,read,write,MonitoringCostEstimator.estimate(source,model,input,output,read,write,r.getBigDecimal(9)));}}
    }
    private static Long nullableLong(ResultSet r,int i)throws SQLException{long value=r.getLong(i);return r.wasNull()?null:value;}
    private static BigDecimal allocation(BigDecimal amount,long numerator,long denominator){return amount==null||denominator==0?null:amount.multiply(BigDecimal.valueOf(numerator)).divide(BigDecimal.valueOf(denominator),12,RoundingMode.HALF_UP).setScale(6,RoundingMode.HALF_UP);}
    private static void require(Connection c,String sql,Object... values)throws SQLException{try(var q=c.prepareStatement(sql)){bind(q,values);try(var r=q.executeQuery()){if(!r.next())throw new NoSuchElementException();}}}
    private static void bind(PreparedStatement q,Object... values)throws SQLException{for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);}
}
