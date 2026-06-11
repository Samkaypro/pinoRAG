package io.pinoRAG.collection;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// Reads pino_collections.settings JSONB scoped by tenant + collection id.
// JdbcTemplate path so the lookup works from a virtual thread without a
// request scope (the ingest pipeline runs there). Returns defaults on miss
// or parse error so a misconfigured row never blocks ingestion.
@Component
public class CollectionSettingsLookup {

    private static final Logger log = LoggerFactory.getLogger(CollectionSettingsLookup.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CollectionSettingsLookup(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public CollectionSettings forCollection(Long tenantId, Long collectionId) {
        try {
            String raw = jdbc.queryForObject(
                    "SELECT settings::text FROM pino_collections " +
                            "WHERE id = ? AND tenant_id = ?",
                    String.class, collectionId, tenantId);
            if (raw == null || raw.isBlank()) {
                return CollectionSettings.defaults();
            }
            Map<String, Object> map = json.readValue(raw, MAP_TYPE);
            return CollectionSettings.of(map);
        } catch (EmptyResultDataAccessException ex) {
            return CollectionSettings.defaults();
        } catch (JacksonException ex) {
            log.warn("Failed to parse collection {} settings; using defaults", collectionId, ex);
            return CollectionSettings.defaults();
        }
    }
}
