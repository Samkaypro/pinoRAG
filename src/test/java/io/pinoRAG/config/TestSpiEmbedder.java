package io.pinoRAG.config;

import io.pinoRAG.ingest.embed.Embedder;

import java.util.ArrayList;
import java.util.List;

// Public no-arg constructor required by ServiceLoader. Registered via
// src/test/resources/META-INF/services/io.pinoRAG.ingest.embed.Embedder.
// Used by SpiDiscoveryIntegrationTest to prove third-party JARs can land
// without touching pinoRAG source.
public class TestSpiEmbedder implements Embedder {

    public TestSpiEmbedder() {}

    @Override
    public String id() {
        return "test-spi";
    }

    @Override
    public int dimensions() {
        return 1536;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String ignored : texts) {
            out.add(new float[1536]);
        }
        return out;
    }
}
