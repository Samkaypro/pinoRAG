package io.pinoRAG.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.List;

// Postgres full-text retrieval over the STORED body_tsv generated column.
// websearch_to_tsquery defaults to AND between terms; we rewrite the input
// to OR so any single matching term ranks the chunk. Same ACL clause as
// VectorRetriever (is_public OR owner_subject IS NULL OR owner_subject =
// caller OR group_ids overlaps caller groups).
@Component
public class BM25Retriever implements Retriever {

    private static final String SEARCH_SQL =
            "SELECT c.id AS chunk_id, " +
                    "       c.document_id AS document_id, " +
                    "       d.source_uri AS document_name, " +
                    "       c.body AS body, " +
                    "       ts_rank_cd(c.body_tsv, q.query) AS rank " +
                    "FROM pino_chunks c " +
                    "JOIN pino_documents d ON d.id = c.document_id, " +
                    "     websearch_to_tsquery('english', ?) AS q(query) " +
                    "WHERE c.tenant_id = ? " +
                    "  AND c.collection_id = ? " +
                    "  AND d.status = 'READY' " +
                    "  AND c.body_tsv @@ q.query " +
                    "  AND (d.is_public = TRUE " +
                    "       OR d.owner_subject IS NULL " +
                    "       OR d.owner_subject = ? " +
                    "       OR d.group_ids && ?) " +
                    "ORDER BY rank DESC " +
                    "LIMIT ?";

    private final JdbcTemplate jdbc;

    public BM25Retriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RetrievalMode mode() {
        return RetrievalMode.BM25;
    }

    @Override
    public List<ScoredChunk> search(Long tenantId, Long collectionId, RetrievalQuery query, int k) {
        String text = query.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String ored = toOrQuery(text);
        if (ored.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                SEARCH_SQL,
                ps -> {
                    Array groupsArray = ps.getConnection().createArrayOf(
                            "text", query.groups() == null ? new String[0] : query.groups());
                    ps.setString(1, ored);
                    ps.setLong(2, tenantId);
                    ps.setLong(3, collectionId);
                    ps.setString(4, query.subject());
                    ps.setArray(5, groupsArray);
                    ps.setInt(6, k);
                },
                (rs, i) -> new ScoredChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getString("body"),
                        rs.getDouble("rank")));
    }

    static String toOrQuery(String text) {
        String[] tokens = text.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            String clean = t.replaceAll("[^\\p{L}\\p{N}_-]", "");
            if (clean.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(clean);
        }
        return sb.toString();
    }
}
