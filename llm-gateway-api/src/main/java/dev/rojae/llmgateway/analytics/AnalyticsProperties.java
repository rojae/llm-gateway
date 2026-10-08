package dev.rojae.llmgateway.analytics;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Component
@ConfigurationProperties("gateway.analytics")
public class AnalyticsProperties {
    private boolean enabled;
    private String jdbcUrl, username, password, adminToken;
    private String sourceId="local", tenantId="local", legacyTenantId, accessLog="var/access.log", contentLog="var/content.log";
    @org.springframework.beans.factory.annotation.Value("${gateway.security.mode:local}")
    private String securityMode="local";
    private Duration pollInterval=Duration.ofSeconds(2);
    private Duration connectionTimeout=Duration.ofSeconds(3);
    private int batchSize=250, maxLineBytes=1048576, maxPoolSize=8, requestRetentionDays, contentRetentionDays;
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!enabled) return;
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Analytics requires a PostgreSQL JDBC URL");
        if ("local".equals(securityMode) && (adminToken == null || adminToken.length() < 32)) throw new IllegalArgumentException("Analytics requires an operator token of at least 32 characters");
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("Invalid analytics source identifier");
        if (!validTenant(tenantId) || (legacyTenantId != null && !validTenant(legacyTenantId))) throw new IllegalArgumentException("Invalid analytics tenant identifier");
        if (accessLog == null || accessLog.isBlank() || contentLog == null || contentLog.isBlank()) throw new IllegalArgumentException("Analytics log paths are required");
        if (batchSize < 1 || batchSize > 1000 || maxLineBytes < 256 || maxLineBytes > 4194304) throw new IllegalArgumentException("Invalid analytics batch or line limits");
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(100)) < 0 || pollInterval.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("Invalid analytics polling interval");
        if (maxPoolSize < 2 || maxPoolSize > 32 || connectionTimeout == null || connectionTimeout.compareTo(Duration.ofMillis(250)) < 0 || connectionTimeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid analytics pool limits");
        if (requestRetentionDays < 0 || contentRetentionDays < 0 || requestRetentionDays > 36500 || contentRetentionDays > 36500) throw new IllegalArgumentException("Invalid analytics retention");
    }
    private static boolean validTenant(String value){return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getJdbcUrl(){return jdbcUrl;} public void setJdbcUrl(String v){jdbcUrl=v;}
    public String getUsername(){return username;} public void setUsername(String v){username=v;}
    public String getPassword(){return password;} public void setPassword(String v){password=v;}
    public String getAdminToken(){return adminToken;} public void setAdminToken(String v){adminToken=v;}
    public String getSourceId(){return sourceId;} public void setSourceId(String v){sourceId=v;}
    public String getTenantId(){return tenantId;} public void setTenantId(String v){tenantId=v;}
    public String getLegacyTenantId(){return legacyTenantId;} public void setLegacyTenantId(String v){legacyTenantId=v==null||v.isBlank()?null:v;}
    public String getAccessLog(){return accessLog;} public void setAccessLog(String v){accessLog=v;}
    public String getContentLog(){return contentLog;} public void setContentLog(String v){contentLog=v;}
    public Duration getPollInterval(){return pollInterval;} public void setPollInterval(Duration v){pollInterval=v;}
    public int getBatchSize(){return batchSize;} public void setBatchSize(int v){batchSize=v;}
    public int getMaxLineBytes(){return maxLineBytes;} public void setMaxLineBytes(int v){maxLineBytes=v;}
    public int getMaxPoolSize(){return maxPoolSize;} public void setMaxPoolSize(int v){maxPoolSize=v;}
    public Duration getConnectionTimeout(){return connectionTimeout;} public void setConnectionTimeout(Duration v){connectionTimeout=v;}
    public int getRequestRetentionDays(){return requestRetentionDays;} public void setRequestRetentionDays(int v){requestRetentionDays=v;}
    public int getContentRetentionDays(){return contentRetentionDays;} public void setContentRetentionDays(int v){contentRetentionDays=v;}
}
