package dev.rojae.llmgateway.security;

import java.util.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
public final class TenantClaims {
    private final GatewaySecurityProperties properties;
    public TenantClaims(GatewaySecurityProperties properties){this.properties=properties;}
    public TenantPrincipal resolve(Map<String,Object> claims) {
        Object tenant=claims.get(properties.getTenantClaim()),subject=claims.get("sub"),roles=claims.get(properties.getRolesClaim());
        if(!(tenant instanceof String tenantId)||!properties.getAllowedTenants().contains(tenantId)
                ||!(subject instanceof String user)||user.isBlank()||user.length()>512||user.codePoints().anyMatch(Character::isISOControl)
                ||!(roles instanceof Collection<?> values)||values.isEmpty())throw invalid();
        Set<String> accepted=new HashSet<>();
        for(Object role:values){if(!(role instanceof String name)||!Set.of("viewer","operator","admin").contains(name))throw invalid();accepted.add(name);}
        return new TenantPrincipal(tenantId,user,accepted);
    }
    private static OAuth2AuthenticationException invalid(){return new OAuth2AuthenticationException(new OAuth2Error("invalid_token","Required tenant identity is unavailable",null));}
}
