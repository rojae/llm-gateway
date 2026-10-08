package dev.rojae.llmgateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 접근 로그를 테스트에서 읽는다.
 *
 * <p>게이트웨이 내부 상태를 들여다보는 대신 <b>실제로 찍힌 로그 줄</b>을 검증한다.
 * 접근 로그가 v0 의 산출물이므로, 그 줄이 맞는지가 유일하게 의미 있는 증거다.
 */
final class AccessLogCapture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger = (Logger) LoggerFactory.getLogger("gateway.access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    AccessLogCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    /** requestId 로 해당 줄을 찾는다. 스트리밍은 응답이 끝난 뒤에 찍히므로 잠깐 기다린다. */
    JsonNode awaitRecord(String requestId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode found = find(requestId);
            if (found != null) {
                return found;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("접근 로그에 requestId=" + requestId + " 줄이 찍히지 않았다. 찍힌 줄: " + lines());
    }

    private JsonNode find(String requestId) {
        for (String line : lines()) {
            try {
                JsonNode node = MAPPER.readTree(line);
                if (requestId.equals(node.path("requestId").asText())) {
                    return node;
                }
            } catch (Exception ignored) {
                // 접근 로그가 아닌 줄
            }
        }
        return null;
    }

    private List<String> lines() {
        synchronized (appender) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
