package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.security.GatewaySecurityProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Key lookup and tenant binding complete before any request body subscription. */
@Component
@Order(-110)
public final class TelemetryAuthFilter implements WebFilter {
    public static final String IDENTITY_ATTRIBUTE="llm.gateway.telemetry.identity";
    private static final PathPattern PATH=PathPatternParser.defaultInstance.parse("/telemetry/{*rest}");
    private final TelemetryProperties telemetry;
    private final AnalyticsProperties analytics;
    private final GatewaySecurityProperties security;
    private final CollectorCredentialService credentials;
    private final Scheduler worker=Schedulers.newBoundedElastic(2,32,"collector-auth");

    @Autowired
    public TelemetryAuthFilter(TelemetryProperties telemetry,AnalyticsProperties analytics,GatewayProperties gateway,GatewaySecurityProperties security,ObjectProvider<CollectorCredentialService> credentials){this(telemetry,analytics,gateway,security,credentials.getIfAvailable());}
    public TelemetryAuthFilter(TelemetryProperties telemetry,AnalyticsProperties analytics,GatewayProperties gateway){this(telemetry,analytics,gateway,new GatewaySecurityProperties(),(CollectorCredentialService)null);}
    public TelemetryAuthFilter(TelemetryProperties telemetry,AnalyticsProperties analytics,GatewayProperties gateway,GatewaySecurityProperties security,CollectorCredentialService credentials){
        this.telemetry=telemetry;this.analytics=analytics;this.security=security;this.credentials=credentials;
        if(security.isEnterprise()){
            if(telemetry.getToken()!=null&&!telemetry.getToken().isBlank())throw new IllegalArgumentException("Enterprise mode prohibits static collector tokens");
        }else if(telemetry.isEnabled()&&analytics.isEnabled()){
            telemetry.validateLocalToken();
            if(telemetry.getToken().equals(analytics.getAdminToken())||telemetry.getToken().equals(gateway.upstreamApiKey()))throw new IllegalArgumentException("Telemetry requires a separate collector token");
        }
    }
    @PreDestroy public void stop(){worker.dispose();}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        if(!PATH.matches(exchange.getRequest().getPath().pathWithinApplication()))return chain.filter(exchange);
        exchange.getResponse().getHeaders().setCacheControl("no-store");exchange.getResponse().getHeaders().set("X-Content-Type-Options","nosniff");
        if(!telemetry.isEnabled()||!analytics.isEnabled())return deny(exchange,HttpStatus.NOT_FOUND,"telemetry_unavailable");
        String token=bearer(exchange);
        if(token==null)return deny(exchange,HttpStatus.UNAUTHORIZED,"unauthorized");
        if(!security.isEnterprise()&&MessageDigest.isEqual(digest(telemetry.getToken()),digest(token))){
            exchange.getAttributes().put(IDENTITY_ATTRIBUTE,new CollectorCredentialService.Identity(null,analytics.getTenantId(),telemetry.getBillingMode()));
            return chain.filter(exchange);
        }
        if(credentials==null)return deny(exchange,HttpStatus.SERVICE_UNAVAILABLE,"telemetry_unavailable");
        return Mono.fromCallable(()->credentials.authenticate(token)).subscribeOn(worker)
                .flatMap(identity->{
                    if(identity.isEmpty())return deny(exchange,HttpStatus.UNAUTHORIZED,"unauthorized");
                    var bound=identity.get();
                    boolean allowed=security.isEnterprise()?security.getAllowedTenants().contains(bound.tenantId()):analytics.getTenantId().equals(bound.tenantId());
                    if(!allowed)return deny(exchange,HttpStatus.UNAUTHORIZED,"unauthorized");
                    exchange.getAttributes().put(IDENTITY_ATTRIBUTE,bound);return chain.filter(exchange);
                }).onErrorResume(failure->deny(exchange,HttpStatus.SERVICE_UNAVAILABLE,"telemetry_unavailable"));
    }
    private static String bearer(ServerWebExchange exchange){
        var headers=exchange.getRequest().getHeaders().get("Authorization");if(headers==null||headers.size()!=1)return null;
        String header=headers.getFirst();if(header.length()<=7||header.length()>4103||!header.regionMatches(true,0,"Bearer ",0,7))return null;
        String token=header.substring(7);return token.codePoints().anyMatch(Character::isWhitespace)?null:token;
    }
    private static byte[] digest(String input){try{return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));}catch(Exception impossible){throw new IllegalStateException("hash_unavailable");}}
    private static Mono<Void> deny(ServerWebExchange exchange,HttpStatus status,String code){
        exchange.getResponse().setStatusCode(status);exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes=("{\"code\":"+TelemetryResponses.code(status)+",\"message\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
