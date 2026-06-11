package io.pinoRAG.ingest.embed;

import java.util.List;

public interface Embedder {

    String id();

    int dimensions();

    List<float[]> embed(List<String> texts);
}
