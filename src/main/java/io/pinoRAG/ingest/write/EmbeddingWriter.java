package io.pinoRAG.ingest.write;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// Writes vectors as a Postgres array literal and CASTs to pgvector. Avoids
// pulling in pgvector-java just to get one INSERT working.
@Component
public class EmbeddingWriter {

    private static final String SQL =
            "INSERT INTO pino_embeddings (chunk_id, model, dim, embedding) " +
                    "VALUES (?, ?, ?, CAST(? AS vector))";

    private final JdbcTemplate jdbc;

    public EmbeddingWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void writeAll(List<Long> chunkIds, List<float[]> vectors, String model, int dim) {
        if (chunkIds.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "chunkIds.size=" + chunkIds.size() + " != vectors.size=" + vectors.size());
        }
        for (int i = 0; i < chunkIds.size(); i++) {
            jdbc.update(SQL, chunkIds.get(i), model, dim, formatVector(vectors.get(i)));
        }
    }

    static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
