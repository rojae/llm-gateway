package dev.rojae.llmgateway.analytics;

import java.math.*;
import java.util.*;

/** A versioned API equivalent, never a subscription charge or a final session total. */
public final class MonitoringCostEstimator {
    private MonitoringCostEstimator(){}
    public static final String VERSION="2026-10-08", SOURCE="https://developers.openai.com/api/docs/models/gpt-6.1-sol";
    private record Price(String input,String read,String write,String output,String sourceUrl){}
    private static final Map<String,Price> PRICES=Map.of(
        "gpt-6.1-sol",new Price("2","0.10","2.50","10",SOURCE),
        "gpt-6-astra",new Price("10","1","12.50","50","https://developers.openai.com/api/docs/models/gpt-6-astra"),
        "gpt-5.6-luna",new Price("0.20","0.02","0.25","1.20","https://developers.openai.com/api/docs/models/gpt-5.6-luna"));
    public record Estimate(BigDecimal amountUsd,String availability,String basis,String model,String priceVersion,String sourceUrl,
                           boolean longContext,Long ordinaryInputTokens,BigDecimal inputUsdPerMillion,BigDecimal cacheReadUsdPerMillion,BigDecimal cacheWriteUsdPerMillion,
                           BigDecimal outputUsdPerMillion,List<String> assumptions){}
    public static Estimate estimate(String source,String model,Long input,Long output,Long read,Long write,BigDecimal reported){
        if(reported!=null)return new Estimate(reported.signum()<0?null:reported.setScale(6,RoundingMode.HALF_UP),reported.signum()<0?"invalid_usage":"priced","client_reported",model,null,null,false,null,null,null,null,null,List.of("Client reported API equivalent; not a subscription charge"));
        Price price=model==null?null:PRICES.get(model);
        if(price==null)return unavailable("unpriced",model,List.of("No exact model price snapshot"),null);
        var assumptions=new ArrayList<String>();assumptions.add("Standard API equivalent; not a subscription charge; excludes tool fees, fast and regional pricing");
        if("codex".equals(source))assumptions.add("Codex input is assumed to include cache read and cache write tokens");
        if(read==null||write==null)assumptions.add("Missing cache counters assumed zero");
        if(input==null||output==null)return unavailable("missing_usage",model,assumptions,price);
        long r=read==null?0:read,w=write==null?0:write;
        if(input<0||output<0||r<0||w<0)return unavailable("invalid_usage",model,assumptions,price);
        BigDecimal in=BigDecimal.valueOf(input),cached=BigDecimal.valueOf(r),written=BigDecimal.valueOf(w);
        BigDecimal normal="codex".equals(source)?in.subtract(cached).subtract(written):in;
        if(normal.signum()<0)return unavailable("invalid_usage",model,assumptions,price);
        boolean large=input>272000;
        BigDecimal factor=large?new BigDecimal("2"):BigDecimal.ONE;
        BigDecimal ip=new BigDecimal(price.input()).multiply(factor),rp=new BigDecimal(price.read()).multiply(factor),wp=new BigDecimal(price.write()).multiply(factor),op=new BigDecimal(price.output()).multiply(large?new BigDecimal("1.5"):BigDecimal.ONE);
        BigDecimal cost=normal.multiply(ip).add(cached.multiply(rp)).add(written.multiply(wp)).add(BigDecimal.valueOf(output).multiply(op)).divide(new BigDecimal("1000000"),12,RoundingMode.HALF_UP).setScale(6,RoundingMode.HALF_UP);
        return new Estimate(cost,"priced","official_standard_api",model,VERSION,price.sourceUrl(),large,normal.longValueExact(),ip,rp,wp,op,List.copyOf(assumptions));
    }
    public static Estimate estimate(Map<String,Object> event){return estimate((String)event.get("source"),(String)event.get("model"),number(event.get("inputTokens")),number(event.get("outputTokens")),number(event.get("cacheReadTokens")),number(event.get("cacheWriteTokens")),(BigDecimal)event.get("apiEquivalentCostUsd"));}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static Estimate unavailable(String status,String model,List<String> assumptions,Price price){return new Estimate(null,status,"official_standard_api",model,price==null?null:VERSION,price==null?null:price.sourceUrl(),false,null,null,null,null,null,List.copyOf(assumptions));}
}
