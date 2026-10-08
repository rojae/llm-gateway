package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.security.AdminAccess;
import dev.rojae.llmgateway.security.TenantPrincipal;
import jakarta.annotation.PreDestroy;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@RestController
@RequestMapping("/admin/api/collectors")
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class CollectorCredentialController {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final CollectorCredentialService service;
    private final AdminAccess access;
    private final Scheduler scheduler=Schedulers.newBoundedElastic(2,100,"collector-admin");
    public CollectorCredentialController(CollectorCredentialService service,AdminAccess access){this.service=service;this.access=access;}
    @GetMapping public Mono<ResponseEntity<Object>> list(@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){
        return errors(access.require(exchange,"collectors:manage").flatMap(p->run(()->response(200,service.list(p.tenantId(),limit,offset)))));
    }
    @PostMapping public Mono<ResponseEntity<Object>> create(ServerWebExchange exchange){
        return errors(access.require(exchange,"collectors:manage").flatMap(p->DataBufferUtils.join(exchange.getRequest().getBody(),8192)
            .map(buffer->{try{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}})
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Empty request")))
            .flatMap(bytes->run(()->create(p,bytes)))));
    }
    private ResponseEntity<Object> create(TenantPrincipal principal,byte[] bytes)throws Exception{
        JsonNode body;
        try{body=JSON.readTree(bytes);}catch(Exception invalid){throw new IllegalArgumentException("Invalid request");}
        if(body==null||!body.isObject())throw new IllegalArgumentException("Invalid request");
        var names=body.fieldNames();while(names.hasNext())if(!Set.of("name","expiresInDays","billingMode","ownerAccountId","registrationId").contains(names.next()))throw new IllegalArgumentException("Unknown field");
        if(!body.path("name").isTextual()||(body.has("expiresInDays")&&(!body.get("expiresInDays").isIntegralNumber()||!body.get("expiresInDays").canConvertToInt()))||(body.has("billingMode")&&!body.get("billingMode").isTextual()))throw new IllegalArgumentException("Invalid request");
        return response(201,service.create(principal.tenantId(),principal.subject(),body.get("name").textValue(),body.path("expiresInDays").asInt(30),body.path("billingMode").asText("subscription_declared"),uuid(body,"ownerAccountId"),uuid(body,"registrationId")));
    }
    private static UUID uuid(JsonNode body,String field){if(!body.has(field)||body.get(field).isNull())return null;if(!body.get(field).isTextual())throw new IllegalArgumentException("Invalid identifier");return UUID.fromString(body.get(field).textValue());}
    @DeleteMapping("/{id}") public Mono<ResponseEntity<Object>> revoke(@PathVariable String id,ServerWebExchange exchange){
        return errors(access.require(exchange,"collectors:manage").flatMap(p->run(()->{service.revoke(p.tenantId(),p.subject(),UUID.fromString(id));return response(204,null);})));
    }
    private interface Action {ResponseEntity<Object> run()throws Exception;}
    private Mono<ResponseEntity<Object>> run(Action action){return Mono.fromCallable(action::run).subscribeOn(scheduler);}
    static ResponseEntity<Object> response(int status,Object body){return ResponseEntity.status(status).header("Cache-Control","no-store").body(body);}
    static Mono<ResponseEntity<Object>> errors(Mono<ResponseEntity<Object>> response){
        return response.onErrorResume(ResponseStatusException.class,e->Mono.just(error(e.getStatusCode().value(),e.getStatusCode().value()==401?"unauthorized":e.getStatusCode().value()==403?"forbidden":"analytics_unavailable")))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(error(400,"invalid_request")))
            .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class,e->Mono.just(error(413,"request_too_large")))
            .onErrorResume(e->Mono.just(error(503,"analytics_unavailable")));
    }
    private static ResponseEntity<Object> error(int status,String code){return response(status,Map.of("error",Map.of("code",code,"message","Request unavailable")));}
    @PreDestroy public void close(){scheduler.dispose();}
}
