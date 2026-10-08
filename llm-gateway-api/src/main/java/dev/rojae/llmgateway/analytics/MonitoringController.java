package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.rojae.llmgateway.security.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@RestController
@RequestMapping("/admin/api/monitoring")
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class MonitoringController {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MonitoringQueries queries;
    private final AdminAccess access;
    private final Scheduler worker=Schedulers.newBoundedElastic(2,100,"monitoring-admin");
    public MonitoringController(MonitoringQueries queries,AdminAccess access){this.queries=queries;this.access=access;}
    @GetMapping("/accounts") public Mono<ResponseEntity<Object>> accounts(@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.accounts(p.tenantId(),limit,offset));}
    @PostMapping("/accounts") public Mono<ResponseEntity<Object>> create(ServerWebExchange exchange){
        return errors(access.require(exchange,"collectors:manage").flatMap(p->DataBufferUtils.join(exchange.getRequest().getBody(),8192)
            .map(buffer->{try{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}})
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Empty request")))
            .flatMap(bytes->Mono.fromCallable(()->{
                JsonNode body=JSON.readTree(bytes);if(body==null||!body.isObject()||body.size()!=1||!body.path("name").isTextual())throw new IllegalArgumentException("Invalid account");
                return CollectorCredentialController.response(201,queries.createAccount(p.tenantId(),p.subject(),body.get("name").textValue()));
            }).subscribeOn(worker))));
    }
    @GetMapping("/sessions") public Mono<ResponseEntity<Object>> sessions(@RequestParam MultiValueMap<String,String> params,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.sessions(p.tenantId(),params));}
    @GetMapping("/sessions/{id}/questions") public Mono<ResponseEntity<Object>> questions(@PathVariable String id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.questions(p.tenantId(),UUID.fromString(id),limit,offset));}
    @GetMapping("/sessions/{id}/events") public Mono<ResponseEntity<Object>> events(@PathVariable String id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.events(p.tenantId(),UUID.fromString(id),limit,offset));}
    @GetMapping("/sessions/{id}/responses") public Mono<ResponseEntity<Object>> responses(@PathVariable String id,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.responses(p.tenantId(),UUID.fromString(id),p.hasPermission("content:read"),p.subject(),limit,offset));}
    @GetMapping("/questions/{id}") public Mono<ResponseEntity<Object>> detail(@PathVariable String id,ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.detail(p.tenantId(),UUID.fromString(id),p.hasPermission("content:read"),p.subject()));}
    @GetMapping("/status") public Mono<ResponseEntity<Object>> status(ServerWebExchange exchange){return execute(exchange,"usage:read",p->queries.status(p.tenantId()));}
    private interface Action{Object run(TenantPrincipal principal)throws Exception;}
    private Mono<ResponseEntity<Object>> execute(ServerWebExchange exchange,String permission,Action action){return errors(access.require(exchange,permission).flatMap(p->Mono.fromCallable(()->CollectorCredentialController.response(200,action.run(p))).subscribeOn(worker)));}
    private static Mono<ResponseEntity<Object>> errors(Mono<ResponseEntity<Object>> response){
        return response.onErrorResume(NoSuchElementException.class,e->Mono.just(CollectorCredentialController.response(404,Map.of("error",Map.of("code","not_found","message","Record not found")))))
            .onErrorResume(com.fasterxml.jackson.core.JacksonException.class,e->Mono.just(CollectorCredentialController.response(400,Map.of("error",Map.of("code","invalid_request","message","Invalid request")))))
            .transform(CollectorCredentialController::errors);
    }
    @PreDestroy public void close(){worker.dispose();}
}
