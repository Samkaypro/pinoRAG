package io.pinoRAG.ingest.embed;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Wraps Spring AI's Ollama embedder. We bind to the concrete model class
// so the ApplicationContext stays unambiguous when both Ollama and OpenAI
// starters are on the classpath at once. Active when all three hold:
//   - pinorag.embedder.id = ollama
//   - the OllamaEmbeddingModel class is on the classpath
//   - Spring AI actually autoconfigured the bean
@Component
@ConditionalOnClass(OllamaEmbeddingModel.class)
@ConditionalOnProperty(name = "pinorag.embedder.id", havingValue = "ollama")
@ConditionalOnBean(OllamaEmbeddingModel.class)
public class OllamaEmbedder implements Embedder {

    private final OllamaEmbeddingModel model;
    private final int dim;

    public OllamaEmbedder(OllamaEmbeddingModel model) {
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
        return model.embed(texts);
    }
}
