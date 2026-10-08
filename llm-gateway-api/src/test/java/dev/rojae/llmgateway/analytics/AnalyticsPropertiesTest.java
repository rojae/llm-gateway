package dev.rojae.llmgateway.analytics;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class AnalyticsPropertiesTest {
    @Test void disabledAnalyticsNeedsNoCredentialsButEnabledConfigurationIsBounded() {
        var properties=new AnalyticsProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setJdbcUrl("jdbc:postgresql://127.0.0.1/test");properties.setAdminToken("test-token-with-at-least-thirty-two-characters");
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setBatchSize(1000000);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setBatchSize(250);properties.setMaxLineBytes(Integer.MAX_VALUE);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setMaxLineBytes(1048576);properties.setPollInterval(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
