package dev.rojae.llmgateway.analytics;

import java.nio.charset.StandardCharsets;
import dev.rojae.llmgateway.security.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(-100)
public final class AdminAuthFilter implements WebFilter {
    private static final PathPattern API = PathPatternParser.defaultInstance.parse("/admin/api/{*rest}");
    private static final PathPattern ADMIN = PathPatternParser.defaultInstance.parse("/admin/{*rest}");
    private final AnalyticsProperties properties;
    private final GatewaySecurityProperties security;
    public AdminAuthFilter(AnalyticsProperties properties) { this(properties,new GatewaySecurityProperties()); }
    @Autowired public AdminAuthFilter(AnalyticsProperties properties,GatewaySecurityProperties security) {this.properties=properties;this.security=security;}

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (ADMIN.matches(path)) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().getHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponse().getHeaders().set("Referrer-Policy","no-referrer");
        }
        if (!API.matches(path)) return chain.filter(exchange);
        if (security.isEnterprise()) return chain.filter(exchange);
        String token = properties.getAdminToken();
        if (!properties.isEnabled() || token == null || token.length() < 32 || token.isBlank()) {
            return deny(exchange,HttpStatus.SERVICE_UNAVAILABLE,"admin_unavailable","Admin analytics is unavailable");
        }
        var authorization = exchange.getRequest().getHeaders().get("Authorization");
        boolean valid = false;
        if (authorization != null && authorization.size() == 1) {
            String header = authorization.getFirst();
            if (header.length() > 7 && header.regionMatches(true,0,"Bearer ",0,7)) {
                String supplied = header.substring(7);
                if (supplied.length() <= 4096 && supplied.codePoints().noneMatch(Character::isWhitespace)) {
                    valid = MessageDigest.isEqual(digest(token),digest(supplied));
                }
            }
        }
        if (!valid) return deny(exchange,HttpStatus.UNAUTHORIZED,"unauthorized","Valid operator token required");
        exchange.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,new TenantPrincipal(properties.getTenantId(),"local-admin",Set.of("admin")));
        return chain.filter(exchange);
    }
    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static Mono<Void> deny(ServerWebExchange exchange,HttpStatus status,String code,String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\"}}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
