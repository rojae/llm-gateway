package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitoringNormalizerTest {
    private final MonitoringNormalizer parser=new MonitoringNormalizer(new TelemetryProperties());
    @Test void providerIdentityIsOneWayAndNeverClaimsVerifiedOwnership()throws Exception{
        var event=record("codex.user_prompt");attr(event,"conversation.id","c");attr(event,"user.account_id","PRIVATE_PROVIDER_ACCOUNT");
        var normalized=parser.normalize(TelemetryNormalizerTest.envelope(event)).events().getFirst();assertThat(normalized.providerAccountHash()).hasSize(64);assertThat(normalized.toString()).doesNotContain("PRIVATE_PROVIDER_ACCOUNT");
    }
    @Test void nativeNanosecondTimestampWithoutEventTimestampIsAcceptedAndPrecise()throws Exception{
        var event=record("codex.user_prompt");event.withArray("attributes").remove(1);event.put("timeUnixNano","1790280000000000001");attr(event,"conversation.id","c");
        var batch=parser.normalize(TelemetryNormalizerTest.envelope(event));assertThat(batch.rejected()).isZero();assertThat(batch.events().getFirst().emittedAt()).isEqualTo(java.time.Instant.parse("2026-09-24T20:00:00.000000001Z"));
    }
    @Test void nativeClaudePromptAndKnownSampleKeepContentSeparateAndExactUsage()throws Exception{
        var prompt=record("claude_code.user_prompt");attr(prompt,"prompt.id","p-1");attr(prompt,"prompt_text","<script>test only</script>");
        var usage=record("claude_code.api_request");attr(usage,"query_source","repl_main");attr(usage,"prompt.id","p-1");attr(usage,"request_id","r-1");attr(usage,"input_tokens","431");attr(usage,"output_tokens","126");attr(usage,"cost_usd","0.001061");
        var batch=parser.normalize(TelemetryNormalizerTest.envelope(prompt,usage));assertThat(batch.rejected()).isZero();assertThat(batch.events()).hasSize(2);
        assertThat(batch.events().getFirst().content()).isEqualTo("<script>test only</script>");
        var observed=batch.events().get(1);assertThat(observed.content()).isNull();assertThat(observed.total()).isEqualTo(557);assertThat(observed.cost()).isEqualByComparingTo("0.001061");assertThat(observed.attribution()).isEqualTo("linked");
    }
    @Test void codexCompletionNumbersRemainObservedAndCannotClaimQuestionAttribution()throws Exception{
        var first=record("codex.sse_event");attr(first,"conversation.id","c-1");attr(first,"event.kind","response.completed");attr(first,"input_token_count","12497");attr(first,"output_token_count","0");
        var last=record("codex.sse_event");attr(last,"conversation.id","c-1");attr(last,"event.kind","response.completed");attr(last,"input_token_count","29220");attr(last,"output_token_count","5");attr(last,"cached_token_count","12288");attr(last,"prompt.id","spoofed-turn");
        var batch=parser.normalize(TelemetryNormalizerTest.envelope(first,last));assertThat(batch.rejected()).isZero();assertThat(batch.events()).hasSize(2);
        assertThat(batch.events()).allSatisfy(e->{assertThat(e.attribution()).isEqualTo("unattributed");assertThat(e.promptId()).isNull();assertThat(e.cost()).isNull();assertThat(e.semantics()).isEqualTo("unknown");});
        assertThat(batch.events().get(1).total()).isEqualTo(29225);assertThat(batch.events().getFirst().identity()).isNotEqualTo(batch.events().get(1).identity());
    }
    @Test void unknownFieldsAndTransportTimestampNeverChangeIdentityOrLeakIntoEvent()throws Exception{
        var event=record("claude_code.api_request");attr(event,"prompt.id","p");attr(event,"request_id","r");
        var original=parser.normalize(TelemetryNormalizerTest.envelope(event)).events().getFirst();
        attr(event,"user.email","PRIVATE_EMAIL");attr(event,"Authorization","PRIVATE_SECRET");attr(event,"prompt","PRIVATE_PROMPT");event.put("observedTimeUnixNano","999999");event.putObject("body").put("stringValue","PRIVATE_BODY");
        var copy=parser.normalize(TelemetryNormalizerTest.envelope(event)).events().getFirst();assertThat(copy.identity()).isEqualTo(original.identity());assertThat(copy.toString()).doesNotContain("PRIVATE_EMAIL","PRIVATE_SECRET","PRIVATE_PROMPT","PRIVATE_BODY");
    }
    @Test void canonicalFallbackDeduplicatesAttributeOrderingWhilePreservingDifferentTimes()throws Exception{
        var original=record("codex.user_prompt");attr(original,"conversation.id","c");attr(original,"prompt","one");
        var reordered=original.deepCopy();var fields=reordered.withArray("attributes");var reverse=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();fields.forEach(reverse::add);java.util.Collections.reverse(reverse);fields.removeAll();reverse.forEach(fields::add);
        assertThat(parser.normalize(TelemetryNormalizerTest.envelope(original)).events().getFirst().identity()).isEqualTo(parser.normalize(TelemetryNormalizerTest.envelope(reordered)).events().getFirst().identity());
    }
    @Test void cacheLargerThanUncachedClaudeInputIsValidButCodexSubsetConflictIsExplicit()throws Exception{
        var claude=record("api_request");attr(claude,"input_tokens","2");attr(claude,"output_tokens","1");attr(claude,"cache_read_tokens","500");
        assertThat(parser.normalize(TelemetryNormalizerTest.envelope(claude)).events().getFirst().total()).isEqualTo(503);
        var codex=record("codex.sse_event");attr(codex,"event.kind","response.completed");attr(codex,"input_token_count","2");attr(codex,"cached_token_count","3");
        assertThat(parser.normalize(TelemetryNormalizerTest.envelope(codex)).events().getFirst().attribution()).isEqualTo("conflict");
    }
    @Test void invalidNumbersDuplicatesAndUnknownModelsAreSafe()throws Exception{
        for(String number:new String[]{"-1","1.5","9223372036854775808","1e-10000"}){
            var invalid=record("api_request");attr(invalid,"input_tokens",number);assertThat(parser.normalize(TelemetryNormalizerTest.envelope(invalid)).rejected()).isEqualTo(1);
        }
        var duplicate=record("api_request");attr(duplicate,"request_id","a");attr(duplicate,"request_id","a");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(duplicate)).rejected()).isEqualTo(1);
        var unknown=record("api_request");unknown.withArray("attributes").remove(4);attr(unknown,"model","unknown-model");attr(unknown,"cost_usd","2");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(unknown)).events().getFirst().cost()).isNull();
    }
    @Test void unknownScopeRetainsObservedTokensWithoutClaimingLinkedUsage()throws Exception{
        var unknown=record("api_request");attr(unknown,"prompt.id","p");attr(unknown,"request_id","r");attr(unknown,"input_tokens","431");attr(unknown,"output_tokens","126");
        var observed=parser.normalize(TelemetryNormalizerTest.envelope(unknown)).events().getFirst();
        assertThat(observed.total()).isEqualTo(557);assertThat(observed.attribution()).isEqualTo("unattributed");assertThat(observed.availability()).isEqualTo("unattributed");
        var explicitMain=unknown.deepCopy();attr(explicitMain,"agent_type","main");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(explicitMain)).events().getFirst().attribution()).isEqualTo("linked");
        var conflicting=explicitMain.deepCopy();attr(conflicting,"query_source","subagent");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(conflicting)).events().getFirst().attribution()).isEqualTo("unattributed");
    }
    @Test void auxiliaryAndErrorEventsNeverBecomeLinkedConsumption()throws Exception{
        var aux=record("api_request");attr(aux,"prompt.id","p");attr(aux,"request_id","r");attr(aux,"query_source","background");
        var error=record("api_error");attr(error,"prompt.id","p");attr(error,"request_id","r");
        assertThat(parser.normalize(TelemetryNormalizerTest.envelope(aux,error)).events()).allSatisfy(e->assertThat(e.attribution()).isEqualTo("unattributed"));
    }
    @Test void structuredBodiesOnlyReadAllowlistedKeysAndPromptTruncationIsExplicit()throws Exception{
        var prompt=record("claude_code.user_prompt");var values=prompt.putObject("body").putObject("kvlistValue").putArray("values");
        values.addObject().put("key","prompt_text").putObject("value").put("stringValue","x".repeat(20000));values.addObject().put("key","header").putObject("value").put("stringValue","PRIVATE_SECRET");
        var event=parser.normalize(TelemetryNormalizerTest.envelope(prompt)).events().getFirst();assertThat(event.content()).hasSize(16384);assertThat(event.truncated()).isTrue();assertThat(event.toString()).doesNotContain("PRIVATE_SECRET");
    }
    @Test void nativeAssistantAndNotifyTurnAreSupportedWithoutClaimingUsage()throws Exception{
        var response=record("claude_code.assistant_response");attr(response,"prompt.id","p");attr(response,"message.uuid","m");attr(response,"response","answer");attr(response,"query_source","sdk");
        var nativeEvent=parser.normalize(TelemetryNormalizerTest.envelope(response)).events().getFirst();
        assertThat(nativeEvent.supported()).isTrue();assertThat(nativeEvent.attribution()).isEqualTo("linked");assertThat(nativeEvent.cost()).isNull();
        var notify=record("codex.turn_response");attr(notify,"conversation.id","c");attr(notify,"turn.id","t");attr(notify,"prompt","question");attr(notify,"response","answer");
        var custom=parser.normalize(TelemetryNormalizerTest.envelope(notify)).events().getFirst();
        assertThat(custom.question()).isTrue();assertThat(custom.promptId()).isEqualTo("t");assertThat(custom.content()).isEqualTo("question");assertThat(custom.model()).isNull();
    }
    @Test void malformedNotifyFieldsAreRejected()throws Exception{
        for(String missing:java.util.List.of("conversation.id","turn.id","prompt","response")){
            var notify=record("codex.turn_response");
            for(String key:java.util.List.of("conversation.id","turn.id","prompt","response"))if(!key.equals(missing))attr(notify,key,"value");
            assertThat(parser.normalize(TelemetryNormalizerTest.envelope(notify)).rejected()).as(missing).isEqualTo(1);
        }
    }
    @Test void responseUnicodeBoundariesEmptyTextAndCliTruncationAreExplicit()throws Exception{
        var response=record("claude_code.assistant_response");attr(response,"response","x".repeat(65535)+"😀tail");
        var event=parser.normalize(TelemetryNormalizerTest.envelope(response)).events().getFirst();assertThat(event.responseContent()).hasSize(65535);assertThat(event.responseTruncated()).isTrue();
        var empty=record("claude_code.assistant_response");attr(empty,"response","");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(empty)).events().getFirst().responseContent()).isNull();
        var marker=record("claude_code.assistant_response");attr(marker,"response","short text [... truncated ...]");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(marker)).events().getFirst().responseTruncated()).isTrue();
        var upstream=record("claude_code.assistant_response");attr(upstream,"response","short text");attr(upstream,"response_length","100000");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(upstream)).events().getFirst().responseTruncated()).isTrue();
    }
    @Test void nativeRedactionAndOriginalLengthReflectCollectionState()throws Exception{
        for(String text:java.util.List.of("<REDACTED>","[REDACTED]")){
            var event=record("claude_code.assistant_response");attr(event,"response",text);attr(event,"response_length","100000");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(event)).events().getFirst().responseContent()).isNull();
        }
        var marker=record("claude_code.assistant_response");attr(marker,"response","text\n\n[TRUNCATED - Content exceeds 60KB limit]");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(marker)).events().getFirst().responseTruncated()).isTrue();
        var smallLimit=record("claude_code.assistant_response");attr(smallLimit,"response","short");attr(smallLimit,"response_length","1000");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(smallLimit)).events().getFirst().responseTruncated()).isTrue();
    }
    @Test void notifyNonStringTextAndInvalidIdsCannotBecomeQuestions()throws Exception{
        for(String invalid:java.util.List.of("prompt","response","turn.id","conversation.id")){
            var turn=record("codex.turn_response");for(String key:java.util.List.of("prompt","response","turn.id","conversation.id"))TelemetryNormalizerTest.attr(turn,key,key.equals(invalid)?"intValue":"stringValue","12");
            assertThat(parser.normalize(TelemetryNormalizerTest.envelope(turn)).rejected()).as(invalid).isEqualTo(1);
        }
    }
    @Test void codexTurnIdentityCannotBeChangedByClaudeMessageFields()throws Exception{
        var turn=record("codex.turn_response");attr(turn,"conversation.id","c");attr(turn,"turn.id","t");attr(turn,"prompt","q");attr(turn,"response","a");var first=parser.normalize(TelemetryNormalizerTest.envelope(turn)).events().getFirst();
        attr(turn,"message.uuid","unexpected");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(turn)).events().getFirst().identity()).isEqualTo(first.identity());
        var wrongType=record("claude_code.assistant_response");TelemetryNormalizerTest.attr(wrongType,"response","intValue","123");assertThat(parser.normalize(TelemetryNormalizerTest.envelope(wrongType)).rejected()).isEqualTo(1);
    }
    private static ObjectNode record(String event){return TelemetryNormalizerTest.record(event);}
    private static void attr(ObjectNode log,String key,String value){TelemetryNormalizerTest.attr(log,key,"stringValue",value);}
}
