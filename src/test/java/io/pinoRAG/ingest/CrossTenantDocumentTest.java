package io.pinoRAG.ingest;

import io.pinoRAG.document.DocumentResponse;
import io.pinoRAG.auth.ApiKeyAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.test.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

// Confirms tenant boundary enforcement is real:
// tenant A's API key cannot see tenant B's document via GET /v1/documents/{id}.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class CrossTenantDocumentTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path tempUploadDir;

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
        r.add("pinorag.ingest.upload-dir", () -> tempUploadDir.toString());
        r.add("pinorag.ingest.chunk-size", () -> "200");
        r.add("pinorag.ingest.chunk-overlap", () -> "40");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;

    private String keyA;
    private String keyB;
    private Long collectionA;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        Long tenantA = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('a') RETURNING id", Long.class);
        Long tenantB = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('b') RETURNING id", Long.class);

        collectionA = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id", Long.class, tenantA);

        keyA = installKey(tenantA, "tenantapref", "secret-a");
        keyB = installKey(tenantB, "tenantbpref", "secret-b");
    }

    @Test
    void tenantBCannotGetTenantADocument() {
        // Upload as tenant A
        DocumentResponse uploaded = uploadAs(keyA, "notes.txt", "tenant A only content here");

        // Wait briefly for the doc row to be visible (after-commit dispatch)
        sleepUntilExists(uploaded.id());

        // Tenant B asks for it: must be 404, never 200.
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.HEADER, keyB);
        ResponseEntity<String> r = http.exchange("/v1/documents/" + uploaded.id(),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noAuthHeaderOnDocumentReadReturns401() {
        DocumentResponse uploaded = uploadAs(keyA, "notes.txt", "secret content");
        sleepUntilExists(uploaded.id());

        ResponseEntity<String> r = http.getForEntity("/v1/documents/" + uploaded.id(), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private DocumentResponse uploadAs(String apiKey, String filename, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);

        ResponseEntity<DocumentResponse> r = http.exchange(
                "/v1/collections/" + collectionA + "/documents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                DocumentResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return r.getBody();
    }

    // The async dispatch fires after commit; we poll briefly so the rest of
    // the test runs against the persisted row.
    private void sleepUntilExists(Long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pino_documents WHERE id = ?", Long.class, id);
            if (count != null && count > 0) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private String installKey(Long tenantId, String prefix, String secret) {
        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) VALUES (?, ?, ?)",
                tenantId, prefix, sha256Hex(secret));
        return "prk_" + prefix + "." + secret;
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
