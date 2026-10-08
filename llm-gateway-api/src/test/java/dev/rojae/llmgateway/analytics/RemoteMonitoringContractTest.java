package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

/** HTTP contracts backed by real PostgreSQL; fixtures are synthetic, with measured token values. */
@EnabledIfEnvironmentVariable(named="GW_TEST_DB_URL",matches=".+")
class RemoteMonitoringContractTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String SECRET_PROMPT="PRIVATE_TEST_PROMPT <script>alert('fixture')</script>";
    final String tenant="remote-a-"+UUID.randomUUID(),otherTenant="remote-b-"+UUID.randomUUID();
    AnalyticsDatabase database; CollectorCredentialService credentials; AuditService audit; AdminAccess access;
    CollectorCredentialController collectors; MonitoringController monitoring; TelemetryController telemetry;
    TelemetryAuthFilter auth; WebTestClient client;

    @BeforeEach void setup()throws Exception {
        var analytics=new AnalyticsProperties();analytics.setEnabled(true);analytics.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));
        analytics.setUsername(System.getenv("GW_TEST_DB_USER"));analytics.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        database=new AnalyticsDatabase(analytics);database.initialize();audit=new AuditService(database);credentials=new CollectorCredentialService(database,audit);
        var adminSecurity=new GatewaySecurityProperties();var beans=new StaticListableBeanFactory();beans.addBean("audit",audit);
        access=new AdminAccess(adminSecurity,new TenantClaims(adminSecurity),beans.getBeanProvider(AuditService.class));
        collectors=new CollectorCredentialController(credentials,access);
        monitoring=new MonitoringController(new MonitoringQueries(database,audit),access);
        var properties=new TelemetryProperties();properties.setEnabled(true);properties.setToken("");
        var security=new GatewaySecurityProperties();security.setMode("enterprise");security.setAllowedTenants(List.of(tenant,otherTenant));
        auth=new TelemetryAuthFilter(properties,analytics,new GatewayProperties("http://localhost","",Duration.ofSeconds(1),Duration.ofSeconds(1),true),security,credentials);
        telemetry=new TelemetryController(database,properties,credentials);
        client=WebTestClient.bindToController(collectors,monitoring,telemetry).webFilter((exchange,chain)->{
            String t=exchange.getRequest().getHeaders().getFirst("Test-Tenant");
            if(t!=null)exchange.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,new TenantPrincipal(t,"test-issuer",Set.of(Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("Test-Role")).orElse("admin"))));
            return auth.filter(exchange,chain);
        }).configureClient().responseTimeout(Duration.ofSeconds(10)).build();
    }
    @AfterEach void close(){if(auth!=null)auth.stop();if(telemetry!=null)telemetry.stop();if(monitoring!=null)monitoring.close();if(collectors!=null)collectors.close();if(access!=null)access.close();if(database!=null)database.close();}

    @Test void claudeQuestionUsesNativePromptLinkAndExactMeasuredUsage()throws Exception {
        UUID owner=account(tenant,"Claude owner");var key=key(tenant,owner,null);
        ingest(key.token(),claudePrompt(),claudeUsage()).expectStatus().isOk();
        JsonNode session=first(get("/sessions?accountId="+owner,tenant,"viewer"));
        JsonNode question=first(get("/sessions/"+session.path("id").asText()+"/questions",tenant,"viewer"));
        assertThat(question.path("inputTokens").asLong()).isEqualTo(431);
        assertThat(question.path("outputTokens").asLong()).isEqualTo(126);
        assertThat(question.path("totalTokens").asLong()).isEqualTo(557);
        assertThat(question.path("apiEquivalentCostUsd").decimalValue()).isEqualByComparingTo("0.001061");
        assertThat(count("monitoring_question_contents",tenant)).isEqualTo(1);
    }

    @Test void codexResponseObservationsNeverBecomeFinalQuestionUsage()throws Exception {
        UUID owner=account(tenant,"Codex owner");var key=key(tenant,owner,null);
        var prompt=codex("user_prompt","1");attr(prompt,"prompt",SECRET_PROMPT);
        var first=codex("sse_event","2");attr(first,"event.kind","response.completed");number(first,"input_token_count",12497);number(first,"output_token_count",0);
        var second=codex("sse_event","3");attr(second,"event.kind","response.completed");number(second,"input_token_count",29220);number(second,"output_token_count",5);number(second,"cached_token_count",12288);
        ingest(key.token(),prompt,first,second).expectStatus().isOk();
        JsonNode session=first(get("/sessions?accountId="+owner,tenant,"viewer"));String sid=session.path("id").asText();
        assertThat(session.path("totalTokens").isNull()).isTrue();assertThat(session.path("apiEquivalentCostUsd").isNull()).isTrue();
        JsonNode question=first(get("/sessions/"+sid+"/questions",tenant,"viewer"));
        for(String field:List.of("inputTokens","outputTokens","totalTokens","apiEquivalentCostUsd")){assertThat(question.has(field)).as(field+" is explicit").isTrue();assertThat(question.path(field).isNull()).as(field).isTrue();}
        JsonNode events=get("/sessions/"+sid+"/events",tenant,"viewer");
        List<Long> observed=new ArrayList<>();events.path("items").forEach(e->{if(e.hasNonNull("inputTokens"))observed.add(e.path("inputTokens").asLong());});
        assertThat(observed).containsExactlyInAnyOrder(12497L,29220L);
        assertThat(events.toString()).doesNotContain(SECRET_PROMPT);
    }

    @Test void nativePromptAndRequestIdsWithoutMainScopeKeepUsageUnattributed()throws Exception {
        UUID owner=account(tenant,"Unknown scope owner");var key=key(tenant,owner,null);
        var usage=claudeUsage();
        var attributes=usage.withArray("attributes");for(int i=attributes.size()-1;i>=0;i--)if(attributes.get(i).path("key").asText().equals("query_source"))attributes.remove(i);
        ingest(key.token(),claudePrompt(),usage).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();
        JsonNode question=first(get("/sessions/"+sid+"/questions",tenant,"viewer"));
        for(String field:List.of("inputTokens","outputTokens","totalTokens","apiEquivalentCostUsd")){assertThat(question.has(field)).as(field+" is explicit").isTrue();assertThat(question.path(field).isNull()).as(field).isTrue();}
        assertThat(question.path("attribution").asText()).isEqualTo("unattributed");
        JsonNode events=get("/sessions/"+sid+"/events",tenant,"viewer");boolean usageFound=false;
        for(JsonNode event:events.path("items"))if(event.path("eventKind").asText().equals("api_request")){
            usageFound=true;assertThat(event.path("inputTokens").asLong()).isEqualTo(431);assertThat(event.path("outputTokens").asLong()).isEqualTo(126);
            assertThat(event.path("inputTokens").asLong()+event.path("outputTokens").asLong()).isEqualTo(557);
            assertThat(event.path("attribution").asText()).isEqualTo("unattributed");
        }
        assertThat(usageFound).isTrue();
    }

    @Test void replayReverseOrderAndTokenRotationPreserveQuestionAndConsumptionIdentity()throws Exception {
        UUID owner=account(tenant,"Rotation owner");var key=key(tenant,owner,null);
        var prompt=claudePrompt();var usage=claudeUsage();
        ingest(key.token(),usage).expectStatus().isOk();ingest(key.token(),prompt).expectStatus().isOk();
        for(int repeat=0;repeat<3;repeat++)ingest(key.token(),prompt,usage).expectStatus().isOk();
        var rotated=key(tenant,owner,key.credential().registrationId());credentials.revoke(tenant,"test-issuer",key.credential().id());
        ingest(rotated.token(),usage,prompt).expectStatus().isOk();
        assertThat(count("monitoring_sessions",tenant)).isEqualTo(1);assertThat(count("monitoring_questions",tenant)).isEqualTo(1);assertThat(count("monitoring_events",tenant)).isEqualTo(2);
        JsonNode session=first(get("/sessions?accountId="+owner,tenant,"viewer"));
        assertThat(first(get("/sessions/"+session.path("id").asText()+"/questions",tenant,"viewer")).path("totalTokens").asLong()).isEqualTo(557);
    }

    @Test void forgedBodyIdentityCannotMoveRowsAndExternalIdsAreOwnerAndTenantScoped()throws Exception {
        UUID owner=account(tenant,"Owner A"),other=account(tenant,"Owner B"),foreign=account(otherTenant,"Owner C");
        var prompt=claudePrompt();attr(prompt,"tenant_id",otherTenant);attr(prompt,"user.account",foreign.toString());attr(prompt,"ownerAccountId",foreign.toString());
        for(var key:List.of(key(tenant,owner,null),key(tenant,other,null),key(otherTenant,foreign,null)))ingest(key.token(),prompt,claudeUsage()).expectStatus().isOk();
        assertThat(count("monitoring_sessions",tenant)).isEqualTo(2);assertThat(count("monitoring_sessions",otherTenant)).isEqualTo(1);
        assertThat(get("/sessions?accountId="+owner,tenant,"viewer").path("items")).hasSize(1);
        assertThat(get("/sessions?accountId="+foreign,tenant,"viewer").path("items")).isEmpty();
        String foreignSession=first(get("/sessions?accountId="+foreign,otherTenant,"viewer")).path("id").asText();
        client.get().uri("/admin/api/monitoring/sessions/"+foreignSession+"/questions").header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
        client.post().uri("/admin/api/collectors").header("Test-Tenant",tenant).bodyValue(Map.of("name","invalid owner","ownerAccountId",foreign.toString())).exchange().expectStatus().isBadRequest();
    }

    @Test void questionContentRequiresPermissionAndSuccessfulReadIsAudited()throws Exception {
        UUID owner=account(tenant,"Private owner");var key=key(tenant,owner,null);ingest(key.token(),claudePrompt(),claudeUsage()).expectStatus().isOk();
        JsonNode sessions=get("/sessions?accountId="+owner,tenant,"viewer");String sid=first(sessions).path("id").asText();
        JsonNode questions=get("/sessions/"+sid+"/questions",tenant,"viewer");String qid=first(questions).path("id").asText();
        for(JsonNode response:List.of(get("/accounts",tenant,"viewer"),sessions,questions,get("/sessions/"+sid+"/events",tenant,"viewer")))assertThat(response.toString()).doesNotContain(SECRET_PROMPT,"PRIVATE_PROVIDER_EMAIL","sk-fixture-secret","promptPreview","rawBody");
        JsonNode denied=get("/questions/"+qid,tenant,"viewer");assertThat(denied.path("content").isNull()).isTrue();assertThat(denied.toString()).doesNotContain(SECRET_PROMPT);
        JsonNode content=get("/questions/"+qid,tenant,"operator");assertThat(content.path("content").path("text").asText()).isEqualTo(SECRET_PROMPT);
        assertThat(audit.list(tenant,100,0).items()).anySatisfy(e->{assertThat(e.action()).isEqualTo("content.read");assertThat(e.outcome()).isEqualTo("success");});
    }

    @Test void ownerlessLegacyUsageIsAcceptedButNewPromptAndCodexRecordsAreExplicitlyRejected()throws Exception {
        var legacy=credentials.create(tenant,"legacy-issuer","legacy",30,"subscription_declared");
        JsonNode response=JSON.readTree(ingest(legacy.token(),claudeUsage(),claudePrompt(),codex("user_prompt","1")).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody());
        assertThat(response.path("partialSuccess").path("rejectedLogRecords").asInt()).isEqualTo(2);
        assertThat(response.path("partialSuccess").path("errorMessage").asText()).isEqualTo("account_not_linked");
        assertThat(count("requests",tenant)).isEqualTo(1);assertThat(count("monitoring_questions",tenant)).isZero();assertThat(count("monitoring_events",tenant)).isZero();
    }

    @Test void revokedAndExpiredKeysCannotPersistMonitoringPayloads()throws Exception {
        UUID owner=account(tenant,"Credential owner");var revoked=key(tenant,owner,null);var expired=key(tenant,owner,null);
        credentials.revoke(tenant,"test-issuer",revoked.credential().id());
        try(var connection=database.open();var query=connection.prepareStatement("UPDATE llm_gateway_analytics.collector_credentials SET expires_at=now()-interval '1 second' WHERE id=?")){query.setObject(1,expired.credential().id());query.executeUpdate();}
        for(var key:List.of(revoked,expired))ingest(key.token(),claudePrompt(),claudeUsage()).expectStatus().isUnauthorized();
        assertThat(count("monitoring_events",tenant)).isZero();assertThat(count("monitoring_question_contents",tenant)).isZero();
    }

    @Test void contentRetentionTombstonePreventsReplayAndLatePromptRestoration()throws Exception {
        UUID owner=account(tenant,"Retention owner");var key=key(tenant,owner,null);var prompt=claudePrompt();ingest(key.token(),prompt,claudeUsage()).expectStatus().isOk();
        try(var connection=database.open()){
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_question_contents SET received_at=now()-interval '3 days' WHERE tenant_id=?")){query.setString(1,tenant);query.executeUpdate();}
            MonitoringStore.prune(connection,tenant,0,1);
        }
        ingest(key.token(),prompt).expectStatus().isOk();
        var late=claudePrompt();attr(late,"request_id","late-prompt-distinct-event");ingest(key.token(),late).expectStatus().isOk();
        assertThat(count("monitoring_question_contents",tenant)).isZero();assertThat(count("monitoring_questions",tenant)).isEqualTo(1);
    }

    @Test void acknowledgementWaitsForAtomicCommitAndFailureCanBeRetried()throws Exception {
        UUID owner=account(tenant,"Atomic owner");var key=key(tenant,owner,null);var prompt=claudePrompt();var usage=claudeUsage();
        String hook="contract_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=database.open();var sql=connection.createStatement()){
            sql.execute("CREATE FUNCTION llm_gateway_analytics."+hook+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' THEN RAISE EXCEPTION 'synthetic commit failure'; END IF; RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER "+hook+" BEFORE INSERT OR UPDATE ON llm_gateway_analytics.monitoring_status FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics."+hook+"()");
        }
        try{
            ingest(key.token(),prompt,usage,claudeResponse("atomic-response","PRIVATE_ATOMIC_RESPONSE")).expectStatus().isEqualTo(503);
            for(String table:List.of("monitoring_sessions","monitoring_questions","monitoring_question_contents","monitoring_events","monitoring_event_identities","monitoring_responses","monitoring_response_contents"))assertThat(count(table,tenant)).as(table).isZero();
        }finally{
            try(var connection=database.open();var sql=connection.createStatement()){
                sql.execute("DROP TRIGGER "+hook+" ON llm_gateway_analytics.monitoring_status");sql.execute("DROP FUNCTION llm_gateway_analytics."+hook+"()");
            }
        }
        ingest(key.token(),prompt,usage,claudeResponse("atomic-response","PRIVATE_ATOMIC_RESPONSE")).expectStatus().isOk();
        assertThat(count("monitoring_events",tenant)).isEqualTo(3);assertThat(count("monitoring_question_contents",tenant)).isEqualTo(1);
    }

    @Test void expiredSessionPayloadCannotBeRestoredByReplayOrNewLateEvent()throws Exception {
        UUID owner=account(tenant,"Session retention owner");var key=key(tenant,owner,null);var prompt=claudePrompt();var usage=claudeUsage();ingest(key.token(),prompt,usage).expectStatus().isOk();
        try(var connection=database.open()){
            try(var query=connection.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET first_seen_at=now()-interval '3 days',last_seen_at=now()-interval '3 days' WHERE tenant_id=?")){query.setString(1,tenant);query.executeUpdate();}
            MonitoringStore.prune(connection,tenant,1,1);
        }
        ingest(key.token(),prompt,usage).expectStatus().isOk();var late=claudeUsage();
        late.withArray("attributes").forEach(a->{if(a.path("key").asText().equals("request_id"))((ObjectNode)a.path("value")).put("stringValue","late-distinct-request");});
        ingest(key.token(),late).expectStatus().isOk();
        assertThat(count("monitoring_events",tenant)).isZero();assertThat(count("monitoring_question_contents",tenant)).isZero();assertThat(get("/sessions?accountId="+owner,tenant,"viewer").path("items")).isEmpty();
    }

    @Test void questionRetentionExpiresOldPayloadInsideAnActiveSessionWithoutRevival()throws Exception {
        UUID owner=account(tenant,"Active session retention");var key=key(tenant,owner,null);
        var oldPrompt=claudePrompt();var oldUsage=claudeUsage();ingest(key.token(),oldPrompt,oldUsage).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();
        String oldQuestion=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();
        try(var connection=database.open()){
            for(String sql:List.of(
                    "UPDATE llm_gateway_analytics.monitoring_questions SET received_at=now()-interval '3 days' WHERE tenant_id=? AND id=?",
                    "UPDATE llm_gateway_analytics.monitoring_events SET received_at=now()-interval '3 days' WHERE tenant_id=? AND question_id=?")){
                try(var query=connection.prepareStatement(sql)){query.setString(1,tenant);query.setObject(2,UUID.fromString(oldQuestion));query.executeUpdate();}
            }
        }
        var freshPrompt=claudePrompt();replace(freshPrompt,"prompt.id","fresh-prompt");replace(freshPrompt,"prompt_text","Fresh synthetic question");
        var freshUsage=claudeUsage();replace(freshUsage,"prompt.id","fresh-prompt");replace(freshUsage,"request_id","fresh-request");
        ingest(key.token(),freshPrompt,freshUsage).expectStatus().isOk();
        try(var connection=database.open()){MonitoringStore.prune(connection,tenant,1,0);}
        ingest(key.token(),oldPrompt,oldUsage).expectStatus().isOk();
        var lateUsage=claudeUsage();replace(lateUsage,"request_id","late-old-question-request");ingest(key.token(),lateUsage).expectStatus().isOk();
        var latePrompt=claudePrompt();attr(latePrompt,"request_id","late-old-question-prompt");ingest(key.token(),latePrompt).expectStatus().isOk();
        JsonNode session=first(get("/sessions?accountId="+owner,tenant,"viewer"));assertThat(session.path("id").asText()).isEqualTo(sid);
        assertThat(session.path("totalTokens").asLong()).isEqualTo(557);
        JsonNode question=first(get("/sessions/"+sid+"/questions",tenant,"viewer"));assertThat(question.path("externalPromptId").asText()).isEqualTo("fresh-prompt");
        assertThat(count("monitoring_events",tenant)).isEqualTo(2);assertThat(count("monitoring_question_contents",tenant)).isEqualTo(1);
        client.get().uri("/admin/api/monitoring/questions/"+oldQuestion).header("Test-Tenant",tenant).header("Test-Role","operator").exchange().expectStatus().isNotFound();
        try(var connection=database.open();var query=connection.prepareStatement("SELECT purged_at,content_purged_at FROM llm_gateway_analytics.monitoring_questions WHERE tenant_id=? AND id=?")){
            query.setString(1,tenant);query.setObject(2,UUID.fromString(oldQuestion));try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getTimestamp(1)).isNotNull();assertThat(rows.getTimestamp(2)).isNotNull();}
        }
    }

    @Test void providerAccountObservationsAreMaskedAndConflictCannotChangeServerOwner()throws Exception {
        UUID owner=account(tenant,"Provider observation owner");var key=key(tenant,owner,null);
        String firstRaw="PRIVATE_PROVIDER_ACCOUNT_ALPHA",secondRaw="PRIVATE_PROVIDER_ACCOUNT_BETA";
        var firstUsage=claudeUsage();attr(firstUsage,"user.account_uuid",firstRaw);ingest(key.token(),firstUsage).expectStatus().isOk();
        JsonNode observed=first(get("/sessions?accountId="+owner,tenant,"viewer"));String sid=observed.path("id").asText();
        assertThat(observed.path("providerAccountStatus").asText()).isEqualTo("observed");
        assertThat(observed.path("providerAccountId").asText()).matches("sha256:[a-f0-9]{12}");
        var secondUsage=claudeUsage();replace(secondUsage,"request_id","provider-conflict-request");attr(secondUsage,"user.account_id",secondRaw);ingest(key.token(),secondUsage).expectStatus().isOk();
        JsonNode conflict=first(get("/sessions?accountId="+owner,tenant,"viewer"));
        assertThat(conflict.path("providerAccountStatus").asText()).isEqualTo("conflict");assertThat(conflict.path("accountId").asText()).isEqualTo(owner.toString());
        assertThat(conflict.path("providerAccountId").asText()).isEqualTo(observed.path("providerAccountId").asText());
        assertThat(credentials.authenticate(key.token()).orElseThrow().ownerAccountId()).isEqualTo(owner);
        for(JsonNode dto:List.of(conflict,get("/accounts",tenant,"viewer"),get("/sessions/"+sid+"/questions",tenant,"viewer"),get("/sessions/"+sid+"/events",tenant,"viewer")))assertThat(dto.toString()).doesNotContain(firstRaw,secondRaw);
        try(var connection=database.open()){
            for(String table:List.of("monitoring_accounts","monitoring_sessions","monitoring_questions","monitoring_question_contents","monitoring_events","monitoring_event_identities","audit_events","collector_credentials")){
                try(var query=connection.prepareStatement("SELECT row_to_json(r)::text FROM llm_gateway_analytics."+table+" r WHERE tenant_id=?")){
                    query.setString(1,tenant);try(var rows=query.executeQuery()){while(rows.next())assertThat(rows.getString(1)).as(table).doesNotContain(firstRaw,secondRaw);}
                }
            }
            try(var query=connection.prepareStatement("SELECT provider_account_hash,account_id FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND id=?")){
                query.setString(1,tenant);query.setObject(2,UUID.fromString(sid));try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).matches("[a-f0-9]{64}");assertThat(rows.getObject(2,UUID.class)).isEqualTo(owner);}
            }
        }
    }

    @Test void unknownAndMalformedRecordsAreCountedWithoutFalseUsage()throws Exception {
        UUID owner=account(tenant,"Diagnostic owner");var key=key(tenant,owner,null);
        var unknown=record("codex.future_event");attr(unknown,"conversation.id","future-session");attr(unknown,"prompt",SECRET_PROMPT);
        var malformed=claudeUsage();malformed.withArray("attributes").forEach(a->{if(a.path("key").asText().equals("input_tokens"))((ObjectNode)a.path("value")).put("intValue","not-a-number");});
        JsonNode response=JSON.readTree(ingest(key.token(),unknown,malformed).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody());
        assertThat(response.path("partialSuccess").path("rejectedLogRecords").asInt()).isEqualTo(1);
        JsonNode status=get("/status",tenant,"viewer");assertThat(status.path("stored").asLong()).isEqualTo(1);assertThat(status.path("unsupported").asLong()).isEqualTo(1);assertThat(status.path("rejected").asLong()).isEqualTo(1);
        assertThat(count("monitoring_questions",tenant)).isZero();assertThat(count("monitoring_question_contents",tenant)).isZero();assertThat(count("monitoring_events",tenant)).isEqualTo(1);
    }

    @Test void emptyEnvelopeDoesNotClaimAStoredConnection()throws Exception {
        UUID owner=account(tenant,"Empty owner");ingest(key(tenant,owner,null).token()).expectStatus().isOk();
        JsonNode status=get("/status",tenant,"viewer");assertThat(status.path("stored").asLong()).isZero();assertThat(status.path("lastReceivedAt").isNull()).isTrue();
        assertThat(count("monitoring_events",tenant)).isZero();
    }

    @Test void responsesArriveBeforeQuestionsStayProtectedAndDeduplicate()throws Exception{
        UUID owner=account(tenant,"Response owner");var key=key(tenant,owner,null);
        var response=claudeResponse("message-1","FIRST_PRIVATE_ANSWER");
        ingest(key.token(),response,response,claudePrompt(),claudeUsage()).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();
        String qid=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();
        JsonNode detail=get("/questions/"+qid,tenant,"operator");
        assertThat(detail.path("responseAvailability").asText()).isEqualTo("available");
        assertThat(detail.path("responses")).hasSize(1);assertThat(detail.path("responses").get(0).path("content").path("text").asText()).isEqualTo("FIRST_PRIVATE_ANSWER");
        JsonNode viewer=get("/questions/"+qid,tenant,"viewer");assertThat(viewer.path("responseAvailability").asText()).isEqualTo("permission_required");assertThat(viewer.toString()).doesNotContain("FIRST_PRIVATE_ANSWER");
        assertThat(first(get("/sessions/"+sid+"/responses",tenant,"operator")).path("questionId").asText()).isEqualTo(qid);
        assertThat(detail.path("question").path("totalTokens").asLong()).isEqualTo(557);
        for(String path:List.of("/accounts","/sessions?accountId="+owner,"/sessions/"+sid+"/questions","/sessions/"+sid+"/events"))assertThat(get(path,tenant,"operator").toString()).doesNotContain("FIRST_PRIVATE_ANSWER");
    }
    @Test void notifyModeSuppressesNativeQuestionAndValidatesHeader()throws Exception{
        UUID owner=account(tenant,"Notify owner");var key=key(tenant,owner,null);
        var nativePrompt=codex("user_prompt","1");attr(nativePrompt,"prompt","native question");
        var turn=codex("turn_response","2");attr(turn,"turn.id","turn-1");attr(turn,"prompt","first\n\nsecond");attr(turn,"response","notify answer");
        byte[] bytes=TelemetryNormalizerTest.envelope(nativePrompt,turn);
        client.post().uri("/telemetry/v1/logs").header("Authorization","Bearer "+key.token()).header("Content-Type","application/json").header("X-LLM-Gateway-Codex-Question-Source","notify").bodyValue(bytes).exchange().expectStatus().isOk();
        assertThat(count("monitoring_questions",tenant)).isEqualTo(1);assertThat(count("monitoring_responses",tenant)).isEqualTo(1);
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();String qid=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();
        assertThat(get("/questions/"+qid,tenant,"operator").path("content").path("text").asText()).isEqualTo("first\n\nsecond");
        for(String value:List.of("invalid","notify,native"))client.post().uri("/telemetry/v1/logs").header("Authorization","Bearer "+key.token()).header("Content-Type","application/json").header("X-LLM-Gateway-Codex-Question-Source",value).bodyValue(bytes).exchange().expectStatus().isBadRequest();
    }
    @Test void responseContentRetentionAndMetadataRetentionCannotBeReplayed()throws Exception{
        UUID owner=account(tenant,"Response retention");var key=key(tenant,owner,null);var response=claudeResponse("retained-message","PRIVATE_RETAINED_ANSWER");ingest(key.token(),claudePrompt(),response).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();String qid=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_response_contents SET received_at=now()-interval '3 days' WHERE tenant_id=?")){q.setString(1,tenant);q.executeUpdate();MonitoringStore.prune(c,tenant,0,1);}
        ingest(key.token(),response).expectStatus().isOk();
        assertThat(count("monitoring_response_contents",tenant)).isZero();assertThat(get("/questions/"+qid,tenant,"operator").path("responseAvailability").asText()).isEqualTo("purged");
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_responses SET received_at=now()-interval '3 days' WHERE tenant_id=?")){q.setString(1,tenant);q.executeUpdate();MonitoringStore.prune(c,tenant,1,0);}
        ingest(key.token(),response).expectStatus().isOk();assertThat(get("/sessions/"+sid+"/responses",tenant,"operator").path("items")).isEmpty();assertThat(count("monitoring_response_contents",tenant)).isZero();
    }
    @Test void responseIdentifiersAreOwnerTenantAndSessionScopedAndOrphansRemainVisible()throws Exception{
        UUID a=account(tenant,"A"),b=account(tenant,"B"),foreign=account(otherTenant,"C");
        var response=claudeResponse("same-id","same answer");
        for(var key:List.of(key(tenant,a,null),key(tenant,b,null),key(otherTenant,foreign,null)))ingest(key.token(),response).expectStatus().isOk();
        var otherSession=response.deepCopy();replace(otherSession,"session.id","another-session");ingest(key(tenant,a,null).token(),otherSession).expectStatus().isOk();
        assertThat(count("monitoring_responses",tenant)).isEqualTo(3);assertThat(count("monitoring_responses",otherTenant)).isEqualTo(1);
        var orphan=record("claude_code.assistant_response");attr(orphan,"session.id","orphan-session");attr(orphan,"message.uuid","orphan");attr(orphan,"response","unlinked answer");
        UUID orphanOwner=account(tenant,"orphan");ingest(key(tenant,orphanOwner,null).token(),orphan).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+orphanOwner,tenant,"viewer")).path("id").asText();JsonNode item=first(get("/sessions/"+sid+"/responses",tenant,"operator"));assertThat(item.path("questionId").isNull()).isTrue();assertThat(item.path("attribution").asText()).isEqualTo("unattributed");
        client.get().uri("/admin/api/monitoring/sessions/"+sid+"/responses").header("Test-Tenant",otherTenant).exchange().expectStatus().isNotFound();
    }
    @Test void responsePagesUseSequenceAndExposeSafeRevisions()throws Exception{
        UUID owner=account(tenant,"Ordered responses");var key=key(tenant,owner,null);ingest(key.token(),claudePrompt()).expectStatus().isOk();
        JsonNode session=first(get("/sessions?accountId="+owner,tenant,"viewer"));String sid=session.path("id").asText();JsonNode question=first(get("/sessions/"+sid+"/questions",tenant,"viewer"));String qid=question.path("id").asText();
        String before=question.path("responseRevision").asText();assertThat(before).isNotBlank();
        var second=claudeResponse("second","SECOND_ANSWER");replace(second,"event.timestamp","2026-10-08T10:00:00Z");var first=claudeResponse("first","FIRST_ANSWER");replace(first,"event.timestamp","2026-10-08T10:00:00Z");first.withArray("attributes").forEach(a->{if(a.path("key").asText().equals("event.sequence"))((ObjectNode)a.path("value")).put("intValue","2");});
        ingest(key.token(),second,first).expectStatus().isOk();JsonNode page=get("/sessions/"+sid+"/responses?limit=1&offset=0",tenant,"operator");assertThat(page.path("total").asInt()).isEqualTo(2);assertThat(first(page).path("content").path("text").asText()).isEqualTo("FIRST_ANSWER");
        assertThat(first(get("/sessions/"+sid+"/responses?limit=1&offset=1",tenant,"operator")).path("content").path("text").asText()).isEqualTo("SECOND_ANSWER");
        assertThat(get("/questions/"+qid,tenant,"operator").path("question").path("responseRevision").asText()).isNotEqualTo(before);
        assertThat(first(get("/sessions?accountId="+owner,tenant,"viewer")).path("responseRevision").asText()).isNotEqualTo(session.path("responseRevision").asText());
        JsonNode redacted=get("/sessions/"+sid+"/responses",tenant,"viewer");assertThat(redacted.toString()).doesNotContain("FIRST_ANSWER","SECOND_ANSWER");assertThat(redacted.path("items").get(0).path("contentAvailability").asText()).isEqualTo("permission_required");
        client.get().uri("/admin/api/monitoring/sessions/"+sid+"/responses?limit=101").header("Test-Tenant",tenant).exchange().expectStatus().isBadRequest();
    }
    @Test void selectedSessionMetadataIsExactScopedValidatedAndTracksPurges()throws Exception{
        UUID owner=account(tenant,"Selected metadata"),otherOwner=account(tenant,"Other metadata");var key=key(tenant,owner,null);
        ingest(key.token(),claudeResponse("selected","PRIVATE_SELECTED_ANSWER")).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();
        var other=claudeResponse("other","OTHER_PRIVATE_ANSWER");replace(other,"session.id","other-session");ingest(key.token(),other).expectStatus().isOk();
        String path="/sessions?accountId="+owner+"&sessionId="+sid+"&source=claude_code&limit=1";
        long auditBefore=count("audit_events",tenant);
        JsonNode metadata=first(get(path,tenant,"operator"));assertThat(metadata.path("id").asText()).isEqualTo(sid);
        assertThat(metadata.toString()).doesNotContain("PRIVATE_SELECTED_ANSWER","OTHER_PRIVATE_ANSWER");
        assertThat(count("audit_events",tenant)).isEqualTo(auditBefore);
        assertThat(get(path,otherTenant,"viewer").path("items")).isEmpty();
        assertThat(get("/sessions?accountId="+otherOwner+"&sessionId="+sid,tenant,"viewer").path("items")).isEmpty();
        assertThat(get("/sessions?sessionId="+sid+"&source=codex",tenant,"viewer").path("items")).isEmpty();
        assertThat(get("/sessions?sessionId="+sid+"&from=2000-01-01&to=2000-01-02",tenant,"viewer").path("items")).isEmpty();
        for(String invalid:List.of("invalid","","1-1-1-1-1",sid+"&sessionId="+sid))client.get().uri("/admin/api/monitoring/sessions?sessionId="+invalid).header("Test-Tenant",tenant).exchange().expectStatus().isBadRequest();
        String before=metadata.path("responseRevision").asText();
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_response_contents SET received_at=now()-interval '3 days' WHERE tenant_id=?")){q.setString(1,tenant);q.executeUpdate();MonitoringStore.prune(c,tenant,0,1);}
        String bodyPurged=first(get(path,tenant,"viewer")).path("responseRevision").asText();assertThat(bodyPurged).isNotEqualTo(before);
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_responses SET received_at=now()-interval '3 days' WHERE tenant_id=?")){q.setString(1,tenant);q.executeUpdate();MonitoringStore.prune(c,tenant,1,0);}
        assertThat(first(get(path,tenant,"viewer")).path("responseRevision").asText()).isNotEqualTo(bodyPurged);
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET purged_at=now() WHERE tenant_id=? AND id=?")){q.setString(1,tenant);q.setObject(2,UUID.fromString(sid));q.executeUpdate();}
        assertThat(get(path,tenant,"viewer").path("items")).isEmpty();
    }
    @Test void failedResponseReadAuditReturnsNoBodyAndRollsBackAudit()throws Exception{
        UUID owner=account(tenant,"Audit responses");var key=key(tenant,owner,null);ingest(key.token(),claudeResponse("audit-message","AUDIT_PRIVATE_ANSWER")).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();String qid=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();long before=count("audit_events",tenant);
        String hook="response_audit_"+UUID.randomUUID().toString().replace("-","");
        try(var c=database.open();var sql=c.createStatement()){
            sql.execute("CREATE FUNCTION llm_gateway_analytics."+hook+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"' AND NEW.action='content.read' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER "+hook+" BEFORE INSERT ON llm_gateway_analytics.audit_events FOR EACH ROW EXECUTE FUNCTION llm_gateway_analytics."+hook+"()");
        }
        try{
            for(String path:List.of("/questions/"+qid,"/sessions/"+sid+"/responses")){
                byte[] bytes=client.get().uri("/admin/api/monitoring"+path).header("Test-Tenant",tenant).header("Test-Role","operator").exchange().expectStatus().is5xxServerError().expectBody(byte[].class).returnResult().getResponseBody();
                assertThat(bytes==null?"":new String(bytes,StandardCharsets.UTF_8)).doesNotContain("AUDIT_PRIVATE_ANSWER");
            }
            assertThat(count("audit_events",tenant)).isEqualTo(before);
        }finally{try(var c=database.open();var sql=c.createStatement()){sql.execute("DROP TRIGGER "+hook+" ON llm_gateway_analytics.audit_events");sql.execute("DROP FUNCTION llm_gateway_analytics."+hook+"()");}}
    }
    @Test void responseContentsExpireWithSessionsAndLateMessagesCannotRestoreThem()throws Exception{
        UUID owner=account(tenant,"Expired responses");var key=key(tenant,owner,null);var response=claudeResponse("expired","PRIVATE_EXPIRED_ANSWER");ingest(key.token(),response).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();
        try(var c=database.open();var q=c.prepareStatement("UPDATE llm_gateway_analytics.monitoring_sessions SET last_seen_at=now()-interval '3 days' WHERE tenant_id=?")){q.setString(1,tenant);q.executeUpdate();MonitoringStore.prune(c,tenant,1,0);}
        ingest(key.token(),response,claudeResponse("late-new-message","PRIVATE_LATE_ANSWER")).expectStatus().isOk();assertThat(count("monitoring_response_contents",tenant)).isZero();
        client.get().uri("/admin/api/monitoring/sessions/"+sid+"/responses").header("Test-Tenant",tenant).exchange().expectStatus().isNotFound();
    }
    @Test void databaseRejectsCrossOwnerSessionAndQuestionResponseReferences()throws Exception{
        UUID owner=account(tenant,"Scope A"),other=account(tenant,"Scope B");ingest(key(tenant,owner,null).token(),claudeResponse("scope","answer")).expectStatus().isOk();ingest(key(tenant,other,null).token(),claudeResponse("scope","answer")).expectStatus().isOk();
        String otherSession=first(get("/sessions?accountId="+other,tenant,"viewer")).path("id").asText();String otherQuestion=first(get("/sessions/"+otherSession+"/questions",tenant,"viewer")).path("id").asText();
        for(String sql:List.of("UPDATE llm_gateway_analytics.monitoring_responses SET session_id=? WHERE tenant_id=? AND account_id=?","UPDATE llm_gateway_analytics.monitoring_responses SET question_id=? WHERE tenant_id=? AND account_id=?")){
            try(var c=database.open();var query=c.prepareStatement(sql)){
                query.setObject(1,UUID.fromString(sql.contains("SET session_id")?otherSession:otherQuestion));query.setString(2,tenant);query.setObject(3,owner);
                assertThatThrownBy(query::executeUpdate).isInstanceOf(java.sql.SQLException.class).satisfies(e->assertThat(((java.sql.SQLException)e).getSQLState()).isEqualTo("23503"));
            }
        }
    }
    @Test void absentEmptyAndRedactedAnswersRemainNotCollected()throws Exception{
        UUID owner=account(tenant,"Unavailable answers");var key=key(tenant,owner,null);ingest(key.token(),claudePrompt()).expectStatus().isOk();
        String sid=first(get("/sessions?accountId="+owner,tenant,"viewer")).path("id").asText();String qid=first(get("/sessions/"+sid+"/questions",tenant,"viewer")).path("id").asText();
        assertThat(get("/questions/"+qid,tenant,"operator").path("responseAvailability").asText()).isEqualTo("not_collected");
        ingest(key.token(),claudeResponse("empty",""),claudeResponse("redacted","<REDACTED>")).expectStatus().isOk();JsonNode detail=get("/questions/"+qid,tenant,"operator");assertThat(detail.path("responseAvailability").asText()).isEqualTo("not_collected");
        assertThat(detail.path("responses")).hasSize(2);for(JsonNode response:detail.path("responses")){assertThat(response.path("content").isNull()).isTrue();assertThat(response.path("contentAvailability").asText()).isEqualTo("not_collected");}
    }
    static ObjectNode claudeResponse(String message,String text){var event=record("claude_code.assistant_response");attr(event,"session.id","shared-session");attr(event,"prompt.id","shared-prompt");attr(event,"message.uuid",message);attr(event,"response",text);attr(event,"query_source","repl_main_thread");number(event,"event.sequence",3);return event;}

    UUID account(String t,String name)throws Exception {
        byte[] bytes=client.post().uri("/admin/api/monitoring/accounts").header("Test-Tenant",t).bodyValue(Map.of("name",name)).exchange().expectStatus().isCreated().expectBody(byte[].class).returnResult().getResponseBody();
        return UUID.fromString(JSON.readTree(bytes).path("id").asText());
    }
    CollectorCredentialService.Created key(String t,UUID owner,UUID registration)throws Exception{return credentials.create(t,"test-issuer","native-monitor",30,"subscription_declared",owner,registration);}
    WebTestClient.ResponseSpec ingest(String token,ObjectNode... logs)throws Exception {
        return client.post().uri("/telemetry/v1/logs").header("Authorization","Bearer "+token).header("Content-Type","application/json").bodyValue(new String(TelemetryNormalizerTest.envelope(logs),StandardCharsets.UTF_8)).exchange();
    }
    JsonNode get(String path,String t,String role)throws Exception{return JSON.readTree(client.get().uri("/admin/api/monitoring"+path).header("Test-Tenant",t).header("Test-Role",role).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody(byte[].class).returnResult().getResponseBody());}
    static JsonNode first(JsonNode page){assertThat(page.path("items")).hasSize(1);return page.path("items").get(0);}
    long count(String table,String t)throws Exception {try(var connection=database.open();var query=connection.prepareStatement("SELECT count(*) FROM llm_gateway_analytics."+table+" WHERE tenant_id=?")){query.setString(1,t);try(var rows=query.executeQuery()){rows.next();return rows.getLong(1);}}}
    static ObjectNode record(String kind){var event=JSON.createObjectNode();event.putArray("attributes");attr(event,"event.name",kind);attr(event,"event.timestamp",Instant.now().toString());return event;}
    static ObjectNode claudePrompt(){var event=record("claude_code.user_prompt");attr(event,"session.id","shared-session");attr(event,"prompt.id","shared-prompt");attr(event,"prompt_text",SECRET_PROMPT);attr(event,"user.email","PRIVATE_PROVIDER_EMAIL");attr(event,"authorization","sk-fixture-secret");return event;}
    static ObjectNode claudeUsage(){var event=record("claude_code.api_request");attr(event,"session.id","shared-session");attr(event,"prompt.id","shared-prompt");attr(event,"request_id","shared-request");attr(event,"model","claude-haiku-4-5");attr(event,"query_source","repl_main");number(event,"input_tokens",431);number(event,"output_tokens",126);number(event,"cache_read_tokens",0);number(event,"cache_creation_tokens",0);number(event,"cost_usd_micros",1061);return event;}
    static ObjectNode codex(String kind,String sequence){var event=record("codex."+kind);attr(event,"conversation.id","codex-shared-conversation");attr(event,"service.version","0.160.0");attr(event,"event.sequence",sequence);return event;}
    static void attr(ObjectNode event,String key,String value){TelemetryNormalizerTest.attr(event,key,"stringValue",value);}
    static void replace(ObjectNode event,String key,String value){event.withArray("attributes").forEach(a->{if(a.path("key").asText().equals(key))((ObjectNode)a.path("value")).put("stringValue",value);});}
    static void number(ObjectNode event,String key,long value){TelemetryNormalizerTest.attr(event,key,"intValue",Long.toString(value));}
}
