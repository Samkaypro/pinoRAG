package io.pinoRAG.ingest.chunking;

import java.util.List;

public interface ChunkingStrategy {

    String id();

    List<String> chunk(String text);
}
