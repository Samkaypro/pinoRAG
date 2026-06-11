package io.pinoRAG;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = {
        BatchAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class,
        DataElasticsearchAutoConfiguration.class,
        DataElasticsearchRepositoriesAutoConfiguration.class,
})
@Testcontainers
class SchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pinorag.auth.jwt.secret",
                () -> "test-secret-test-secret-test-secret-32b");
        registry.add("pinorag.embedder.id", () -> "fake");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Test
    void allPinoTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name LIKE 'pino_%' " +
                        "ORDER BY table_name",
                String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(Set.of(
                "pino_tenants",
                "pino_api_keys",
                "pino_collections",
                "pino_documents",
                "pino_chunks",
                "pino_embeddings",
                "pino_query_log",
                "pino_ingest_log",
                "pino_jobs"));
    }

    @Test
    void pgvectorExtensionInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void embeddingsHasIvfflatIndex() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND tablename = 'pino_embeddings'",
                String.class);
        assertThat(indexes).contains("idx_pino_embeddings_vec");
    }

    @Test
    void chunksHasGinTsvectorIndex() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND tablename = 'pino_chunks'",
                String.class);
        assertThat(indexes).contains("idx_pino_chunks_tsv");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void healthEndpointReportsSchemaVersion() {
        ResponseEntity<Map> resp = http.getForEntity("/actuator/health", Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsEntry("status", "UP");

        Map components = (Map) resp.getBody().get("components");
        assertThat(components).isNotNull();
        assertThat(components).containsKey("schemaVersion");
    }

    @Test
    void insertAndReadTenantRoundtrips() {
        jdbc.update("INSERT INTO pino_tenants (name) VALUES ('acme')");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pino_tenants WHERE name = 'acme'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
