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
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class PathTraversalAndDispatchTest {

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

    private String apiKey;
    private Long collectionId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        Long tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('t') RETURNING id", Long.class);
        collectionId = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id", Long.class, tenantId);
        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) " +
                        "VALUES (?, 'travpref', ?)",
                tenantId, sha256Hex("trav-secret"));
        apiKey = "prk_travpref.trav-secret";
    }

    @Test
    void traversalFilenameIsNeutralizedAndFileLandsInsideUploadRoot() throws Exception {
        DocumentResponse uploaded = upload("../../../etc/passwd",
                "trying to write outside the sandbox");

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM pino_documents WHERE id = ?",
                            String.class, uploaded.id());
                    assertThat(status).isIn("READY", "FAILED");
                });

        // Confirm sanitization: source_uri stored without path segments.
        String stored = jdbc.queryForObject(
                "SELECT source_uri FROM pino_documents WHERE id = ?",
                String.class, uploaded.id());
        assertThat(stored).isEqualTo("passwd");

        // Confirm physical location: every file under the upload root lives
        // beneath tenant-*. Nothing escaped to a parent directory.
        long escaped;
        try (var s = Files.walk(tempUploadDir)) {
            escaped = s.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(tempUploadDir))
                    .count();
        }
        assertThat(escaped).isZero();
    }

    @Test
    void asyncListenerSeesDocumentRowAfterCommit() {
        // Catches the "transaction not committed yet" footgun: the row must
        // be visible to the @Async pipeline. If the bug regresses, the doc
        // stays PENDING forever and this fails by timeout.
        DocumentResponse uploaded = upload("ok.txt", "small body that should reach READY");

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM pino_documents WHERE id = ?",
                            String.class, uploaded.id());
                    assertThat(status).isEqualTo("READY");
                });
    }

    private DocumentResponse upload(String filename, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);

        ResponseEntity<DocumentResponse> r = http.exchange(
                "/v1/collections/" + collectionId + "/documents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                DocumentResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return r.getBody();
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
