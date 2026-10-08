package dev.rojae.llmgateway.security;

import dev.rojae.llmgateway.analytics.AuditService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Component
public final class AdminAccess {
    public static final String PRINCIPAL_ATTRIBUTE="dev.rojae.llmgateway.security.principal";
    private final GatewaySecurityProperties properties;
    private final TenantClaims claims;
    private final ObjectProvider<AuditService> audit;
    private final Scheduler scheduler=Schedulers.newBoundedElastic(2,100,"admin-audit");
    public AdminAccess(GatewaySecurityProperties properties,TenantClaims claims,ObjectProvider<AuditService> audit){this.properties=properties;this.claims=claims;this.audit=audit;}
    public Mono<TenantPrincipal> require(ServerWebExchange exchange,String permission) {
        Mono<TenantPrincipal> identity;
        if(!properties.isEnterprise()) identity=Mono.justOrEmpty((TenantPrincipal)exchange.getAttribute(PRINCIPAL_ATTRIBUTE));
        else identity=exchange.getPrincipal().flatMap(authentication->{
            if(authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated())return Mono.fromCallable(()->claims.resolve(jwt.getToken().getClaims()));
            if(authentication instanceof OAuth2AuthenticationToken oauth && oauth.isAuthenticated() && oauth.getPrincipal() instanceof OidcUser user
                    && user.getIdToken().getExpiresAt()!=null && user.getIdToken().getExpiresAt().isAfter(Instant.now()))
                return Mono.fromCallable(()->claims.resolve(user.getIdToken().getClaims()));
            return Mono.empty();
        });
        return identity.onErrorMap(e->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required"))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required")))
                .flatMap(principal->{
                    if(principal.hasPermission(permission))return Mono.just(principal);
                    return Mono.fromCallable(()->{
                        AuditService service=audit.getIfAvailable();
                        if(service!=null)service.record(principal.tenantId(),principal.subject(),"access.denied",permission,"denied");
                        return principal;
                    }).subscribeOn(scheduler).onErrorMap(e->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Audit unavailable"))
                            .flatMap(ignored->Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"Permission denied")));
                });
    }
    @PreDestroy public void close(){scheduler.dispose();}
}
