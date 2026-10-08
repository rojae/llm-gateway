package dev.rojae.llmgateway.analytics;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Component
@ConfigurationProperties("gateway.telemetry")
public class TelemetryProperties {
    private boolean enabled;
    private String token="",billingMode="subscription_declared";
    private int maxBodyBytes=2097152,maxRecords=1000;
    @jakarta.annotation.PostConstruct
    public void validate(){
        if(!enabled)return;

        if(!java.util.Set.of("subscription_declared","api_key_declared","unknown").contains(billingMode))throw new IllegalArgumentException("Invalid telemetry billing declaration");
        if(maxBodyBytes<256||maxBodyBytes>2097152||maxRecords<1||maxRecords>1000)throw new IllegalArgumentException("Invalid telemetry payload limits");
    }
    public void validateLocalToken(){
        validate();
        if(token==null||token.length()<32||token.length()>4096||token.codePoints().anyMatch(Character::isWhitespace)||token.toLowerCase(java.util.Locale.ROOT).startsWith("sk-"))throw new IllegalArgumentException("Telemetry requires a separate collector token of at least 32 characters");
    }
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getToken(){return token;} public void setToken(String v){token=v;}
    public String getBillingMode(){return billingMode;} public void setBillingMode(String v){billingMode=v;}
    public int getMaxBodyBytes(){return maxBodyBytes;} public void setMaxBodyBytes(int v){maxBodyBytes=v;}
    public int getMaxRecords(){return maxRecords;} public void setMaxRecords(int v){maxRecords=v;}
}
