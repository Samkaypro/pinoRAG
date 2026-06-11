package io.pinoRAG.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("schemaVersion")
public class SchemaVersionHealthIndicator implements HealthIndicator {

    private static final String LATEST_VERSION_SQL =
            "SELECT version, description, installed_on FROM flyway_schema_history " +
            "WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1";

    private final JdbcTemplate jdbc;

    public SchemaVersionHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            return jdbc.query(LATEST_VERSION_SQL, rs -> {
                if (!rs.next()) {
                    return Health.down().withDetail("reason", "no migrations applied").build();
                }
                return Health.up()
                        .withDetail("version", rs.getString("version"))
                        .withDetail("description", rs.getString("description"))
                        .withDetail("installedOn", String.valueOf(rs.getTimestamp("installed_on")))
                        .build();
            });
        } catch (DataAccessException ex) {
            return Health.down(ex).build();
        }
    }
}
