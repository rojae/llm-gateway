package dev.rojae.llmgateway.analytics;

import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** OTLP HTTP/JSON acknowledgement means the normalized usage transaction has committed. */
@RestController
@ConditionalOnProperty(name={"gateway.analytics.enabled","gateway.telemetry.enabled"},havingValue="true")
public final class TelemetryController {
    private final AnalyticsDatabase database;
    private final TelemetryProperties properties;
    private final TelemetryNormalizer normalizer;
    private final MonitoringNormalizer monitoring;
    private final Scheduler worker=Schedulers.newBoundedElastic(2,32,"telemetry-persistence");
    private final CollectorCredentialService credentials;
    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryController(AnalyticsDatabase database,TelemetryProperties properties,CollectorCredentialService credentials){this.database=database;this.properties=properties;this.credentials=credentials;normalizer=new TelemetryNormalizer(properties);monitoring=new MonitoringNormalizer(properties);}
    @PreDestroy public void stop(){worker.dispose();}

    @PostMapping(path="/telemetry/v1/logs")
    public Mono<ResponseEntity<Map<String,Object>>> logs(org.springframework.web.server.ServerWebExchange exchange){
        CollectorCredentialService.Identity identity=exchange.getAttribute(TelemetryAuthFilter.IDENTITY_ATTRIBUTE);
        if(identity==null)return Mono.just(error(HttpStatus.UNAUTHORIZED,"unauthorized"));
        ServerHttpRequest request=exchange.getRequest();
        String encoding=request.getHeaders().getFirst("Content-Encoding");
        if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))return Mono.just(error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"unsupported_encoding"));
        MediaType contentType;
        try{contentType=request.getHeaders().getContentType();}catch(IllegalArgumentException bad){return Mono.just(error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"unsupported_media_type"));}
        if(contentType==null||!MediaType.APPLICATION_JSON.isCompatibleWith(contentType))return Mono.just(error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"unsupported_media_type"));
        if(request.getHeaders().getContentLength()>properties.getMaxBodyBytes())return Mono.just(error(HttpStatus.PAYLOAD_TOO_LARGE,"payload_too_large"));
        return DataBufferUtils.join(request.getBody(),properties.getMaxBodyBytes())
                .map(buffer->{try{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}})
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes->Mono.fromCallable(()->persist(bytes,identity)).subscribeOn(worker))
                .onErrorResume(DataBufferLimitException.class,error->Mono.just(error(HttpStatus.PAYLOAD_TOO_LARGE,"payload_too_large")))
                .onErrorResume(RejectedExecutionException.class,error->Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"telemetry_busy")))
                .onErrorResume(error->Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"telemetry_unavailable")));
    }
    private ResponseEntity<Map<String,Object>> persist(byte[] payload,CollectorCredentialService.Identity identity){
        TelemetryNormalizer.Batch batch;
        MonitoringNormalizer.Batch remote;
        try{remote=monitoring.normalize(payload);batch=identity.ownerAccountId()==null?normalizer.normalize(payload,identity.billingMode()):new TelemetryNormalizer.Batch(java.util.List.of(),0);}
        catch(IllegalArgumentException invalid){
            boolean tooLarge="payload_too_large".equals(invalid.getMessage())||"too_many_records".equals(invalid.getMessage());
            return error(tooLarge?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.BAD_REQUEST,tooLarge?"payload_too_large":"invalid_telemetry");
        }
        int rejected=identity.ownerAccountId()==null?Math.max(batch.rejected(),remote.rejected()):remote.rejected();
        boolean accountNotLinked=false;int stored=0,duplicates=0,unsupported=0,unattributed=0;
        try(var connection=database.open()) {
            connection.setAutoCommit(false);
            try {
                if(identity.credentialId()!=null&&!credentials.stillAuthorized(connection,identity)){
                    connection.rollback();return error(HttpStatus.UNAUTHORIZED,"unauthorized");
                }
                for(var event:remote.events()){
                    if(identity.ownerAccountId()==null){
                        if(!("claude_code".equals(event.source())&&java.util.Set.of("api_request","api_error").contains(event.kind()))){rejected++;accountNotLinked=true;}
                        continue;
                    }
                    var savepoint=connection.setSavepoint();
                    try{
                        if(MonitoringStore.insert(connection,identity,event)){stored++;if(!event.supported())unsupported++;if("unattributed".equals(event.attribution()))unattributed++;}
                        else duplicates++;
                    }catch(SQLException invalid){if(invalid.getSQLState()==null||!invalid.getSQLState().startsWith("22"))throw invalid;connection.rollback(savepoint);rejected++;}
                    connection.releaseSavepoint(savepoint);
                }
                for(byte[] record:batch.records()) {
                    var savepoint=connection.setSavepoint();
                    try{AnalyticsRecord.insert(connection,"access",record,identity.tenantId());}
                    catch(AnalyticsRecord.Rejected invalid){rejected++;}
                    catch(SQLException invalid){
                        if(invalid.getSQLState()==null||!invalid.getSQLState().startsWith("22"))throw invalid;
                        connection.rollback(savepoint);rejected++;
                    }
                    connection.releaseSavepoint(savepoint);
                }
                MonitoringStore.status(connection,identity.tenantId(),stored,duplicates,rejected,unsupported,unattributed);
                connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}
        }catch(Exception failure){return error(HttpStatus.SERVICE_UNAVAILABLE,"telemetry_unavailable");}
        Map<String,Object> response=rejected==0?Map.of():Map.of("partialSuccess",Map.of("rejectedLogRecords",Integer.toString(rejected),"errorMessage",accountNotLinked?"account_not_linked":"Malformed supported telemetry records rejected"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
    private static ResponseEntity<Map<String,Object>> error(HttpStatus status,String code){return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(TelemetryResponses.failure(status,code));}
}
