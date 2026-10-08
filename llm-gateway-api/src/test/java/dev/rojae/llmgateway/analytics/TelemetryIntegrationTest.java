package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class TelemetryIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String TOKEN=TelemetrySecurityTest.TOKEN;
    static final Path SOURCES;
    static{try{SOURCES=Files.createTempDirectory("llm-gateway-telemetry-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @LocalServerPort int port;
    @Autowired AnalyticsDatabase database;
    @Autowired AnalyticsProperties properties;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("gateway.analytics.enabled",()->true);p.add("gateway.analytics.jdbc-url",()->System.getenv("GW_TEST_DB_URL"));
        p.add("gateway.analytics.username",()->System.getenv("GW_TEST_DB_USER"));p.add("gateway.analytics.password",()->System.getenv("GW_TEST_DB_PASSWORD"));
        p.add("gateway.analytics.admin-token",()->"operator-test-token-at-least-thirty-two-characters");
        p.add("gateway.analytics.access-log",()->SOURCES.resolve("access.log").toString());p.add("gateway.analytics.content-log",()->SOURCES.resolve("content.log").toString());
        p.add("gateway.analytics.poll-interval",()->"1h");
        p.add("gateway.telemetry.enabled",()->true);p.add("gateway.telemetry.token",()->TOKEN);
        p.add("gateway.telemetry.max-body-bytes",()->4096);p.add("gateway.telemetry.max-records",()->3);
    }
    @BeforeAll void schema()throws Exception{database.initialize();}
    @Test void acknowledgementFollowsCommitAndRetriesNeverDuplicateOrInventBilledCost()throws Exception{
        String request=UUID.randomUUID().toString();var record=TelemetryNormalizerTest.record("api_request");
        TelemetryNormalizerTest.attr(record,"request_id","stringValue",request);
        TelemetryNormalizerTest.attr(record,"cost_usd_micros","intValue","1200");
        byte[] payload=TelemetryNormalizerTest.envelope(record);
        var response=post(payload,TOKEN,null);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{}");
        assertThat(post(payload,TOKEN,null).getStatusCode().value()).isEqualTo(200);
        var normalized=new TelemetryNormalizer(new TelemetryProperties()).normalize(payload);
        String id=JSON.readTree(normalized.records().getFirst()).path("requestId").asText();
        try(var c=database.open();var q=c.prepareStatement("SELECT source,billing_mode,cost_usd,api_equivalent_cost_usd FROM llm_gateway_analytics.requests WHERE request_id=?")){
            q.setString(1,id);try(var r=q.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("claude_code");assertThat(r.getString(2)).isEqualTo("subscription_declared");assertThat(r.getObject(3)).isNull();assertThat(r.getBigDecimal(4)).isEqualByComparingTo("0.0012");assertThat(r.next()).isFalse();}
        }
    }
    @Test void malformedRecordReportsPartialSuccessAndPrivateFieldsNeverPersist()throws Exception{
        var good=TelemetryNormalizerTest.record("api_error");TelemetryNormalizerTest.attr(good,"request_id","stringValue",UUID.randomUUID().toString());
        TelemetryNormalizerTest.attr(good,"error","stringValue","PRIVATE_OAUTH_ERROR");
        var bad=TelemetryNormalizerTest.record("api_request");TelemetryNormalizerTest.attr(bad,"input_tokens","intValue","-1");
        var response=post(TelemetryNormalizerTest.envelope(good,bad),TOKEN,null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(response.getBody()).path("partialSuccess").path("rejectedLogRecords").asText()).isEqualTo("1");
        assertThat(response.getBody()).doesNotContain("PRIVATE_OAUTH_ERROR");
    }
    @Test void authenticationAndWireLimitsRejectBeforeAcceptingPayload()throws Exception{
        byte[] payload=TelemetryNormalizerTest.envelope(TelemetryNormalizerTest.record("user_prompt"));
        assertThat(post(payload,null,null).getStatusCode().value()).isEqualTo(401);
        assertThat(post(payload,"operator-test-token-at-least-thirty-two-characters",null).getStatusCode().value()).isEqualTo(401);
        assertThat(post(payload,TOKEN,"gzip").getStatusCode().value()).isEqualTo(415);
        assertThat(post(new byte[8192],TOKEN,null).getStatusCode().value()).isEqualTo(413);
        assertThat(post("broken PRIVATE_JSON".getBytes(),TOKEN,null).getStatusCode().value()).isEqualTo(400);
        assertThat(post(TelemetryNormalizerTest.envelope(TelemetryNormalizerTest.record("user_prompt"),TelemetryNormalizerTest.record("user_prompt"),TelemetryNormalizerTest.record("user_prompt"),TelemetryNormalizerTest.record("user_prompt")),TOKEN,null).getStatusCode().value()).isEqualTo(413);
    }
    @Test void databaseOutageReturnsRetryableFailureAndSamePayloadSucceedsAfterRecovery()throws Exception{
        var record=TelemetryNormalizerTest.record("api_request");TelemetryNormalizerTest.attr(record,"request_id","stringValue",UUID.randomUUID().toString());byte[] payload=TelemetryNormalizerTest.envelope(record);
        String original=properties.getJdbcUrl();
        try{database.close();properties.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/PRIVATE_DATABASE");var response=post(payload,TOKEN,null);assertThat(response.getStatusCode().value()).isEqualTo(503);assertThat(response.getBody()).doesNotContain("PRIVATE_DATABASE");}
        finally{database.close();properties.setJdbcUrl(original);}
        assertThat(post(payload,TOKEN,null).getStatusCode().value()).isEqualTo(200);
    }

    @Test void normalizedHttpPathsAndChunkedBodiesEnforceAuthAndSizeLimit()throws Exception{
        var client=java.net.http.HttpClient.newHttpClient();
        for(String path:new String[]{"/tele%6Detry/v1/logs","/telemetry/v1/logs;v=1"}){
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).POST(java.net.http.HttpRequest.BodyPublishers.ofString("PRIVATE_BODY")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(JSON.readTree(response.body()).path("code").asInt()).isEqualTo(16);
            assertThat(response.body()).doesNotContain("PRIVATE_BODY");
        }
        var response=WebClient.create("http://127.0.0.1:"+port).post().uri("/telemetry/v1/logs")
                .header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON)
                .body(org.springframework.web.reactive.function.BodyInserters.fromDataBuffers(reactor.core.publisher.Flux.range(0,8).map(i->new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(new byte[1024]))))
                .exchangeToMono(r->r.toEntity(String.class)).block(Duration.ofSeconds(10));
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(JSON.readTree(response.getBody()).path("code").asInt()).isEqualTo(8);
    }
    @Test void failureOnSecondInsertRollsBackBatchBeforeRetryableResponse()throws Exception{
        var first=TelemetryNormalizerTest.record("api_request");var second=TelemetryNormalizerTest.record("api_request");
        TelemetryNormalizerTest.attr(first,"request_id","stringValue",UUID.randomUUID().toString());TelemetryNormalizerTest.attr(second,"request_id","stringValue",UUID.randomUUID().toString());
        byte[] payload=TelemetryNormalizerTest.envelope(first,second);
        var normalized=new TelemetryNormalizer(new TelemetryProperties()).normalize(payload);
        String firstId=JSON.readTree(normalized.records().get(0)).path("requestId").asText(),secondId=JSON.readTree(normalized.records().get(1)).path("requestId").asText();
        try(var c=database.open();var q=c.createStatement()){
            q.execute("CREATE OR REPLACE FUNCTION llm_gateway_analytics.reject_telemetry_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+secondId+"' THEN RAISE EXCEPTION 'PRIVATE_INSERT_FAILURE'; END IF; RETURN NEW; END $$");
            q.execute("CREATE TRIGGER reject_telemetry_test BEFORE INSERT ON llm_gateway_analytics.requests FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics.reject_telemetry_test()");
        }
        try{
            var response=post(payload,TOKEN,null);assertThat(response.getStatusCode().value()).isEqualTo(503);assertThat(response.getBody()).doesNotContain("PRIVATE_INSERT_FAILURE");
            try(var c=database.open();var q=c.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.requests WHERE request_id IN (?,?)")){q.setString(1,firstId);q.setString(2,secondId);try(var r=q.executeQuery()){r.next();assertThat(r.getInt(1)).isZero();}}
        }finally{try(var c=database.open();var q=c.createStatement()){q.execute("DROP TRIGGER reject_telemetry_test ON llm_gateway_analytics.requests");q.execute("DROP FUNCTION llm_gateway_analytics.reject_telemetry_test()");}}
        assertThat(post(payload,TOKEN,null).getStatusCode().value()).isEqualTo(200);
        try(var c=database.open();var q=c.prepareStatement("SELECT count(*) FROM llm_gateway_analytics.requests WHERE request_id IN (?,?)")){q.setString(1,firstId);q.setString(2,secondId);try(var r=q.executeQuery()){r.next();assertThat(r.getInt(1)).isEqualTo(2);}}
    }
    private ResponseEntity<String> post(byte[] payload,String token,String encoding){
        var request=WebClient.create("http://127.0.0.1:"+port).post().uri("/telemetry/v1/logs").contentType(MediaType.APPLICATION_JSON);
        if(token!=null)request.header("Authorization","Bearer "+token);if(encoding!=null)request.header("Content-Encoding",encoding);
        return request.bodyValue(payload).exchangeToMono(response->response.toEntity(String.class)).block(Duration.ofSeconds(10));
    }
}
