package io.pinoRAG.ingest.embed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Deterministic embedder for tests and offline boot. Produces a unit vector
// derived from text content. Active when pinorag.embedder.id = fake.
@Component
@ConditionalOnProperty(name = "pinorag.embedder.id", havingValue = "fake", matchIfMissing = false)
public class HashingFakeEmbedder implements Embedder {

    private final int dim;

    public HashingFakeEmbedder() {
        this.dim = 1536;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public int dimensions() {
        return dim;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(vectorFor(text));
        }
        return out;
    }

    private float[] vectorFor(String text) {
        Random rng = new Random(text.hashCode());
        float[] v = new float[dim];
        double sumSquares = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) rng.nextGaussian();
            sumSquares += v[i] * v[i];
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) v[i] /= norm;
        }
        return v;
    }
}
