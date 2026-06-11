package io.pinoRAG.ratelimit;

import io.pinoRAG.auth.ApiKeyAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.data.elasticsearch.autoconfigure.ElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.test.autoconfigure.AutoConfigureTestRestTemplate;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@EnableAutoConfiguration(exclude = {
        BatchAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class,
        ElasticsearchDataAutoConfiguration.class,
        ElasticsearchRepositoriesAutoConfiguration.class,
})
@Testcontainers
class RateLimitIntegrationTest {

    private static final int RPM = 5;

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
        r.add("pinorag.rate-limit.requests-per-minute", () -> String.valueOf(RPM));
        r.add("pinorag.rate-limit.burst", () -> String.valueOf(RPM));
        r.add("pinorag.rate-limit.anonymous-requests-per-minute", () -> "1000");
        r.add("pinorag.embedder.id", () -> "fake");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;

    private String apiKey;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_api_keys");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        Long tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('rl-tenant') RETURNING id",
                Long.class);

        String prefix = "rlprefixaa";
        String secret = "rl-secret";
        jdbc.update("INSERT INTO pino_api_keys (tenant_id, prefix, hashed_secret) " +
                        "VALUES (?, ?, ?)",
                tenantId, prefix, sha256Hex(secret));
        apiKey = "prk_" + prefix + "." + secret;
    }

    @Test
    void burstPlusOneReturns429WithRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.add(ApiKeyAuthenticationFilter.HEADER, apiKey);
        HttpEntity<Void> req = new HttpEntity<>(h);

        for (int i = 0; i < RPM; i++) {
            ResponseEntity<String> r = http.exchange("/v1/collections",
                    HttpMethod.GET, req, String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> limited = http.exchange("/v1/collections",
                HttpMethod.GET, req, String.class);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
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
