package com.docqa.rag.testsupport;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic offline embedding model: a hashed bag of words, L2-normalised.
 *
 * <p><b>Why not just return random or constant vectors.</b> The tests that
 * matter most here are the threshold and refusal tests, and both are
 * meaningless against a model whose similarities carry no signal - random
 * vectors make everything refuse, constant vectors make nothing refuse, and in
 * either case the test passes for the wrong reason and would keep passing if the
 * threshold logic were deleted.
 *
 * <p>Hashing tokens into a sparse vector and normalising gives cosine similarity
 * that behaves the way a real embedding model does at the coarse level the tests
 * assert on: identical text scores 1.0, heavy word overlap scores high, disjoint
 * vocabulary scores ~0. That is enough to prove the threshold is applied, that
 * the refusal path fires on an out-of-scope question, and that a near-miss lands
 * below the bar - without an API key, a network call, or any flakiness.
 *
 * <p>It is emphatically <em>not</em> semantic: it cannot match "late fee" to
 * "penalty for delayed payment". Tests are written with vocabulary overlap in
 * mind, and that limitation is noted in the README - real semantic quality is
 * measured against a real provider, not here.
 */
public class StubEmbeddingModel implements EmbeddingModel {

    private final int dimensions;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger textCount = new AtomicInteger();

    public StubEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        callCount.incrementAndGet();
        List<String> inputs = request.getInstructions();
        textCount.addAndGet(inputs.size());

        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(embed(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : tokenise(text)) {
            int index = Math.floorMod(token.hashCode(), dimensions);
            vector[index] += 1.0f;
        }
        return normalise(vector);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** How many provider calls were made. Used to assert batching actually batches. */
    public int callCount() {
        return callCount.get();
    }

    /** How many texts were embedded in total. */
    public int textCount() {
        return textCount.get();
    }

    public void reset() {
        callCount.set(0);
        textCount.set(0);
    }

    private static List<String> tokenise(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Unit length, so the dot product Postgres computes is exactly cosine
     * similarity. An all-zero vector (text with no alphanumerics) is given a
     * single fixed component: pgvector cannot compute cosine distance against a
     * zero vector and would return NaN.
     */
    private static float[] normalise(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        if (sumOfSquares == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
