package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.security.AdminAccess;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@RestController
@RequestMapping("/admin/api/audit")
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class AuditController {
    private final AuditService service;
    private final AdminAccess access;
    private final Scheduler scheduler=Schedulers.newBoundedElastic(2,100,"audit-admin");
    public AuditController(AuditService service,AdminAccess access){this.service=service;this.access=access;}
    @GetMapping public Mono<ResponseEntity<Object>> list(@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){
        return CollectorCredentialController.errors(access.require(exchange,"audit:read").flatMap(p->Mono.fromCallable(()->CollectorCredentialController.response(200,service.list(p.tenantId(),limit,offset))).subscribeOn(scheduler)));
    }
    @PreDestroy public void close(){scheduler.dispose();}
}
