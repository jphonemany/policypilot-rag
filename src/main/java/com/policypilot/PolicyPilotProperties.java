package com.policypilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("policypilot")
public record PolicyPilotProperties(String catalogPath, Embeddings embeddings) {

    public record Embeddings(String baseUrl, String apiKey, String model, int batchSize) {
    }
}
