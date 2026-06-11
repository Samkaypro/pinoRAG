package io.pinoRAG.ingest;

import io.pinoRAG.document.DocumentResponse;
import io.pinoRAG.auth.ApiKeyAuthenticationFilter;
import io.pinoRAG.document.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
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
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = {
        BatchAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class,
        DataElasticsearchAutoConfiguration.class,
        DataElasticsearchRepositoriesAutoConfiguration.class,
})
@Testcontainers
class IngestEndToEndTest {

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
        r.add("pinorag.ingest.embedding-batch-size", () -> "8");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;

    private String apiKey;
    private Long collectionId;
    private Long tenantId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('ingest-tenant') RETURNING id",
                Long.class);
        collectionId = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, ?, ?) RETURNING id",
                Long.class, tenantId, "main", "fake");
        String prefix = "ingestpref";
        String secret = "ingestsecret";
        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) " +
                        "VALUES (?, ?, ?)",
                tenantId, prefix, sha256Hex(secret));
        apiKey = "prk_" + prefix + "." + secret;
    }

    @Test
    void uploadParsesChunksEmbedsAndReachesReady() {
        String body = "PinoRAG ingests documents, splits them into chunks, embeds each chunk, "
                + "and stores them in pgvector. ".repeat(20);
        DocumentResponse uploaded = uploadText("notes.txt", body);
        assertThat(uploaded.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(uploaded.id()).isNotNull();

        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    DocumentResponse d = fetchDocument(uploaded.id());
                    assertThat(d.status()).isIn(DocumentStatus.READY, DocumentStatus.FAILED);
                });

        DocumentResponse done = fetchDocument(uploaded.id());
        assertThat(done.status()).isEqualTo(DocumentStatus.READY);
        assertThat(done.chunkCount()).isGreaterThan(0);

        Long embeddings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pino_embeddings e " +
                        "JOIN pino_chunks c ON c.id = e.chunk_id " +
                        "WHERE c.document_id = ?", Long.class, done.id());
        assertThat(embeddings).isEqualTo(done.chunkCount());
    }

    @Test
    void reuploadIncrementsVersionAndDeprecatesPrior() {
        DocumentResponse first = uploadText("report.txt", "first version body content");
        Instant deadline = Instant.now().plusSeconds(45);
        while (Instant.now().isBefore(deadline)
                && fetchDocument(first.id()).status() == DocumentStatus.PENDING) {
            sleep(250);
        }
        DocumentResponse second = uploadText("report.txt", "second version body content");

        assertThat(second.version()).isEqualTo(2);

        String priorStatus = jdbc.queryForObject(
                "SELECT status FROM pino_documents WHERE id = ?",
                String.class, first.id());
        assertThat(priorStatus).isEqualTo("DEPRECATED");
    }

    private DocumentResponse uploadText(String filename, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        Resource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
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

    private DocumentResponse fetchDocument(Long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        ResponseEntity<DocumentResponse> r = http.exchange(
                "/v1/documents/" + id, HttpMethod.GET,
                new HttpEntity<>(headers), DocumentResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
