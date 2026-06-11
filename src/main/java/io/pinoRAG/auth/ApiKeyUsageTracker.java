package io.pinoRAG.auth;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Coalesces per-request "last used" updates into a single batched UPDATE
// every 10 seconds. The latest timestamp wins per key id. Loss of the
// in-memory map on shutdown is acceptable: a missing touch is at most one
// stale heartbeat, not a correctness issue.
@Component
public class ApiKeyUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyUsageTracker.class);
    private static final String UPDATE_SQL =
            "UPDATE pino_api_keys SET last_used_at = ? WHERE id = ?";

    private final ConcurrentMap<Long, OffsetDateTime> pending = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public ApiKeyUsageTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void touch(Long apiKeyId) {
        if (apiKeyId == null) return;
        pending.merge(apiKeyId, OffsetDateTime.now(),
                (a, b) -> a.isAfter(b) ? a : b);
    }

    @Scheduled(fixedDelayString = "${pinorag.auth.touch-flush-millis:10000}")
    public void flush() {
        if (pending.isEmpty()) return;
        Map<Long, OffsetDateTime> snapshot = new HashMap<>();
        pending.forEach((id, ts) -> {
            snapshot.put(id, ts);
            pending.remove(id, ts);
        });
        if (snapshot.isEmpty()) return;
        try {
            List<Map.Entry<Long, OffsetDateTime>> batch = new ArrayList<>(snapshot.entrySet());
            jdbc.batchUpdate(UPDATE_SQL, new BatchPreparedStatementSetter() {
                @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setTimestamp(1, Timestamp.from(batch.get(i).getValue().toInstant()));
                    ps.setLong(2, batch.get(i).getKey());
                }
                @Override public int getBatchSize() { return batch.size(); }
            });
        } catch (Exception ex) {
            log.warn("Failed to flush {} api-key touches", snapshot.size(), ex);
        }
    }

    @PreDestroy
    void shutdown() {
        flush();
    }
}
