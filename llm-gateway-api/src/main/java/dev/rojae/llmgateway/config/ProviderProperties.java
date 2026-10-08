package dev.rojae.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit provider routes accept caller credentials; they never use the legacy managed key. */
@ConfigurationProperties(prefix = "gateway.providers")
public record ProviderProperties(
        @DefaultValue("https://api.openai.com/v1") String openaiBaseUrl,
        @DefaultValue("https://api.anthropic.com/v1") String anthropicBaseUrl) {
    public String openaiUrl() { return normalize(openaiBaseUrl); }
    public String anthropicUrl() { return normalize(anthropicBaseUrl); }

    private static String normalize(String value) {
        return value.replaceAll("/+$", "");
    }
}
