package io.pinoRAG.ingest.embed;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Wraps Spring AI's OpenAI embedder. Active when:
//   - pinorag.embedder.id = openai
//   - the OpenAiEmbeddingModel class is on the classpath
//   - Spring AI autoconfigured a bean (requires spring.ai.openai.api-key)
@Component
@ConditionalOnClass(OpenAiEmbeddingModel.class)
@ConditionalOnProperty(name = "pinorag.embedder.id", havingValue = "openai")
@ConditionalOnBean(OpenAiEmbeddingModel.class)
public class OpenAIEmbedder implements Embedder {

    private final OpenAiEmbeddingModel model;
    private final int dim;

    public OpenAIEmbedder(OpenAiEmbeddingModel model) {
        this.model = model;
        this.dim = model.dimensions();
    }

    @Override
    public String id() {
        return "openai";
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
