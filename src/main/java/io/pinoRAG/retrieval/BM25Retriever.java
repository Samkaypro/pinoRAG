package io.pinoRAG.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// Postgres full-text retrieval. body_tsv is a STORED generated column
// (see V1__init.sql) with a GIN index, so to_tsvector recomputation is
// not in the hot path.
//
// websearch_to_tsquery treats space as AND by default. For BM25-style
// recall we want OR between terms: a chunk matching ANY query term should
// be scored. We rewrite the input by inserting the literal token "OR"
// between whitespace-separated terms; websearch_to_tsquery recognizes the
// word "OR" as the OR operator. Stopword removal and stemming still apply.
// ts_rank_cd weights cover-density, so chunks with more distinct query
// terms close together still rank higher than chunks with just one.
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
                    ps.setString(1, ored);
                    ps.setLong(2, tenantId);
                    ps.setLong(3, collectionId);
                    ps.setInt(4, k);
                },
                (rs, i) -> new ScoredChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_name"),
                        rs.getString("body"),
                        rs.getDouble("rank")));
    }

    // Visible for testing. Splits on whitespace, drops anything that is not
    // a word character so weird input does not confuse websearch_to_tsquery,
    // and joins with " OR " so any single term suffices to match.
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
