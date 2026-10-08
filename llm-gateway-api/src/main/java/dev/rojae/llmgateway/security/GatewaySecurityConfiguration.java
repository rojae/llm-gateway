package dev.rojae.llmgateway.security;

import dev.rojae.llmgateway.analytics.AnalyticsProperties;
import dev.rojae.llmgateway.analytics.TelemetryProperties;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.oidc.userinfo.*;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.*;
import org.springframework.security.web.server.authentication.logout.*;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={SessionAutoConfiguration.class,ReactiveUserDetailsServiceAutoConfiguration.class})
public class GatewaySecurityConfiguration {
    /** Never let OAuth resource-server processing intercept provider credentials. */
    @Bean @Order(100) SecurityWebFilterChain publicAndProxy(ServerHttpSecurity http) {
        return http.securityContextRepository(org.springframework.security.web.server.context.NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(e->e.anyExchange().permitAll()).csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable).requestCache(ServerHttpSecurity.RequestCacheSpec::disable).build();
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="gateway.security.mode",havingValue="enterprise")
    @EnableRedisWebSession(redisNamespace="llm-gateway:admin:sessions")
    static class Enterprise {
        Enterprise(GatewaySecurityProperties p,AnalyticsProperties analytics,TelemetryProperties telemetry) {
            p.validate();
            if(!analytics.getAdminToken().isBlank()||!telemetry.getToken().isBlank())
                throw new IllegalArgumentException("Enterprise mode does not accept legacy shared credentials");
            if(!p.getAllowedTenants().contains(analytics.getTenantId()))
                throw new IllegalArgumentException("Trusted analytics tenant is not allowed");
        }
        @Bean ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> sessionTimeout(
                @Value("${spring.session.timeout:30m}") Duration timeout) {
            if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofHours(24))>0)throw new IllegalArgumentException("Invalid admin session timeout");
            return repository->repository.setDefaultMaxInactiveInterval(timeout);
        }
        @Bean WebSessionIdResolver webSessionIdResolver(GatewaySecurityProperties p) {
            var resolver=new CookieWebSessionIdResolver();resolver.setCookieName("LLMGATEWAYSESSION");
            resolver.addCookieInitializer(cookie->cookie.httpOnly(true).secure("https".equals(java.net.URI.create(p.getExternalBaseUrl()).getScheme())).sameSite("Lax").path("/"));
            return resolver;
        }
        @Bean ReactiveClientRegistrationRepository clientRegistrationRepository(GatewaySecurityProperties p) {
            String base=p.getExternalBaseUrl().replaceAll("/+$","");
            ClientRegistration registration=ClientRegistrations.fromIssuerLocation(p.getIssuerUri())
                    .registrationId("enterprise").clientId(p.getClientId()).clientSecret(p.getClientSecret())
                    .scope("openid").redirectUri(base+"/login/oauth2/code/enterprise").build();
            return new InMemoryReactiveClientRegistrationRepository(registration);
        }
        @Bean ReactiveJwtDecoder adminJwtDecoder(GatewaySecurityProperties p,TenantClaims claims,ReactiveClientRegistrationRepository registrations) {
            ClientRegistration registration=registrations.findByRegistrationId("enterprise").block();
            NimbusReactiveJwtDecoder decoder=NimbusReactiveJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri()).build();
            OAuth2TokenValidator<Jwt> tenant=jwt->{
                try {
                    if(jwt.getExpiresAt()==null||!jwt.getAudience().contains(p.getAudience()))throw new IllegalArgumentException();
                    claims.resolve(jwt.getClaims());return OAuth2TokenValidatorResult.success();
                } catch(Exception ignored){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid admin identity",null));}
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(p.getIssuerUri()),tenant));
            return decoder;
        }
        @Bean ReactiveOAuth2UserService<OidcUserRequest,OidcUser> oidcUserService(TenantClaims claims) {
            OidcReactiveOAuth2UserService delegate=new OidcReactiveOAuth2UserService();
            return request->delegate.loadUser(request).map(user->{
                // Only signed ID-token claims authorize; userinfo cannot elevate tenant or roles.
                TenantPrincipal principal=claims.resolve(user.getIdToken().getClaims());
                return new DefaultOidcUser(principal.roles().stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList(),user.getIdToken());
            });
        }
        @Bean @Order(0) SecurityWebFilterChain enterpriseAdmin(ServerHttpSecurity http,
                ReactiveClientRegistrationRepository registrations,ReactiveJwtDecoder adminJwtDecoder,GatewaySecurityProperties p) {
            var resolver=new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/admin/**","/oauth2/**","/login/oauth2/**"))
                    .authorizeExchange(e->e.pathMatchers("/admin/api/**").authenticated().anyExchange().permitAll())
                    .exceptionHandling(e->e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .oauth2Login(o->o.authorizationRequestResolver(resolver)
                            .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler(p.getAdminUiUrl()))
                            .authenticationFailureHandler(new RedirectServerAuthenticationFailureHandler(p.getAdminUiUrl()+"?login=failed")))
                    .oauth2ResourceServer(o->o.jwt(j->j.jwtDecoder(adminJwtDecoder)))
                    // Keep CSRF for every unsafe admin request, even if it carries Authorization.
                    .csrf(c->c.requireCsrfProtectionMatcher(exchange->{
                        boolean safe=Set.of(HttpMethod.GET,HttpMethod.HEAD,HttpMethod.OPTIONS,HttpMethod.TRACE).contains(exchange.getRequest().getMethod());
                        return safe?org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch()
                                :org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.match();
                    }))
                    .logout(l->l.requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,"/admin/auth/logout"))
                            // CSRF logout cleanup also touches the session. Invalidate only after every handler completes.
                            .logoutSuccessHandler((exchange, authentication) -> exchange.getExchange().getSession()
                                    .flatMap(org.springframework.web.server.WebSession::invalidate)
                                    .then(Mono.defer(() -> {
                                        exchange.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                                        return exchange.getExchange().getResponse().setComplete();
                                    }))))
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable).build();
        }
    }
}
