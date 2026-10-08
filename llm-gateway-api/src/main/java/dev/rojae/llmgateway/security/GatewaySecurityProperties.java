package dev.rojae.llmgateway.security;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("gateway.security")
public class GatewaySecurityProperties {
    private String mode="local",issuerUri="",audience="",clientId="",clientSecret="",tenantClaim="tenant_id",rolesClaim="roles",externalBaseUrl="",adminUiUrl="/admin/";
    private List<String> allowedTenants=List.of();
    private boolean allowInsecureLocalOidc;
    @PostConstruct public void validate() {
        if(!Set.of("local","enterprise").contains(mode))throw new IllegalArgumentException("Invalid security mode");
        validateAdminUiUrl();
        if(!isEnterprise())return;
        if(audience.isBlank()||clientId.isBlank()||clientSecret.isBlank()||tenantClaim.isBlank()||rolesClaim.isBlank()
                ||allowedTenants.isEmpty()||allowedTenants.stream().anyMatch(t->!t.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            throw new IllegalArgumentException("Enterprise identity configuration is incomplete");
        validateUrl(issuerUri);validateUrl(externalBaseUrl);
    }
    private void validateAdminUiUrl() {
        try {
            URI uri=URI.create(adminUiUrl);
            if(uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null)throw new IllegalArgumentException();
            if(!uri.isAbsolute()) {
                if(!adminUiUrl.startsWith("/")||adminUiUrl.startsWith("//")||uri.getRawAuthority()!=null)throw new IllegalArgumentException();
                return;
            }
            boolean loopbackHttp="http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
            if(uri.getHost()==null||(!"https".equals(uri.getScheme())&&!loopbackHttp))throw new IllegalArgumentException();
        } catch(Exception ignored){throw new IllegalArgumentException("Admin UI URL must be an absolute path, HTTPS URL, or loopback HTTP URL");}
    }
    private void validateUrl(String value) {
        try {
            URI uri=URI.create(value);
            boolean local=allowInsecureLocalOidc && "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
            if(uri.getHost()==null||(!"https".equals(uri.getScheme())&&!local)||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
        } catch(Exception ignored){throw new IllegalArgumentException("Enterprise identity URLs must use HTTPS");}
    }
    public boolean isEnterprise(){return "enterprise".equals(mode);}
    public String getMode(){return mode;}public void setMode(String v){mode=v;}
    public String getIssuerUri(){return issuerUri;}public void setIssuerUri(String v){issuerUri=v;}
    public String getAudience(){return audience;}public void setAudience(String v){audience=v;}
    public String getClientId(){return clientId;}public void setClientId(String v){clientId=v;}
    public String getClientSecret(){return clientSecret;}public void setClientSecret(String v){clientSecret=v;}
    public String getTenantClaim(){return tenantClaim;}public void setTenantClaim(String v){tenantClaim=v;}
    public String getRolesClaim(){return rolesClaim;}public void setRolesClaim(String v){rolesClaim=v;}
    public List<String> getAllowedTenants(){return allowedTenants;}public void setAllowedTenants(List<String> v){allowedTenants=List.copyOf(v);}
    public String getExternalBaseUrl(){return externalBaseUrl;}public void setExternalBaseUrl(String v){externalBaseUrl=v;}
    public String getAdminUiUrl(){return adminUiUrl;}public void setAdminUiUrl(String v){adminUiUrl=v;}
    public boolean isAllowInsecureLocalOidc(){return allowInsecureLocalOidc;}public void setAllowInsecureLocalOidc(boolean v){allowInsecureLocalOidc=v;}
}
