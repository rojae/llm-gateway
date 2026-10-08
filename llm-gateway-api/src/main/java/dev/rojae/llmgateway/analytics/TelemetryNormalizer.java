package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OTLP JSON only. Only explicit usage attributes survive normalization; log bodies never do. */
public final class TelemetryNormalizer {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(2097152).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ATTRIBUTES=Set.of("event.name","event.timestamp","event.sequence","session.id","request_id","client_request_id","model","input_tokens","output_tokens","cache_read_tokens","cache_creation_tokens","cost_usd","cost_usd_micros","duration_ms","user.id","query_source","agent.name","agent_type","agent.type","status_code","attempt");
    private final TelemetryProperties properties;
    public record Batch(List<byte[]> records,int rejected){}
    public TelemetryNormalizer(TelemetryProperties properties){this.properties=properties;}

    public Batch normalize(byte[] payload) {return normalize(payload,properties.getBillingMode());}
    public Batch normalize(byte[] payload,String trustedBillingMode) {
        if(!Set.of("subscription_declared","api_key_declared","unknown").contains(trustedBillingMode))throw new IllegalArgumentException("invalid_billing_mode");
        if(payload.length>properties.getMaxBodyBytes())throw new IllegalArgumentException("payload_too_large");
        JsonNode root;
        try{root=JSON.readTree(payload);}catch(Exception ignored){throw new IllegalArgumentException("invalid_json");}
        if(root==null||!root.isObject())throw new IllegalArgumentException("invalid_envelope");
        List<byte[]> records=new ArrayList<>();int seen=0,rejected=0;
        for(JsonNode resource:array(root,"resourceLogs")) {
            Map<String,JsonNode> resourceAttributes=attributes(resource.path("resource").get("attributes"));
            for(JsonNode scope:array(resource,"scopeLogs")) for(JsonNode log:array(scope,"logRecords")) {
                if(++seen>properties.getMaxRecords())throw new IllegalArgumentException("too_many_records");
                try {
                    if(!log.isObject())throw new IllegalArgumentException("invalid_record");
                    Map<String,JsonNode> values=new HashMap<>(resourceAttributes);values.putAll(attributes(log.get("attributes")));
                    String event=text(values,"event.name",128);
                    if(event==null&&log.path("eventName").isTextual())event=log.path("eventName").textValue();
                    if(event==null&&log.path("body").path("stringValue").isTextual())event=log.path("body").path("stringValue").textValue();
                    if(event!=null&&event.startsWith("claude_code."))event=event.substring(12);
                    if(!"api_request".equals(event)&&!"api_error".equals(event))continue;
                    records.add(JSON.writeValueAsBytes(normalize(log,values,event,trustedBillingMode)));
                }catch(Exception ignored){rejected++;}
            }
        }
        return new Batch(List.copyOf(records),rejected);
    }

    private ObjectNode normalize(JsonNode log,Map<String,JsonNode> values,String event,String trustedBillingMode) {
        Instant timestamp;
        String reportedTime=text(values,"event.timestamp",64);
        if(reportedTime!=null)timestamp=Instant.parse(reportedTime);
        else {
            JsonNode time=log.get("timeUnixNano");
            if(time==null)throw new IllegalArgumentException("missing_timestamp");
            long nanos=nonnegativeLong(time);
            if(nanos==0)throw new IllegalArgumentException("missing_timestamp");
            timestamp=Instant.ofEpochSecond(nanos/1_000_000_000L,nanos%1_000_000_000L);
        }
        if(timestamp.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||timestamp.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw new IllegalArgumentException("invalid_timestamp");
        String session=label(values,"session.id",256),serverId=label(values,"request_id",256),clientId=label(values,"client_request_id",256);
        Long sequence=integer(values,"event.sequence"),attempt=integer(values,"attempt");
        String identity;
        if(serverId!=null)identity="server:"+serverId;
        else if(clientId!=null)identity="client:"+clientId;
        else {
            if(session==null||sequence==null)throw new IllegalArgumentException("missing_identity");
            String preciseTime=log.has("timeUnixNano")?Long.toString(nonnegativeLong(log.get("timeUnixNano"))):timestamp.toString();
            String installation=text(values,"user.id",512);
            identity="session:"+session+"|"+timestamp+"|"+preciseTime+"|"+sequence+"|"+(installation==null?"":hash(installation));
        }
        if("api_error".equals(event))identity+="|attempt:"+attempt;
        ObjectNode result=JSON.createObjectNode();
        result.put("requestId",hash("claude_code|"+event+"|"+identity).substring(0,32));
        result.put("timestamp",timestamp.toString());result.put("source","claude_code");
        result.put("billingMode",trustedBillingMode);result.putNull("costUsd");
        result.put("traffic","live");result.put("provider","anthropic");result.put("generation",true);
        result.put("upstreamRequestId",serverId);result.put("clientRequestId",clientId);
        result.put("event",event);result.put("sessionId",session);result.put("model",label(values,"model",256));
        String user=text(values,"user.id",512);result.put("keyId",user==null?null:"cc_"+hash(user).substring(0,32));
        result.put("querySource",label(values,"query_source",128));
        String agent=label(values,"agent.name",128);if(agent==null)agent=label(values,"agent.type",128);if(agent==null)agent=label(values,"agent_type",128);
        result.put("agentType",agent);
        Long input=integer(values,"input_tokens"),output=integer(values,"output_tokens"),read=integer(values,"cache_read_tokens"),write=integer(values,"cache_creation_tokens");
        result.put("promptTokens",input);result.put("completionTokens",output);result.put("cacheReadTokens",read);result.put("cacheWriteTokens",write);result.putNull("cacheWrite1hTokens");
        Long total=input==null||output==null?null:Math.addExact(Math.addExact(input,output),Math.addExact(read==null?0:read,write==null?0:write));
        result.put("totalTokens",total);result.put("usageAvailability",total==null?"missing":"api_error".equals(event)?"partial":"reported");
        BigDecimal estimated;
        Long micros=integer(values,"cost_usd_micros");
        if(micros!=null)estimated=BigDecimal.valueOf(micros,6);else estimated=decimal(values,"cost_usd");
        if(estimated!=null&&estimated.setScale(12,RoundingMode.HALF_UP).compareTo(new BigDecimal("1000000000000"))>=0)throw new IllegalArgumentException("invalid_cost");
        result.put("apiEquivalentCostUsd",estimated);
        result.put("totalMs",decimal(values,"duration_ms"));result.putNull("ttftMs");
        Long status=integer(values,"status_code");if(status!=null&&(status<100||status>599))throw new IllegalArgumentException("invalid_status");
        if("api_error".equals(event)){result.put("status",status);result.put("outcome",status!=null&&status>=400?"http_error":"upstream_error");result.put("upstreamError","telemetry_api_error");}
        else {result.put("status",status);result.put("outcome",status!=null&&status>=400?"http_error":"success");}
        return result;
    }
    private static Iterable<JsonNode> array(JsonNode node,String field){
        if(!node.isObject())throw new IllegalArgumentException("invalid_envelope");
        JsonNode array=node.get(field);if(array==null)return List.of();
        if(!array.isArray())throw new IllegalArgumentException("invalid_envelope");return array;
    }
    private static Map<String,JsonNode> attributes(JsonNode attributes){
        Map<String,JsonNode> result=new HashMap<>();if(attributes==null)return result;
        if(!attributes.isArray()||attributes.size()>256)throw new IllegalArgumentException("invalid_attributes");
        for(JsonNode item:attributes){
            if(!item.isObject()||!item.path("key").isTextual())throw new IllegalArgumentException("invalid_attributes");
            String key=item.path("key").textValue();if(!ATTRIBUTES.contains(key))continue;
            if(result.putIfAbsent(key,item.path("value"))!=null)throw new IllegalArgumentException("duplicate_attribute");
        }return result;
    }
    private static JsonNode scalar(Map<String,JsonNode> values,String key){
        JsonNode value=values.get(key);if(value==null)return null;
        if(!value.isObject()||value.size()!=1)throw new IllegalArgumentException("invalid_attribute");
        JsonNode scalar;
        if(value.has("stringValue")){scalar=value.get("stringValue");if(!scalar.isTextual())throw new IllegalArgumentException("invalid_attribute");}
        else if(value.has("intValue")){scalar=value.get("intValue");if(!scalar.isTextual()&&!scalar.isIntegralNumber())throw new IllegalArgumentException("invalid_attribute");}
        else if(value.has("doubleValue")){scalar=value.get("doubleValue");if(!scalar.isNumber())throw new IllegalArgumentException("invalid_attribute");}
        else throw new IllegalArgumentException("invalid_attribute");
        return scalar;
    }
    private static String text(Map<String,JsonNode> values,String key,int max){JsonNode value=scalar(values,key);if(value==null)return null;if(!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>max)throw new IllegalArgumentException("invalid_attribute");return value.textValue();}
    private static String label(Map<String,JsonNode> values,String key,int max){String value=text(values,key,max);if(value!=null&&(!value.matches("[A-Za-z0-9_.:/-]+")||value.toLowerCase(java.util.Locale.ROOT).startsWith("sk-")))throw new IllegalArgumentException("invalid_label");return value;}
    private static Long integer(Map<String,JsonNode> values,String key){
        JsonNode value=scalar(values,key);if(value==null)return null;
        if(value.isFloatingPointNumber()){BigDecimal number=boundedDecimal(value.asText());long integral=number.longValueExact();if(integral<0)throw new IllegalArgumentException("invalid_integer");return integral;}
        return nonnegativeLong(value);
    }
    private static long nonnegativeLong(JsonNode value){if(!value.isTextual()&&!value.isIntegralNumber())throw new IllegalArgumentException("invalid_integer");String text=value.asText();if(!text.matches("[0-9]{1,19}"))throw new IllegalArgumentException("invalid_integer");return Long.parseLong(text);}
    private static BigDecimal decimal(Map<String,JsonNode> values,String key){JsonNode value=scalar(values,key);if(value==null)return null;return boundedDecimal(value.asText());}
    private static BigDecimal boundedDecimal(String text){
        if(text.length()>64)throw new IllegalArgumentException("invalid_decimal");
        BigDecimal result=new BigDecimal(text);
        if(result.signum()<0||result.precision()>30||result.scale() < -18||result.scale()>18||!Double.isFinite(result.doubleValue()))throw new IllegalArgumentException("invalid_decimal");
        return result;
    }
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("hash_unavailable");}}
}
