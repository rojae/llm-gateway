package dev.rojae.llmgateway;

import dev.rojae.llmgateway.config.GatewayProperties;
import dev.rojae.llmgateway.config.ProviderProperties;
import dev.rojae.llmgateway.config.ContentLogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, ProviderProperties.class, ContentLogProperties.class})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
