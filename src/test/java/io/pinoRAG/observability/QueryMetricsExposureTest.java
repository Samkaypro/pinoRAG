package io.pinoRAG.observability;

import io.pinoRAG.auth.ApiKeyAuthenticationFilter;
import io.pinoRAG.ingest.embed.Embedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the Phase 8 metrics actually show up at /actuator/prometheus
// after one real query. Without this test the metric names could rot
// silently (typo in the timer name, missing prometheus registry) and
// dashboards would just go dark.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMetrics
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class QueryMetricsExposureTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("pinorag.auth.jwt.secret",
                () -> "test-secret-test-secret-test-secret-32b");
        r.add("pinorag.rate-limit.requests-per-minute", () -> "1000");
        r.add("pinorag.rate-limit.burst", () -> "1000");
        r.add("pinorag.rate-limit.anonymous-requests-per-minute", () -> "1000");
        r.add("pinorag.embedder.id", () -> "fake");
        r.add("pinorag.llm.id", () -> "fake");
        r.add("pinorag.query.min-score", () -> "-1.0");
        r.add("pinorag.ingest.upload-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @Autowired @Qualifier("hashingFakeEmbedder") private Embedder embedder;

    private String apiKey;
    private Long collectionId;
    private Long tenantId;

    @BeforeEach
    void seed() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory());
        jdbc.update("DELETE FROM pino_query_log");
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('m-tenant') RETURNING id", Long.class);
        collectionId = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id", Long.class, tenantId);
        Long docId = jdbc.queryForObject(
                "INSERT INTO pino_documents (tenant_id, collection_id, source_uri, status) " +
                        "VALUES (?, ?, 'manual.md', 'READY') RETURNING id",
                Long.class, tenantId, collectionId);
        insertChunk(docId, "the answer to life is 42");

        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) VALUES (?, ?, ?)",
                tenantId, "metricsp1", sha256Hex("metrics-secret"));
        apiKey = "prk_metricsp1.metrics-secret";
    }

    @Test
    void prometheusEndpointExposesQueryMetricsAfterOneQuery() {
        // Fire one query so the timers + counter get a sample.
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        h.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        String body = "{\"collectionId\":" + collectionId
                + ",\"question\":\"what is the answer to life\"}";
        ResponseEntity<String> q = http.exchange("/v1/query", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Scrape Prometheus.
        ResponseEntity<String> scrape = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String metrics = scrape.getBody();

        // Every custom timer + counter must be exposed.
        assertThat(metrics).contains("pinorag_query_ttft_seconds");
        assertThat(metrics).contains("pinorag_query_latency_seconds");
        assertThat(metrics).contains("pinorag_retrieve_latency_seconds");
        assertThat(metrics).contains("pinorag_query_total");

        // Mode tag is present.
        assertThat(metrics).containsPattern("pinorag_query_total\\{[^}]*mode=");
    }

    // ----- helpers -----

    private void insertChunk(Long docId, String body) {
        Long id = jdbc.queryForObject(
                "INSERT INTO pino_chunks (tenant_id, collection_id, document_id, ordinal, body) " +
                        "VALUES (?, ?, ?, 0, ?) RETURNING id",
                Long.class, tenantId, collectionId, docId, body);
        float[] vec = embedder.embed(List.of(body)).get(0);
        jdbc.update("INSERT INTO pino_embeddings (chunk_id, model, dim, embedding) " +
                        "VALUES (?, ?, ?, CAST(? AS vector))",
                id, embedder.id(), embedder.dimensions(), formatVector(vec));
    }

    private static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
