package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.security.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/admin/api")
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class AdminController {
    private final AnalyticsQueries queries;
    private final AdminAccess access;
    private final AuditService audit;
    private final GatewaySecurityProperties security;
    private final Scheduler scheduler=Schedulers.newBoundedElastic(4,100,"analytics-admin");
    public AdminController(AnalyticsQueries queries,AdminAccess access,AuditService audit,GatewaySecurityProperties security) {
        this.queries=queries;this.access=access;this.audit=audit;this.security=security;
    }
    @GetMapping("/session") public Mono<Map<String,Object>> session(ServerWebExchange exchange) {
        return access.require(exchange,"usage:read").flatMap(principal->{
            var body=new LinkedHashMap<String,Object>();body.put("authenticated",true);body.put("user",Map.of("subject",principal.subject()));
            body.put("tenantId",principal.tenantId());body.put("roles",principal.roles());body.put("permissions",principal.permissions());body.put("csrfToken",null);
            if(!security.isEnterprise())return Mono.just(body);
            Mono<CsrfToken> token=exchange.getAttribute(CsrfToken.class.getName());
            if(token==null)return Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Session protection unavailable"));
            return token.map(csrf->{body.put("csrfToken",csrf.getToken());return (Map<String,Object>)body;});
        });
    }
    @GetMapping("/overview") public Mono<ResponseEntity<Object>> overview(@RequestParam MultiValueMap<String,String> params,ServerWebExchange exchange) {
        return execute(exchange,"usage:read",p->queries.overview(AdminFilters.parse(params),p.tenantId()));
    }
    @GetMapping("/requests") public Mono<ResponseEntity<Object>> requests(@RequestParam MultiValueMap<String,String> params,ServerWebExchange exchange) {
        return execute(exchange,"usage:read",p->queries.requests(AdminFilters.parse(params),p.tenantId()));
    }
    @GetMapping("/requests/{requestId}") public Mono<ResponseEntity<Object>> detail(@PathVariable String requestId,ServerWebExchange exchange) {
        return execute(exchange,"usage:read",p->{
            boolean include=p.hasPermission("content:read");
            Map<String,Object> result=queries.detail(requestId,p.tenantId(),include);
            if(include)audit.record(p.tenantId(),p.subject(),"content.read",requestId,"success");
            return result;
        });
    }
    @GetMapping("/ingestion") public Mono<ResponseEntity<Object>> ingestion(ServerWebExchange exchange) {
        return execute(exchange,"ingestion:read",p->queries.ingestion(p.tenantId()));
    }
    private interface Action {Object run(TenantPrincipal principal)throws Exception;}
    private Mono<ResponseEntity<Object>> execute(ServerWebExchange exchange,String permission,Action action) {
        return access.require(exchange,permission).flatMap(p->Mono.fromCallable(()->ResponseEntity.ok().header("Cache-Control","no-store").body(action.run(p))).subscribeOn(scheduler))
                .onErrorResume(ResponseStatusException.class,e->Mono.just(error(e.getStatusCode().value(),e.getStatusCode().value()==403?"forbidden":e.getStatusCode().value()==401?"unauthorized":"analytics_unavailable","Access unavailable")))
                .onErrorResume(IllegalArgumentException.class,e->Mono.just(error(400,"invalid_filter","Invalid request filters")))
                .onErrorResume(NoSuchElementException.class,e->Mono.just(error(404,"not_found","Request not found")))
                .onErrorResume(e->Mono.just(error(503,"analytics_unavailable","Analytics is temporarily unavailable")));
    }
    private static ResponseEntity<Object> error(int status,String code,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",Map.of("code",code,"message",message)));
    }
    @PreDestroy public void close(){scheduler.dispose();}
}
