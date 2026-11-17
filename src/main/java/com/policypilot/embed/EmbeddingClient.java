package com.policypilot.embed;

import java.util.List;

/** Turns text into vectors. Kept as an interface so tests and other providers can swap in. */
public interface EmbeddingClient {

    /** One vector per input, in the same order as the inputs. */
    List<float[]> embed(List<String> texts);

    /** Stored next to each vector: vectors from different models can't be compared. */
    String model();
}
