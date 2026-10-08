package dev.rojae.llmgateway.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Random collector secrets are only returned at creation; persistent state contains their hashes. */
@Component
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public class CollectorCredentialService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String COLUMNS="id,name,prefix,billing_mode,created_at,created_by,expires_at,revoked_at,last_used_at,owner_account_id,registration_id";
    private final AnalyticsDatabase database;
    private final AuditService audit;
    public record Credential(UUID id,String name,String prefix,String billingMode,Instant createdAt,String createdBy,Instant expiresAt,Instant revokedAt,Instant lastUsedAt,UUID ownerAccountId,UUID registrationId){}
    public record Created(Credential credential,String token){@Override public String toString(){return "Created[credential="+credential+",token=[REDACTED]]";}}
    public record Identity(UUID credentialId,String tenantId,String billingMode,UUID ownerAccountId,UUID registrationId){
        public Identity(UUID credentialId,String tenantId,String billingMode){this(credentialId,tenantId,billingMode,null,null);}
    }
    public record Page(List<Credential> items,long total,int limit,int offset){}
    public CollectorCredentialService(AnalyticsDatabase database,AuditService audit){this.database=database;this.audit=audit;}

    public Created create(String tenant,String actor,String name,int expiresInDays,String billingMode)throws SQLException{
        return create(tenant,actor,name,expiresInDays,billingMode,null,null);
    }
    public Created create(String tenant,String actor,String name,int expiresInDays,String billingMode,UUID ownerAccountId,UUID registrationId)throws SQLException{
        AuditService.validateTenant(tenant);
        if(ownerAccountId==null&&registrationId!=null)throw new IllegalArgumentException("Account required");
        if(ownerAccountId!=null&&registrationId==null)registrationId=UUID.randomUUID();
        if(name==null||name.isBlank()||name.length()>80||name.codePoints().anyMatch(Character::isISOControl)||expiresInDays<1||expiresInDays>365||billingMode==null||!Set.of("subscription_declared","api_key_declared","unknown").contains(billingMode))throw new IllegalArgumentException("Invalid collector configuration");
        UUID id=UUID.randomUUID();byte[] random=new byte[32];RANDOM.nextBytes(random);
        String token="llmgw_col_"+id.toString().replace("-","")+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String prefix=token.substring(0,16);
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            try{
                if(ownerAccountId!=null){
                    try(var check=connection.prepareStatement("SELECT id FROM llm_gateway_analytics.monitoring_accounts WHERE tenant_id=? AND id=?")){
                        check.setString(1,tenant);check.setObject(2,ownerAccountId);try(var result=check.executeQuery()){if(!result.next())throw new IllegalArgumentException("Invalid account");}
                    }
                    try(var register=connection.prepareStatement("INSERT INTO llm_gateway_analytics.monitoring_registrations(tenant_id,id,account_id) VALUES(?,?,?) ON CONFLICT DO NOTHING")){
                        register.setString(1,tenant);register.setObject(2,registrationId);register.setObject(3,ownerAccountId);register.executeUpdate();
                    }
                    try(var check=connection.prepareStatement("SELECT account_id FROM llm_gateway_analytics.monitoring_registrations WHERE tenant_id=? AND id=?")){
                        check.setString(1,tenant);check.setObject(2,registrationId);try(var result=check.executeQuery()){while(result.next())if(!ownerAccountId.equals(result.getObject(1,UUID.class)))throw new IllegalArgumentException("Invalid registration");}
                    }
                }
                Credential credential;
                try(var query=connection.prepareStatement("INSERT INTO llm_gateway_analytics.collector_credentials (id,tenant_id,name,token_hash,prefix,billing_mode,created_by,expires_at,owner_account_id,registration_id) VALUES (?,?,?,?,?,?,?,now()+(? * interval '1 day'),?,?) RETURNING "+COLUMNS)){
                    query.setObject(1,id);query.setString(2,tenant);query.setString(3,name.strip());query.setString(4,hash(token));query.setString(5,prefix);query.setString(6,billingMode);query.setString(7,actor);query.setInt(8,expiresInDays);query.setObject(9,ownerAccountId);query.setObject(10,registrationId);
                    try(var result=query.executeQuery()){result.next();credential=credential(result);}
                }
                audit.record(connection,tenant,actor,"collector.create",id.toString(),"success");
                connection.commit();return new Created(credential,token);
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    public List<Credential> list(String tenant)throws SQLException{return list(tenant,100,0).items();}
    public Page list(String tenant,int limit,int offset)throws SQLException{
        AuditService.validateTenant(tenant);
        if(limit<1||limit>100||offset<0||offset>100000)throw new IllegalArgumentException("Invalid collector pagination");
        List<Credential> credentials=new ArrayList<>();long total;
        try(var connection=database.open()){
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.collector_credentials WHERE tenant_id=?")){
                query.setString(1,tenant);try(var result=query.executeQuery()){result.next();total=result.getLong(1);}
            }
            try(var query=connection.prepareStatement("SELECT "+COLUMNS+" FROM llm_gateway_analytics.collector_credentials WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")){
                query.setString(1,tenant);query.setInt(2,limit);query.setInt(3,offset);try(var result=query.executeQuery()){while(result.next())credentials.add(credential(result));}
            }
            connection.commit();
        }return new Page(List.copyOf(credentials),total,limit,offset);
    }
    public void revoke(String tenant,String actor,UUID id)throws SQLException{
        AuditService.validateTenant(tenant);
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            try{
                int changed;
                try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET revoked_at=COALESCE(revoked_at,now()) WHERE tenant_id=? AND id=?")){
                    query.setString(1,tenant);query.setObject(2,id);changed=query.executeUpdate();
                }
                // A missing key and a different tenant's key have the same response and audit outcome.
                audit.record(connection,tenant,actor,"collector.revoke",id.toString(),changed==0?"denied":"success");
                connection.commit();
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    public Optional<Identity> authenticate(String token)throws SQLException{
        // Existing collector credentials remain valid after the product rename.
        if(token==null||!token.matches("(?:llmgw_col_|jev_col_)[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}"))return Optional.empty();
        try(var connection=database.open()){
            connection.setAutoCommit(false);
            try{
                Identity identity;boolean valid;
                try(var query=connection.prepareStatement("SELECT id,tenant_id,billing_mode,owner_account_id,registration_id,(revoked_at IS NULL AND expires_at>now()) AS valid FROM llm_gateway_analytics.collector_credentials WHERE token_hash=? FOR UPDATE")){
                    query.setString(1,hash(token));try(var result=query.executeQuery()){
                        if(!result.next()){connection.rollback();return Optional.empty();}
                        identity=new Identity(result.getObject(1,UUID.class),result.getString(2),result.getString(3),result.getObject(4,UUID.class),result.getObject(5,UUID.class));valid=result.getBoolean(6);
                    }
                }
                if(!valid){audit.record(connection,identity.tenantId(),"collector:"+identity.credentialId(),"collector.authenticate",identity.credentialId().toString(),"denied");connection.commit();return Optional.empty();}
                try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET last_used_at=now() WHERE id=?")){query.setObject(1,identity.credentialId());query.executeUpdate();}
                connection.commit();return Optional.of(identity);
            }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        }
    }
    public boolean stillAuthorized(Connection connection,Identity identity)throws SQLException{
        try(var query=connection.prepareStatement("SELECT id FROM llm_gateway_analytics.collector_credentials WHERE id=? AND tenant_id=? AND billing_mode=? AND owner_account_id IS NOT DISTINCT FROM ? AND registration_id IS NOT DISTINCT FROM ? AND revoked_at IS NULL AND expires_at>now() FOR SHARE")){
            query.setObject(1,identity.credentialId());query.setString(2,identity.tenantId());query.setString(3,identity.billingMode());query.setObject(4,identity.ownerAccountId());query.setObject(5,identity.registrationId());try(var result=query.executeQuery()){return result.next();}
        }
    }
    private static Credential credential(ResultSet result)throws SQLException{
        return new Credential(result.getObject("id",UUID.class),result.getString("name"),result.getString("prefix"),result.getString("billing_mode"),time(result,"created_at"),result.getString("created_by"),time(result,"expires_at"),time(result,"revoked_at"),time(result,"last_used_at"),result.getObject("owner_account_id",UUID.class),result.getObject("registration_id",UUID.class));
    }
    private static Instant time(ResultSet result,String name)throws SQLException{var value=result.getTimestamp(name);return value==null?null:value.toInstant();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("hash_unavailable");}}
}
