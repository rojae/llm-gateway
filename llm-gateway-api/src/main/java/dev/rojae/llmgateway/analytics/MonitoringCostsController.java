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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

@RestController
@RequestMapping("/admin/api/monitoring/accounts/{id}/costs")
@ConditionalOnProperty(name="gateway.analytics.enabled",havingValue="true")
public final class MonitoringCostsController {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final MonitoringCosts costs;private final AdminAccess access;
    private final Scheduler worker=Schedulers.newBoundedElastic(2,100,"monitoring-costs");
    public MonitoringCostsController(MonitoringCosts costs,AdminAccess access){this.costs=costs;this.access=access;}
    @GetMapping public Mono<ResponseEntity<Object>> get(@PathVariable String id,@RequestParam MultiValueMap<String,String> params,ServerWebExchange exchange){
        return errors(access.require(exchange,"usage:read").flatMap(p->Mono.fromCallable(()->CollectorCredentialController.response(200,costs.report(p.tenantId(),UUID.fromString(id),MonitoringCosts.parse(params)))).subscribeOn(worker)));
    }
    @PutMapping("/subscription") public Mono<ResponseEntity<Object>> put(@PathVariable String id,ServerWebExchange exchange){
        return errors(access.require(exchange,"collectors:manage").flatMap(p->DataBufferUtils.join(exchange.getRequest().getBody(),8192)
            .map(buffer->{try{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}})
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Empty request")))
            .flatMap(bytes->Mono.fromCallable(()->{
                if(!exchange.getRequest().getQueryParams().isEmpty())throw new IllegalArgumentException("Invalid query");
                JsonNode body=JSON.readTree(bytes);
                if(body==null||!body.isObject()||body.size()!=3||!body.has("month")||!body.has("amount")||!body.has("currency")||!body.get("month").isTextual()||!body.get("currency").isTextual()||!(body.get("amount").isNull()||body.get("amount").isNumber()))throw new IllegalArgumentException("Invalid subscription");
                return CollectorCredentialController.response(200,costs.configure(p.tenantId(),UUID.fromString(id),p.subject(),body.get("month").textValue(),body.get("amount").isNull()?null:body.get("amount").decimalValue(),body.get("currency").textValue()));
            }).subscribeOn(worker))));
    }
    private static Mono<ResponseEntity<Object>> errors(Mono<ResponseEntity<Object>> result){return result
        .onErrorResume(NoSuchElementException.class,e->Mono.just(CollectorCredentialController.response(404,Map.of("error",Map.of("code","not_found","message","Record not found")))))
        .onErrorResume(com.fasterxml.jackson.core.JacksonException.class,e->Mono.just(CollectorCredentialController.response(400,Map.of("error",Map.of("code","invalid_request","message","Invalid request")))))
        .transform(CollectorCredentialController::errors);}
    @PreDestroy public void close(){worker.dispose();}
}
