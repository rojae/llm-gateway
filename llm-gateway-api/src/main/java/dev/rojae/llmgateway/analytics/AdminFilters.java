package dev.rojae.llmgateway.analytics;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Typed, bounded filter values; all caller strings become SQL parameters. */
public record AdminFilters(LocalDate from, LocalDate to, ZoneId zone, String traffic,
                           String keyId, String model, String sessionId, String provider,
                           String outcome, String source, String billingMode, int limit, int offset) {
    private static final Set<String> FIELDS = Set.of("from", "to", "zone", "traffic", "keyId", "model", "sessionId", "provider", "outcome", "limit", "offset", "source", "billingMode");
    public static AdminFilters parse(MultiValueMap<String,String> params) {
        try {
            for (var entry : params.entrySet()) {
                if (!FIELDS.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid();
            }
            String zoneName = value(params,"zone","Asia/Seoul");
            if (!ZoneId.getAvailableZoneIds().contains(zoneName)) throw invalid();
            ZoneId zone = ZoneId.of(zoneName);
            LocalDate today = LocalDate.now(zone);
            LocalDate from = date(value(params,"from",today.toString()));
            LocalDate to = date(value(params,"to",today.toString()));
            from.atStartOfDay(zone).toInstant();
            to.plusDays(1).atStartOfDay(zone).toInstant();
            long span = ChronoUnit.DAYS.between(from,to) + 1;
            if (span < 1 || span > 366) throw invalid();
            String traffic = value(params,"traffic","live");
            if (!Set.of("live","test","demo","all").contains(traffic)) throw invalid();
            String source = value(params,"source","gateway");
            if (!Set.of("gateway","claude_code","all").contains(source)) throw invalid();
            String billingMode = exact(params,"billingMode");
            if (billingMode != null && !Set.of("api_key","subscription_declared","api_key_declared","unknown").contains(billingMode)) throw invalid();
            String outcome = exact(params,"outcome");
            if (outcome != null && !Set.of("success","http_error","upstream_error","cancelled").contains(outcome)) throw invalid();
            return new AdminFilters(from,to,zone,traffic,exact(params,"keyId"),exact(params,"model"),
                    exact(params,"sessionId"),exact(params,"provider"),outcome,source,billingMode,
                    number(params,"limit",25,1,100),number(params,"offset",0,0,100000));
        } catch (RuntimeException ignored) { throw invalid(); }
    }
    public Instant start() { return from.atStartOfDay(zone).toInstant(); }
    public Instant end() { return to.plusDays(1).atStartOfDay(zone).toInstant(); }
    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000-")) throw invalid();
        return LocalDate.parse(value);
    }
    private static int number(MultiValueMap<String,String> p,String name,int fallback,int min,int max) {
        int value = Integer.parseInt(value(p,name,Integer.toString(fallback)));
        if(value < min || value > max) throw invalid();
        return value;
    }
    private static String exact(MultiValueMap<String,String> p,String name) {
        String value = p.getFirst(name);
        if (value == null || value.isEmpty()) return null;
        if (value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static String value(MultiValueMap<String,String> p,String name,String fallback) {
        String value = p.getFirst(name); return value == null || value.isEmpty() ? fallback : value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid admin filters"); }
}
