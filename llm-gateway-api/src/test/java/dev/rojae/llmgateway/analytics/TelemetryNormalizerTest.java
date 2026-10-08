package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class TelemetryNormalizerTest {
    static final ObjectMapper JSON=new ObjectMapper();
    private final TelemetryNormalizer normalizer=new TelemetryNormalizer(new TelemetryProperties());

    @Test void officialTypedRequestKeepsExactEstimatedCostSeparateAndDropsPrivateFields() throws Exception {
        ObjectNode record=record("api_request");
        attr(record,"request_id","stringValue","req-a");attr(record,"input_tokens","intValue","10");attr(record,"output_tokens","intValue","2");
        attr(record,"cache_read_tokens","intValue","30");attr(record,"cache_creation_tokens","intValue","4");
        attr(record,"cost_usd_micros","intValue","1234");attr(record,"cost_usd","stringValue","999");
        attr(record,"user.email","stringValue","PRIVATE_EMAIL");attr(record,"prompt","stringValue","PRIVATE_PROMPT");
        record.putObject("body").put("stringValue","PRIVATE_BODY");
        var batch=normalizer.normalize(envelope(record));
        assertThat(batch.records()).hasSize(1);assertThat(batch.rejected()).isZero();
        var normalized=JSON.readTree(batch.records().getFirst());
        assertThat(normalized.path("source").asText()).isEqualTo("claude_code");
        assertThat(normalized.path("billingMode").asText()).isEqualTo("subscription_declared");
        assertThat(normalized.path("costUsd").isNull()).isTrue();
        assertThat(normalized.path("status").isNull()).isTrue();
        assertThat(normalized.path("upstreamRequestId").asText()).isEqualTo("req-a");
        assertThat(normalized.path("apiEquivalentCostUsd").decimalValue()).isEqualByComparingTo("0.001234");
        assertThat(normalized.path("totalTokens").asLong()).isEqualTo(46);
        assertThat(normalized.toString()).doesNotContain("PRIVATE_EMAIL","PRIVATE_PROMPT","PRIVATE_BODY");
    }
    @Test void stableRequestIdentitySurvivesObservationTimestampChangesAndErrorsAreDistinct() throws Exception {
        var record=record("api_request");attr(record,"request_id","stringValue","req-stable");
        String first=JSON.readTree(normalizer.normalize(envelope(record)).records().getFirst()).path("requestId").asText();
        record.put("observedTimeUnixNano","1790280000999999999");
        String second=JSON.readTree(normalizer.normalize(envelope(record)).records().getFirst()).path("requestId").asText();
        assertThat(second).isEqualTo(first);
        var error=record("api_error");attr(error,"request_id","stringValue","req-stable");attr(error,"error","stringValue","PRIVATE_OAUTH sk-ant-secret");
        var normalized=JSON.readTree(normalizer.normalize(envelope(error)).records().getFirst());
        assertThat(normalized.path("requestId").asText()).isNotEqualTo(first);
        assertThat(normalized.path("status").isNull()).isTrue();
        assertThat(normalized.path("outcome").asText()).isEqualTo("upstream_error");
        assertThat(normalized.toString()).doesNotContain("PRIVATE_OAUTH","sk-ant-secret");
    }
    @Test void malformedSupportedRecordsAreRejectedWhileOtherEventsAreIgnored() throws Exception {
        var invalid=record("api_request");attr(invalid,"input_tokens","stringValue","not-a-number");
        var ignored=record("user_prompt");attr(ignored,"prompt","stringValue","PRIVATE_PROMPT");
        var batch=normalizer.normalize(envelope(invalid,ignored));
        assertThat(batch.records()).isEmpty();assertThat(batch.rejected()).isEqualTo(1);
    }
    @Test void bodyEventNamesAndFallbackIdentityRequireOriginalTimeAndSessionSequence() throws Exception {
        var record=JSON.createObjectNode();record.putObject("body").put("stringValue","claude_code.api_request");
        record.put("timeUnixNano","1790280000000000000");record.putArray("attributes");
        attr(record,"session.id","stringValue","session-a");attr(record,"event.sequence","intValue","3");
        var result=normalizer.normalize(envelope(record));assertThat(result.records()).hasSize(1);
        var missingTime=record.deepCopy();missingTime.remove("timeUnixNano");missingTime.put("observedTimeUnixNano","1790280000000000000");
        assertThat(normalizer.normalize(envelope(missingTime)).rejected()).isEqualTo(1);
    }
    @Test void malformedEnvelopeAndOversizedRecordCountDoNotSilentlyAcknowledge() throws Exception {
        assertThatThrownBy(()->normalizer.normalize("{\"resourceLogs\":true}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        var properties=new TelemetryProperties();properties.setMaxRecords(1);
        assertThatThrownBy(()->new TelemetryNormalizer(properties).normalize(envelope(record("user_prompt"),record("user_prompt")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void numericExponentBoundsAndIntegralDoubleAttributesAreHandledSafely() throws Exception {
        var poison=record("api_request");attr(poison,"cost_usd","stringValue","1e-10000");
        assertThat(normalizer.normalize(envelope(poison)).rejected()).isEqualTo(1);
        var integral=record("api_request");
        integral.withArray("attributes").addObject().put("key","input_tokens").putObject("value").put("doubleValue",10.0);
        assertThat(normalizer.normalize(envelope(integral)).records()).hasSize(1);
        for(String value:new String[]{"1.5","9223372036854775808","1e-100000000"}){
            var invalid=record("api_request");attr(invalid,"input_tokens","stringValue",value);
            assertThat(normalizer.normalize(envelope(invalid)).rejected()).isEqualTo(1);
        }
    }
    @Test void fallbackIdentityPreservesNanosecondPrecisionAndInstallationIdentity() throws Exception {
        var record=record("api_request");record.put("timeUnixNano","1790280000000000001");attr(record,"user.id","stringValue","installation-a");
        String first=JSON.readTree(normalizer.normalize(envelope(record)).records().getFirst()).path("requestId").asText();
        record.put("timeUnixNano","1790280000000000002");
        String second=JSON.readTree(normalizer.normalize(envelope(record)).records().getFirst()).path("requestId").asText();
        assertThat(second).isNotEqualTo(first);
    }
    static ObjectNode record(String name){
        var record=JSON.createObjectNode();record.putArray("attributes");
        attr(record,"event.name","stringValue",name);attr(record,"event.timestamp","stringValue","2026-09-25T00:00:00.123Z");
        attr(record,"event.sequence","intValue","1");attr(record,"session.id","stringValue","session-a");attr(record,"model","stringValue","claude-sonnet-4-6");return record;
    }
    static void attr(ObjectNode record,String key,String type,String value){record.withArray("attributes").addObject().put("key",key).putObject("value").put(type,value);}
    static byte[] envelope(ObjectNode... records) throws Exception {
        var root=JSON.createObjectNode();var logs=root.putArray("resourceLogs").addObject().putArray("scopeLogs").addObject().putArray("logRecords");
        for(var record:records)logs.add(record);return JSON.writeValueAsBytes(root);
    }
}
