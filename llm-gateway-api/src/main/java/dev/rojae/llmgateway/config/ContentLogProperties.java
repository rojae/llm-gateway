package dev.rojae.llmgateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.content-log")
public record ContentLogProperties(
        @DefaultValue("4096") @Min(256) @Max(65536) int maxChars,
        @DefaultValue("1048576") @Min(4096) @Max(1048576) int maxEventBytes,
        @DefaultValue("pretty") Format format) {
    public enum Format { PRETTY, JSON }
}
