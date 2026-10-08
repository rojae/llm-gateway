package dev.rojae.llmgateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "gateway.security.mode=local","gateway.analytics.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LocalSecurityIsolationTest {
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    @Test void localProxyRoutesNeedNeitherRedisNorAdminAuthentication() throws Exception {
        assertThat(context.getBeansOfType(ReactiveSessionRepository.class)).isEmpty();
        var client=HttpClient.newHttpClient();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/no-such-endpoint"))
                .header("Authorization","Bearer provider-personal-key").header("Cookie","LLMGATEWAYSESSION=untrusted-session")
                .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        var config=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/auth/config"))
                .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.body()).contains("\"mode\":\"local\"").doesNotContain("clientSecret");
        var page=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/admin/"))
                .GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(404);
    }
}
