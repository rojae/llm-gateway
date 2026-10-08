package dev.rojae.llmgateway;

import dev.rojae.llmgateway.observe.KeyIdentity;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KeyIdentityTest {
    private static final String SECRET = "test-attribution-secret-at-least-32-bytes";

    @Test void stableAcrossInstancesWithSameSecret() {
        assertThat(new KeyIdentity(SECRET).identify("caller-a"))
                .isEqualTo(new KeyIdentity(SECRET).identify("caller-a"))
                .matches("key_[0-9a-f]{64}")
                .doesNotContain("caller-a", SECRET);
    }

    @Test void differentKeysAndSecretsAreIsolated() {
        KeyIdentity identity = new KeyIdentity(SECRET);
        assertThat(identity.identify("caller-a")).isNotEqualTo(identity.identify("caller-b"))
                .isNotEqualTo(new KeyIdentity(SECRET + "other").identify("caller-a"));
    }

    @Test void absentSecretIsInstanceScopedAndStableWithinInstance() {
        KeyIdentity first = new KeyIdentity("");
        assertThat(first.identify("caller-a")).isEqualTo(first.identify("caller-a"))
                .isNotEqualTo(new KeyIdentity("").identify("caller-a"));
    }

    @Test void emptyCredentialsHaveNoIdentity() {
        KeyIdentity identity = new KeyIdentity(SECRET);
        assertThat(identity.identify(null)).isNull();
        assertThat(identity.identify("")).isNull();
        assertThat(identity.identify(" \t")).isNull();
    }

    @Test void weakSecretsFailWithoutDisclosingValue() {
        assertThatThrownBy(() -> new KeyIdentity("weak-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("gateway.key-id-secret must contain at least 32 UTF-8 bytes");
    }

    @Test void concurrentCallsRemainDeterministic() {
        KeyIdentity identity = new KeyIdentity(SECRET);
        String expected = identity.identify("caller-a");
        assertThat(IntStream.range(0, 1000).parallel()
                .mapToObj(i -> identity.identify("caller-a")).distinct().toList())
                .containsExactly(expected);
    }
}
