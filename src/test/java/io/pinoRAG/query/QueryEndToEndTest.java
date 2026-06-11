package io.pinoRAG.query;

import io.pinoRAG.auth.ApiKeyAuthenticationFilter;
import io.pinoRAG.ingest.embed.Embedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Real end-to-end query test. Seeds chunks + embeddings using the same fake
// embedder the production code uses, then sends one POST to /v1/query and
// parses the SSE stream as text. Asserts on the OBSERVABLE behaviour:
// the response body contains status, citation, token, and done events in
// the right order, the cited chunk ids match what we seeded, and the
// query log row was written. The test fails if any of those regress.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class QueryEndToEndTest {

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
        r.add("pinorag.query.min-score", () -> "-1.0"); // accept anything in tests
        r.add("pinorag.ingest.upload-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @Autowired private Embedder embedder;

    private String apiKey;
    private Long collectionId;
    private Long tenantId;
    private Long chunkAId;
    private Long chunkBId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_query_log");
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('q-tenant') RETURNING id",
                Long.class);
        collectionId = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id",
                Long.class, tenantId);

        Long docId = jdbc.queryForObject(
                "INSERT INTO pino_documents (tenant_id, collection_id, source_uri, status) " +
                        "VALUES (?, ?, 'manual.md', 'READY') RETURNING id",
                Long.class, tenantId, collectionId);

        // Two chunks, one of which exactly matches the question text so the
        // fake embedder ranks it highest deterministically.
        chunkAId = insertChunk(docId, 0, "the answer to life is 42");
        chunkBId = insertChunk(docId, 1, "unrelated filler content");

        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) VALUES (?, ?, ?)",
                tenantId, "querypref", sha256Hex("query-secret"));
        apiKey = "prk_querypref.query-secret";
    }

    @Test
    void happyPathReturnsStatusCitationTokenDoneInOrder() {
        ResponseEntity<String> r = postQuery(collectionId,
                "what is the answer to life", null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().getType()).isEqualTo("text");
        assertThat(r.getHeaders().getContentType().getSubtype()).isEqualTo("event-stream");

        String body = r.getBody();
        List<String> eventNames = parseEventNames(body);

        // Required event types
        assertThat(eventNames).contains("status").contains("citation")
                .contains("token").contains("done");

        // Status appears BEFORE the first citation; citation BEFORE the first token.
        assertThat(eventNames.indexOf("status"))
                .isLessThan(eventNames.indexOf("citation"));
        assertThat(eventNames.indexOf("citation"))
                .isLessThan(eventNames.indexOf("token"));
        // Done is last
        assertThat(eventNames.get(eventNames.size() - 1)).isEqualTo("done");

        // The cited chunk id must be one of the chunks we seeded.
        assertThat(body).containsAnyOf("\"chunkId\":" + chunkAId,
                "\"chunkId\":" + chunkBId);
    }

    @Test
    void queryLogRowWrittenAfterRun() {
        postQuery(collectionId, "what is the answer to life", null);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pino_query_log WHERE tenant_id = ?",
                Long.class, tenantId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void fallbackPathFiresWhenMinScoreBlocksEveryChunk() {
        ResponseEntity<String> r = postQuery(collectionId,
                "question that no chunk should match",
                2.0 /* impossible: scores cannot exceed 1 */);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        List<String> events = parseEventNames(body);

        // Fallback path: NO citation events.
        assertThat(events).doesNotContain("citation");
        // Fallback still emits a token (the configured message) and a done.
        assertThat(events).contains("token").endsWith("done");
    }

    @Test
    void noAuthHeaderReturns401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"collectionId\":" + collectionId + ",\"question\":\"x\"}";
        ResponseEntity<String> r = http.exchange("/v1/query", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----- helpers -----

    private ResponseEntity<String> postQuery(Long cid, String question, Double minScore) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        h.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));

        StringBuilder body = new StringBuilder("{\"collectionId\":").append(cid)
                .append(",\"question\":\"").append(question.replace("\"", "\\\"")).append("\"");
        if (minScore != null) {
            body.append(",\"minScore\":").append(minScore);
        }
        body.append("}");

        return http.exchange("/v1/query", HttpMethod.POST,
                new HttpEntity<>(body.toString(), h), String.class);
    }

    private Long insertChunk(Long docId, int ordinal, String body) {
        Long id = jdbc.queryForObject(
                "INSERT INTO pino_chunks (tenant_id, collection_id, document_id, ordinal, body) " +
                        "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, tenantId, collectionId, docId, ordinal, body);
        float[] vec = embedder.embed(List.of(body)).get(0);
        jdbc.update("INSERT INTO pino_embeddings (chunk_id, model, dim, embedding) " +
                        "VALUES (?, ?, ?, CAST(? AS vector))",
                id, embedder.id(), embedder.dimensions(), vectorLiteral(vec));
        return id;
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static final Pattern EVENT_LINE = Pattern.compile("(?m)^event:\\s*(\\w+)\\s*$");

    private static List<String> parseEventNames(String body) {
        Matcher m = EVENT_LINE.matcher(body == null ? "" : body);
        return m.results().map(r -> r.group(1)).toList();
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
