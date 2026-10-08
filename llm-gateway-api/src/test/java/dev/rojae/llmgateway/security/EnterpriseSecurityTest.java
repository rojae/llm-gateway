package dev.rojae.llmgateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.security.web.server.csrf.CsrfToken;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EnterpriseSecurityTest.Probe.class)
@EnabledIfEnvironmentVariable(named="GW_TEST_REDIS_URL",matches=".+")
class EnterpriseSecurityTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final RSAKey KEY;
    private static final HttpServer IDP;
    private static final String ISSUER;
    private static final Map<String,Map<String,String>> AUTHORIZATIONS=new ConcurrentHashMap<>();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("test-key").generate();
            IDP=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            ISSUER="http://127.0.0.1:"+IDP.getAddress().getPort();
            IDP.createContext("/.well-known/openid-configuration",e->respond(e,Map.of(
                    "issuer",ISSUER,"authorization_endpoint",ISSUER+"/authorize","token_endpoint",ISSUER+"/token",
                    "jwks_uri",ISSUER+"/jwks","response_types_supported",List.of("code"),"subject_types_supported",List.of("public"),
                    "id_token_signing_alg_values_supported",List.of("RS256"))));
            IDP.createContext("/jwks",e->respond(e,new JWKSet(KEY.toPublicJWK()).toJSONObject()));
            IDP.createContext("/authorize",e->{
                var query=parse(e.getRequestURI().getRawQuery());String code=UUID.randomUUID().toString();AUTHORIZATIONS.put(code,query);
                e.getResponseHeaders().set("Location",query.get("redirect_uri")+"?code="+code+"&state="+query.get("state"));
                e.sendResponseHeaders(302,-1);e.close();
            });
            IDP.createContext("/token",e->{
                try {
                    var params=parse(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    var authorization=AUTHORIZATIONS.remove(params.get("code"));
                    if(authorization==null||params.get("code_verifier")==null)throw new IllegalArgumentException();
                    String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(params.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                    if(!challenge.equals(authorization.get("code_challenge")))throw new IllegalArgumentException();
                    String nonce=authorization.get("nonce");
                    respond(e,Map.of("access_token","mock-access-token","token_type","Bearer","expires_in",300,
                            "id_token",jwt("llm-gateway-client","tenant-a",List.of("operator"),ISSUER,Instant.now().plusSeconds(300),nonce)));
                } catch(Exception exception){e.sendResponseHeaders(400,-1);e.close();}
            });
            IDP.start();
        } catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("gateway.security.mode",()->"enterprise");p.add("gateway.security.issuer-uri",()->ISSUER);
        p.add("gateway.security.audience",()->"llm-gateway-api");p.add("gateway.security.client-id",()->"llm-gateway-client");p.add("gateway.security.client-secret",()->"mock-client-secret");
        p.add("gateway.security.allowed-tenants",()->"tenant-a,tenant-b");p.add("gateway.security.external-base-url",()->"http://127.0.0.1:9991");
        p.add("gateway.security.admin-ui-url",()->"http://localhost:3000/admin");
        p.add("gateway.security.allow-insecure-local-oidc",()->true);p.add("gateway.analytics.enabled",()->false);
        p.add("gateway.analytics.tenant-id",()->"tenant-a");p.add("gateway.analytics.admin-token",()->"");p.add("gateway.telemetry.token",()->"");
        p.add("spring.data.redis.url",()->System.getenv("GW_TEST_REDIS_URL"));
    }
    @LocalServerPort int port;
    private String app(String path){return "http://127.0.0.1:"+port+path;}
    @AfterAll static void close(){IDP.stop(0);}

    @Test void signedJwtRequiresIssuerAudienceExpiryAllowedTenantAndFiniteRoles() throws Exception {
        var client=HttpClient.newHttpClient();
        String valid=jwt("llm-gateway-api","tenant-a",List.of("viewer"),ISSUER,Instant.now().plusSeconds(300),null);
        var response=client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/identity"))).header("Authorization","Bearer "+valid)
                .header("X-Tenant-Id","tenant-b").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("tenant-a").doesNotContain("tenant-b");
        for(String token:List.of("not-jwt",jwt("wrong","tenant-a",List.of("viewer"),ISSUER,Instant.now().plusSeconds(300),null),
                jwt("llm-gateway-api","tenant-a",List.of("viewer"),ISSUER+"/wrong",Instant.now().plusSeconds(300),null),
                jwt("llm-gateway-api","tenant-a",List.of("viewer"),ISSUER,Instant.now().minusSeconds(300),null),
                jwt("llm-gateway-api","outsider",List.of("admin"),ISSUER,Instant.now().plusSeconds(300),null),
                jwt("llm-gateway-api","tenant-a",List.of("root"),ISSUER,Instant.now().plusSeconds(300),null))) {
            assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/identity"))).header("Authorization","Bearer "+token)
                    .GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        for(String path:List.of("/admin/%61pi/test/identity","/admin/api;v=1/test/identity","/admin/api/unknown")) {
            assertThat(client.send(HttpRequest.newBuilder(URI.create(app(path))).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(400,401);
        }
        JWTClaimsSet original=SignedJWT.parse(valid).getJWTClaimsSet();
        for(JWTClaimsSet claims:List.of(new JWTClaimsSet.Builder(original).notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build(),
                new JWTClaimsSet.Builder(original).subject(null).build(),new JWTClaimsSet.Builder(original).claim("tenant_id",null).build(),
                new JWTClaimsSet.Builder(original).expirationTime(null).build())) {
            var modified=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);
            modified.sign(new RSASSASigner(KEY));
            assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/identity"))).header("Authorization","Bearer "+modified.serialize()).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        var forged=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),original);
        forged.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/identity"))).header("Authorization","Bearer "+forged.serialize()).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/content"))).header("Authorization","Bearer "+valid)
                .GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/v1/no-such-endpoint"))).header("Authorization","Bearer provider-personal-key")
                .GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    @Test void oidcCodePkceUsesFixedCallbackRedisSessionAndCsrfLogout() throws Exception {
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        var client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        var start=client.send(HttpRequest.newBuilder(URI.create(app("/oauth2/authorization/enterprise"))).header("X-Forwarded-Host","evil.example").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(start.statusCode()).isEqualTo(302);
        String authorize=start.headers().firstValue("location").orElseThrow();var params=parse(URI.create(authorize).getRawQuery());
        assertThat(params.get("code_challenge_method")).isEqualTo("S256");
        assertThat(params.get("redirect_uri")).isEqualTo("http://127.0.0.1:9991/login/oauth2/code/enterprise");
        assertThat(params.get("state")).isNotBlank();assertThat(params.get("nonce")).isNotBlank();
        var approved=client.send(HttpRequest.newBuilder(URI.create(authorize)).GET().build(),HttpResponse.BodyHandlers.ofString());
        String callback=URI.create(approved.headers().firstValue("location").orElseThrow()).getRawQuery();
        var loggedIn=client.send(HttpRequest.newBuilder(URI.create(app("/login/oauth2/code/enterprise?"+callback))).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(loggedIn.statusCode()).isEqualTo(302);assertThat(loggedIn.headers().firstValue("location").orElseThrow()).isEqualTo("http://localhost:3000/admin");
        var identity=client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/session"))).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(identity.statusCode()).isEqualTo(200);var body=JSON.readTree(identity.body());assertThat(body.path("tenantId").asText()).isEqualTo("tenant-a");
        String csrf=body.path("csrfToken").asText();assertThat(csrf).isNotBlank();
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/auth/logout"))).header("Authorization","Bearer arbitrary").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401,403);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/auth/logout"))).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        var logout=client.send(HttpRequest.newBuilder(URI.create(app("/admin/auth/logout"))).header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(app("/admin/api/test/session"))).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    @RestController static class Probe {
        private final AdminAccess access;Probe(AdminAccess access){this.access=access;}
        @GetMapping("/admin/api/test/identity") Mono<TenantPrincipal> identity(ServerWebExchange e){return access.require(e,"usage:read");}
        @GetMapping("/admin/api/test/content") Mono<TenantPrincipal> content(ServerWebExchange e){return access.require(e,"content:read");}
        @GetMapping("/admin/api/test/session") Mono<Map<String,Object>> session(ServerWebExchange e){
            return access.require(e,"usage:read").flatMap(p->{Mono<CsrfToken> csrf=e.getAttribute(CsrfToken.class.getName());return csrf.map(t->Map.of("tenantId",p.tenantId(),"csrfToken",t.getToken()));});
        }
    }
    private static Map<String,String> parse(String query){var result=new HashMap<String,String>();for(String pair:query.split("&")){String[] part=pair.split("=",2);result.put(URLDecoder.decode(part[0],StandardCharsets.UTF_8),URLDecoder.decode(part.length>1?part[1]:"",StandardCharsets.UTF_8));}return result;}
    private static void respond(com.sun.net.httpserver.HttpExchange e,Object value)throws java.io.IOException {byte[] bytes=JSON.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();}
    private static String jwt(String audience,String tenant,List<String> roles,String issuer,Instant expires,String nonce)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("mock-user").audience(audience).issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(expires))
                .claim("tenant_id",tenant).claim("roles",roles);if(nonce!=null)claims.claim("nonce",nonce);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(KEY));return jwt.serialize();
    }
}
