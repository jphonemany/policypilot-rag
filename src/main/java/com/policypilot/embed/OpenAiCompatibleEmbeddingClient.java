package com.policypilot.embed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.policypilot.PolicyPilotProperties;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls any OpenAI-compatible POST /embeddings endpoint: local Ollama by default, or OpenAI,
 * FreeLLMAPI and others by changing PP_EMBED_URL / PP_EMBED_MODEL. No vendor SDK, so switching
 * providers is a config change.
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RestClient http;
    private final String model;

    public OpenAiCompatibleEmbeddingClient(RestClient.Builder builder, PolicyPilotProperties props) {
        PolicyPilotProperties.Embeddings cfg = props.embeddings();
        this.model = cfg.model();
        this.http = builder
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Response res = http.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", texts))
                .retrieve()
                .body(Response.class);
        if (res == null || res.data() == null || res.data().size() != texts.size()) {
            throw new IllegalStateException("embedding endpoint returned " + (res == null || res.data() == null ? 0 : res.data().size())
                    + " vectors for " + texts.size() + " inputs");
        }
        // The API returns an index per vector; don't assume the list is already in input order.
        List<float[]> out = new ArrayList<>(texts.size());
        res.data().stream().sorted(Comparator.comparingInt(Item::index)).forEach(item -> out.add(item.embedding()));
        return out;
    }

    @Override
    public String model() {
        return model;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Item> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(int index, float[] embedding) {
    }
}
