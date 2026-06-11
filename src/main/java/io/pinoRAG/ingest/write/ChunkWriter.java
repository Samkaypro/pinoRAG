package io.pinoRAG.ingest.write;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

// Writes chunks directly via JdbcTemplate to skip Hibernate's per-row
// bookkeeping. Returns the generated ids in input order so the embedding
// writer can pair them with vectors.
@Component
public class ChunkWriter {

    private static final String SQL =
            "INSERT INTO pino_chunks " +
                    "(tenant_id, collection_id, document_id, ordinal, body, tokens, metadata) " +
                    "VALUES (?, ?, ?, ?, ?, NULL, '{}'::jsonb)";

    private final JdbcTemplate jdbc;

    public ChunkWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> writeAll(Long tenantId, Long collectionId, Long documentId, List<String> bodies) {
        List<Long> ids = new java.util.ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            ids.add(writeOne(tenantId, collectionId, documentId, bodies.get(i), i));
        }
        return ids;
    }

    private Long writeOne(Long tenantId, Long collectionId, Long documentId, String body, int ordinal) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(SQL, new String[]{"id"});
            ps.setLong(1, tenantId);
            ps.setLong(2, collectionId);
            ps.setLong(3, documentId);
            ps.setInt(4, ordinal);
            ps.setString(5, body);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert returned no id for chunk");
        }
        return key.longValue();
    }
}
