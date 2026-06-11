package io.pinoRAG.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

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
class AuthIntegrationTest {

    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-32b";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("pinorag.auth.jwt.secret", () -> JWT_SECRET);
        r.add("pinorag.rate-limit.requests-per-minute", () -> "60");
        r.add("pinorag.rate-limit.burst", () -> "60");
        r.add("pinorag.rate-limit.anonymous-requests-per-minute", () -> "1000");
        r.add("pinorag.embedder.id", () -> "fake");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;

    private Long tenantAId;
    private Long tenantBId;
    private String validApiKeyA;
    private String validApiKeyB;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantAId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('tenant-a') RETURNING id",
                Long.class);
        tenantBId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('tenant-b') RETURNING id",
                Long.class);

        validApiKeyA = installKey(tenantAId, "aaaaaaaaa", "secret-a");
        validApiKeyB = installKey(tenantBId, "bbbbbbbbb", "secret-b");

        jdbc.update("INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, ?, ?)",
                tenantAId, "tenant-a-coll", "openai-text-embedding-3-small");
        jdbc.update("INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, ?, ?)",
                tenantBId, "tenant-b-coll", "openai-text-embedding-3-small");
    }

    @Test
    void noAuthHeaderReturns401() {
        ResponseEntity<String> r = http.getForEntity("/v1/collections", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidApiKeyReturns401() {
        HttpHeaders h = new HttpHeaders();
        h.add(ApiKeyAuthenticationFilter.HEADER, "prk_notreal.alsofake");
        ResponseEntity<String> r = http.exchange("/v1/collections", HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void validApiKeyForTenantAOnlySeesItsOwnCollections() {
        HttpHeaders h = new HttpHeaders();
        h.add(ApiKeyAuthenticationFilter.HEADER, validApiKeyA);

        ResponseEntity<List> r = http.exchange("/v1/collections", HttpMethod.GET,
                new HttpEntity<>(h), List.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(((java.util.Map) r.getBody().get(0)).get("name"))
                .isEqualTo("tenant-a-coll");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void validApiKeyForTenantBOnlySeesItsOwnCollections() {
        HttpHeaders h = new HttpHeaders();
        h.add(ApiKeyAuthenticationFilter.HEADER, validApiKeyB);

        ResponseEntity<List> r = http.exchange("/v1/collections", HttpMethod.GET,
                new HttpEntity<>(h), List.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(((java.util.Map) r.getBody().get(0)).get("name"))
                .isEqualTo("tenant-b-coll");
    }

    @Test
    void validJwtReturns200WithCallerCollections() {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("user-1")
                .claim("tid", tenantAId)
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(key)
                .compact();

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        ResponseEntity<List> r = http.exchange("/v1/collections", HttpMethod.GET,
                new HttpEntity<>(h), List.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
    }

    @Test
    void healthEndpointPublic() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String installKey(Long tenantId, String prefix, String secret) {
        String hashed = sha256Hex(secret);
        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) " +
                        "VALUES (?, ?, ?)",
                tenantId, prefix, hashed);
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
