package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Versioned native OTLP adapter. Raw bodies, arbitrary attributes and provider identities never persist. */
public final class MonitoringNormalizer {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(2097152).build()).build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ALLOWED=Set.of("event.name","event.kind","event.timestamp","event.sequence","session.id","conversation.id","prompt.id","request_id","client_request_id","model","input_tokens","output_tokens","cache_read_tokens","cache_creation_tokens","input_token_count","output_token_count","cached_token_count","cache_write_token_count","cost_usd","cost_usd_micros","service.name","service.version","app.version","query_source","agent_type","agent.type","agent.name","prompt","prompt_text","user.account_id","user.account_uuid");
    private final TelemetryProperties properties;
    public record Event(String identity,String source,String kind,String session,String promptId,String requestId,
        Instant emittedAt,String cliVersion,String model,Long input,Long output,Long cacheRead,Long cacheWrite,Long total,
        BigDecimal cost,String scope,String semantics,String attribution,String availability,boolean supported,
        boolean question,String content,boolean truncated,String providerAccountHash){}
    public record Batch(List<Event> events,int rejected){}
    public MonitoringNormalizer(TelemetryProperties properties){this.properties=properties;}
    public Batch normalize(byte[] bytes){
        if(bytes.length>properties.getMaxBodyBytes())throw new IllegalArgumentException("payload_too_large");
        JsonNode root;try{root=JSON.readTree(bytes);}catch(Exception invalid){throw new IllegalArgumentException("invalid_json");}
        if(root==null||!root.isObject())throw new IllegalArgumentException("invalid_envelope");
        List<Event> events=new ArrayList<>();int rejected=0,seen=0;
        for(JsonNode resource:array(root,"resourceLogs")){
            Map<String,JsonNode> base=attributes(resource.path("resource").get("attributes"));
            for(JsonNode scope:array(resource,"scopeLogs"))for(JsonNode log:array(scope,"logRecords")){
                if(++seen>properties.getMaxRecords())throw new IllegalArgumentException("too_many_records");
                try{events.add(event(log,base));}catch(RuntimeException invalid){rejected++;}
            }
        }
        return new Batch(List.copyOf(events),rejected);
    }
    private Event event(JsonNode log,Map<String,JsonNode> base){
        if(!log.isObject())throw new IllegalArgumentException("invalid_record");
        var values=new TreeMap<String,JsonNode>(base);values.putAll(attributes(log.get("attributes")));
        // OTLP structured bodies are inspected only for the same explicit field allowlist.
        JsonNode kv=log.path("body").path("kvlistValue").get("values");
        if(kv!=null){Map<String,JsonNode> body=attributes(kv);body.forEach(values::putIfAbsent);}
        String name=text(values,"event.name",128);
        if(name==null&&log.path("eventName").isTextual())name=log.path("eventName").textValue();
        if(name==null){String body=log.path("body").path("stringValue").asText("");if(body.matches("(?:claude_code|codex)\\.[a-zA-Z0-9_.-]{1,100}"))name=body;}
        String service=text(values,"service.name",128);
        String source=name!=null&&name.startsWith("codex.")||service!=null&&service.toLowerCase(Locale.ROOT).contains("codex")?"codex":"claude_code";
        if(name==null)name="unknown";
        if(!name.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("invalid_event");
        String kind=name.replaceFirst("^(claude_code|codex)\\.","");
        String subkind=label(values,"event.kind",128);if("sse_event".equals(kind)&&subkind!=null)kind=subkind;
        String session=label(values,"codex".equals(source)?"conversation.id":"session.id",256);
        String prompt="claude_code".equals(source)?label(values,"prompt.id",256):null;
        String request=label(values,"request_id",256);if(request==null)request=label(values,"client_request_id",256);
        String model=label(values,"model",256);
        String version=label(values,"app.version",80);if(version==null)version=label(values,"service.version",80);
        Instant at;String timestamp=text(values,"event.timestamp",64);
        if(timestamp!=null)at=Instant.parse(timestamp);else{
            JsonNode nanos=log.get("timeUnixNano");if(nanos==null)throw new IllegalArgumentException("missing_timestamp");long value=nanos(nanos);if(value==0)throw new IllegalArgumentException("missing_timestamp");at=Instant.ofEpochSecond(value/1000000000L,value%1000000000L);
        }
        if(at.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||at.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw new IllegalArgumentException("invalid_timestamp");
        boolean claude="claude_code".equals(source),question="user_prompt".equals(kind);
        boolean usage=claude?"api_request".equals(kind):"response.completed".equals(kind);
        boolean supported=question||usage||Set.of("api_error","tool_result","tool_decision","conversation_starts").contains(kind);
        Long input=integer(values,claude?"input_tokens":"input_token_count"),output=integer(values,claude?"output_tokens":"output_token_count"),read=integer(values,claude?"cache_read_tokens":"cached_token_count"),write=integer(values,claude?"cache_creation_tokens":"cache_write_token_count");
        Long total=input==null||output==null?null:Math.addExact(input,output);
        if(claude&&total!=null)total=Math.addExact(total,Math.addExact(read==null?0:read,write==null?0:write));
        boolean conflict=!claude&&input!=null&&read!=null&&read>input;
        String querySource=label(values,"query_source",128),agent=label(values,"agent_type",128);if(agent==null)agent=label(values,"agent.type",128);if(agent==null)agent=label(values,"agent.name",128);
        // Link only validated native prompt + consumption identity; auxiliary/subagent sums remain unconfirmed.
        boolean linked=claude&&usage&&prompt!=null&&request!=null&&session!=null
            &&(querySource!=null||agent!=null)
            &&(querySource==null||Set.of("repl_main","main","user").contains(querySource))
            &&(agent==null||Set.of("main","primary").contains(agent));
        BigDecimal cost=null;if(claude&&usage){Long micros=integer(values,"cost_usd_micros");cost=micros!=null?BigDecimal.valueOf(micros,6):decimal(values,"cost_usd");}
        if(cost!=null&&!knownClaudeModel(model))cost=null;
        String content=null;if(question){content=text(values,claude?"prompt_text":"prompt",properties.getMaxBodyBytes());if(content==null&&claude)content=text(values,"prompt",properties.getMaxBodyBytes());}
        boolean truncated=content!=null&&content.length()>16384;if(truncated)content=content.substring(0,16384);
        String attribution=conflict?"conflict":linked?"linked":"unattributed";
        String availability=conflict?"conflict":usage&&total!=null?linked?"reported":"unattributed":"missing";
        // Fingerprint excludes transport token, registration, observedTime and reception time. Values are canonical.
        var fingerprint=new TreeMap<String,String>();fingerprint.put("source",source);fingerprint.put("kind",kind);fingerprint.put("session",Objects.toString(session,""));
        if(request!=null){fingerprint.remove("session");fingerprint.put("request",request);if("api_error".equals(kind))fingerprint.put("timestamp",at.toString());}
        else if(question&&prompt!=null)fingerprint.put("prompt",prompt);
        else{
            fingerprint.put("timestamp",at.toString());
            for(var field:values.entrySet())if(!Set.of("service.name","service.version","app.version","user.account_id","user.account_uuid").contains(field.getKey()))fingerprint.put(field.getKey(),Objects.toString(scalar(field.getValue()),""));
        }
        String providerAccount=text(values,"user.account_uuid",512);if(providerAccount==null)providerAccount=text(values,"user.account_id",512);
        String providerHash=providerAccount==null?null:hash(source+"|"+providerAccount);
        String canonical;try{canonical=JSON.writeValueAsString(fingerprint);}catch(Exception impossible){throw new IllegalStateException(impossible);}
        return new Event(hash(canonical),source,kind,session,prompt,request,at,version,model,input,output,read,write,total,cost,
            usage?"response":"unknown",claude&&usage?"delta":"unknown",attribution,availability,supported,question,content,truncated,providerHash);
    }
    private static boolean knownClaudeModel(String model){
        if(model==null)return false;
        for(var known:dev.rojae.llmgateway.observe.KnownModel.values())if(known.vendor()==dev.rojae.llmgateway.observe.KnownModel.Vendor.ANTHROPIC){
            if(model.equals(known.id())||model.startsWith(known.id()+"-")&&model.substring(known.id().length()+1).matches("[0-9]{8}"))return true;
        }return false;
    }
    private static Iterable<JsonNode> array(JsonNode node,String key){if(!node.isObject())throw new IllegalArgumentException("invalid_envelope");JsonNode value=node.get(key);if(value==null)return List.of();if(!value.isArray())throw new IllegalArgumentException("invalid_envelope");return value;}
    private static Map<String,JsonNode> attributes(JsonNode attrs){var values=new TreeMap<String,JsonNode>();if(attrs==null)return values;if(!attrs.isArray()||attrs.size()>256)throw new IllegalArgumentException("invalid_attributes");for(JsonNode attr:attrs){if(!attr.isObject()||!attr.path("key").isTextual())throw new IllegalArgumentException("invalid_attributes");String key=attr.path("key").textValue();if(ALLOWED.contains(key)&&values.putIfAbsent(key,attr.path("value"))!=null)throw new IllegalArgumentException("duplicate_attribute");}return values;}
    private static JsonNode scalar(JsonNode value){if(value==null)return null;if(!value.isObject()||value.size()!=1)throw new IllegalArgumentException("invalid_attribute");for(String type:List.of("stringValue","intValue","doubleValue"))if(value.has(type)){JsonNode result=value.get(type);if(!result.isTextual()&&!result.isNumber())throw new IllegalArgumentException("invalid_attribute");return result;}throw new IllegalArgumentException("invalid_attribute");}
    private static String text(Map<String,JsonNode> attrs,String key,int max){JsonNode node=scalar(attrs.get(key));if(node==null)return null;if(!node.isTextual()||node.textValue().length()>max)throw new IllegalArgumentException("invalid_attribute");return node.textValue().isBlank()?null:node.textValue();}
    private static String label(Map<String,JsonNode> attrs,String key,int max){String value=text(attrs,key,max);if(value!=null&&(!value.matches("[A-Za-z0-9_.:/-]+")||value.toLowerCase(Locale.ROOT).startsWith("sk-")))throw new IllegalArgumentException("invalid_label");return value;}
    private static Long integer(Map<String,JsonNode> attrs,String key){JsonNode value=scalar(attrs.get(key));return value==null?null:number(value);}
    private static long nanos(JsonNode value){if(!value.isTextual()&&!value.isIntegralNumber())throw new IllegalArgumentException("invalid_timestamp");String raw=value.asText();if(!raw.matches("[0-9]{1,19}"))throw new IllegalArgumentException("invalid_timestamp");return Long.parseLong(raw);}
    private static long number(JsonNode value){String text=value.asText();if(text.length()>64)throw new IllegalArgumentException("invalid_number");BigDecimal number=bounded(text);long result=number.longValueExact();if(result<0)throw new IllegalArgumentException("invalid_number");return result;}
    private static BigDecimal decimal(Map<String,JsonNode> attrs,String key){JsonNode value=scalar(attrs.get(key));return value==null?null:bounded(value.asText());}
    private static BigDecimal bounded(String text){if(text.length()>64)throw new IllegalArgumentException("invalid_number");BigDecimal value=new BigDecimal(text);if(value.signum()<0||value.precision()>30||value.scale()<-18||value.scale()>18||value.compareTo(new BigDecimal("1000000000000"))>=0)throw new IllegalArgumentException("invalid_number");return value;}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
