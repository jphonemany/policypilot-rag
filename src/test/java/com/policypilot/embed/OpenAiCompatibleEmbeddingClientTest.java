package com.policypilot.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.policypilot.PolicyPilotProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleEmbeddingClientTest {

    private MockRestServiceServer server;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleEmbeddingClient(builder,
                new PolicyPilotProperties("unused", new PolicyPilotProperties.Embeddings("http://embed.test/v1", "k", "all-minilm", 32)));
    }

    @Test
    void returnsVectorsInInputOrderEvenWhenTheApiShufflesThem() {
        server.expect(requestTo("http://embed.test/v1/embeddings"))
                .andExpect(header("Authorization", "Bearer k"))
                .andExpect(jsonPath("$.model").value("all-minilm"))
                .andExpect(jsonPath("$.input[1]").value("second"))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.2,0.2]},{"index":0,"embedding":[0.1,0.1]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("first", "second"));

        assertThat(vectors.get(0)).containsExactly(0.1f, 0.1f);
        assertThat(vectors.get(1)).containsExactly(0.2f, 0.2f);
        server.verify();
    }

    @Test
    void failsLoudlyWhenTheApiReturnsTooFewVectors() {
        server.expect(requestTo("http://embed.test/v1/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("a", "b"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 vectors for 2 inputs");
    }

    @Test
    void emptyInputMakesNoRequest() {
        assertThat(client.embed(List.of())).isEmpty();
        server.verify();
    }
}
