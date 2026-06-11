package io.pinoRAG.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// pgvector cosine search via the `<=>` operator. Tenant + collection scope
// are SQL predicates, not Hibernate filters, because we bypass JPA on the
// vector path. Distance is converted to similarity (1 - distance) so the
// score in ScoredChunk is intuitive (higher is better).
@Component
public class VectorRetriever {

    private static final String SEARCH_SQL =
            "SELECT c.id AS chunk_id, " +
                    "       c.document_id AS document_id, " +
                    "       d.source_uri AS document_name, " +
                    "       c.body AS body, " +
                    "       e.embedding <=> CAST(? AS vector) AS distance " +
                    "FROM pino_chunks c " +
                    "JOIN pino_embeddings e ON e.chunk_id = c.id " +
                    "JOIN pino_documents d ON d.id = c.document_id " +
                    "WHERE c.tenant_id = ? " +
                    "  AND c.collection_id = ? " +
                    "  AND d.status = 'READY' " +
                    "ORDER BY distance ASC " +
                    "LIMIT ?";

    private final JdbcTemplate jdbc;

    public VectorRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ScoredChunk> search(Long tenantId, Long collectionId, float[] queryVector, int k) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        return jdbc.query(
                SEARCH_SQL,
                ps -> {
                    ps.setString(1, formatVector(queryVector));
                    ps.setLong(2, tenantId);
                    ps.setLong(3, collectionId);
                    ps.setInt(4, k);
                },
                (rs, i) -> new ScoredChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getString("body"),
                        1.0 - rs.getDouble("distance")));
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
