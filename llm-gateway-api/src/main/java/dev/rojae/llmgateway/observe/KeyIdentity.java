package dev.rojae.llmgateway.observe;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Pseudonymous credential correlation only; upstream remains responsible for authentication. */
@Component
public final class KeyIdentity {
    private final SecretKeySpec hmacKey;

    public KeyIdentity(@Value("${gateway.key-id-secret:}") String secret) {
        byte[] secretBytes;
        if (secret == null || secret.isBlank()) {
            secretBytes = new byte[32];
            new SecureRandom().nextBytes(secretBytes);
        } else {
            secretBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (secretBytes.length < 32) {
                throw new IllegalArgumentException(
                        "gateway.key-id-secret must contain at least 32 UTF-8 bytes");
            }
        }
        hmacKey = new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    public String identify(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            // Mac is mutable: one instance per invocation keeps concurrent requests isolated.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return "key_" + HexFormat.of().formatHex(mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
