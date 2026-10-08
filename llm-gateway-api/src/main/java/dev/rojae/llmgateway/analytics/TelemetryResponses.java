package dev.rojae.llmgateway.analytics;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** OTLP failures use the protobuf JSON representation of google.rpc.Status. */
final class TelemetryResponses {
    private TelemetryResponses(){}
    static int code(HttpStatus status){return switch(status){
        case UNAUTHORIZED -> 16;
        case NOT_FOUND -> 5;
        case PAYLOAD_TOO_LARGE -> 8;
        case UNSUPPORTED_MEDIA_TYPE -> 12;
        case SERVICE_UNAVAILABLE -> 14;
        default -> 3;
    };}
    static Map<String,Object> failure(HttpStatus status,String message){return Map.of("code",code(status),"message",message);}
}
