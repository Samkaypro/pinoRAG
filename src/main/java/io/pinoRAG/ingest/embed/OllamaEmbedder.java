package io.pinoRAG.ingest.embed;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Wraps Spring AI's Ollama embedding model. The exact response shape varies
// across Spring AI milestones, so we read the canonical embed(List) method
// (returns float[][]) and copy each row. Active only when the property
// selects ollama AND Spring AI autoconfigured an EmbeddingModel bean.
@Component
@ConditionalOnProperty(name = "pinorag.embedder.id", havingValue = "ollama")
@ConditionalOnBean(EmbeddingModel.class)
public class OllamaEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final int dim;

    public OllamaEmbedder(EmbeddingModel model) {
        this.model = model;
        this.dim = model.dimensions();
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public int dimensions() {
        return dim;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        float[][] raw = model.embed(texts);
        List<float[]> out = new java.util.ArrayList<>(raw.length);
        for (float[] row : raw) {
            out.add(row);
        }
        return out;
    }
}
