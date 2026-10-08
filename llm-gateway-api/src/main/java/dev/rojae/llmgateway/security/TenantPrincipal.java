package dev.rojae.llmgateway.security;

import java.io.Serializable;
import java.util.Set;

public record TenantPrincipal(String tenantId,String subject,Set<String> roles) implements Serializable {
    public TenantPrincipal { roles=Set.copyOf(roles); }
    public Set<String> permissions() {
        if(roles.contains("admin"))return Set.of("usage:read","content:read","ingestion:read","collectors:manage","audit:read");
        if(roles.contains("operator"))return Set.of("usage:read","content:read","ingestion:read");
        if(roles.contains("viewer"))return Set.of("usage:read");
        return Set.of();
    }
    public boolean hasPermission(String permission){return permissions().contains(permission);}
}
