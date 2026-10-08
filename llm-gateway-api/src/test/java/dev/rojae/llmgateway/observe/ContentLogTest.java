package dev.rojae.llmgateway.observe;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.config.ContentLogProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ContentLogTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ContentLog logger() {
        return new ContentLog(new ContentLogProperties(512, 4096, ContentLogProperties.Format.JSON));
    }
    private RequestRecord record() {
        var r = new RequestRecord("/anthropic/v1/messages", ApiFormat.ANTHROPIC);
        r.setProvider("anthropic"); r.setModel("model"); r.setStatus(200);
        return r;
    }
    @Test void loweringContentLevelStopsInFlightResponseInBothDestinations() throws Exception {
        try (var console = new Capture("gateway.content", Level.TRACE);
             var file = new Capture("gateway.content.file", Level.TRACE)) {
            var exchange = logger().start(record(), ApiFormat.ANTHROPIC,
                    mapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"));
            console.logger.setLevel(Level.INFO);
            exchange.response(mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"AFTER-DISABLE\"}]}"));
            exchange.finish();
            assertThat(console.list).hasSize(1);
            assertThat(file.list).hasSize(1); // Already queued request remains; no new response may be queued.
        }
    }
    @Test void cancellationMarksPartialAndFinalizationIsExactlyOnce() throws Exception {
        try (var logs = new Capture("gateway.content", Level.DEBUG);
             var file = new Capture("gateway.content.file", Level.OFF)) {
            var record = record(); record.setStream(true);
            var exchange = logger().start(record, ApiFormat.ANTHROPIC, mapper.readTree("{}"));
            exchange.event(mapper.readTree("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"부분 응답\"}}"));
            record.setClientDisconnected(true);
            exchange.finish(); exchange.finish();
            assertThat(logs.list).hasSize(2);
            var event = mapper.readTree(logs.list.getLast().getFormattedMessage());
            assertThat(event.path("requestId").asText()).isEqualTo(record.getRequestId());
            assertThat(event.path("text").asText()).contains("부분 응답");
            assertThat(event.path("partial").asBoolean()).isTrue();
            assertThat(event.path("outcome").asText()).isEqualTo("cancelled");
        }
    }
    @Test void turningTraceOnMidRequestDoesNotCaptureUnapprovedHistory() throws Exception {
        try (var logs = new Capture("gateway.content", Level.INFO)) {
            var exchange = logger().start(record(), ApiFormat.ANTHROPIC,
                    mapper.readTree("{\"system\":\"do-not-capture\"}"));
            logs.logger.setLevel(Level.TRACE);
            exchange.response(mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"also-uncaptured\"}]}"));
            exchange.finish();
            assertThat(logs.list).isEmpty();
        }
    }
    private static class Capture extends ListAppender<ILoggingEvent> implements AutoCloseable {
        private final Logger logger;
        private final Level previous;
        private final boolean additive;
        Capture(String name, Level level) {
            logger = (Logger) LoggerFactory.getLogger(name); previous = logger.getLevel();
            additive = logger.isAdditive(); logger.setAdditive(false);
            start(); logger.setLevel(level); logger.addAppender(this);
        }
        public void close() { logger.detachAppender(this); logger.setLevel(previous); logger.setAdditive(additive); stop(); }
    }
}
