package io.pinoRAG.retrieval;

import io.pinoRAG.ingest.embed.Embedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Seeds a small corpus with both lexical and (pseudo) semantic signal, then
// runs each retrieval mode against four Q/A pairs with known-good chunk IDs.
// Asserts:
//   1. BM25 beats VECTOR on the lexical-heavy queries (fake embedder makes
//      vector roughly random, so BM25 should dominate).
//   2. HYBRID's recall is at least as good as the best single retriever.
//   3. Cross-tenant chunks are never returned (tenant scope).
@SpringBootTest
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class RetrievalBenchmarkTest {

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
        r.add("pinorag.embedder.id", () -> "fake");
        r.add("pinorag.llm.id", () -> "fake");
        r.add("pinorag.ingest.upload-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private VectorRetriever vectorRetriever;
    @Autowired private BM25Retriever bm25Retriever;
    @Autowired private HybridRetriever hybridRetriever;
    @Autowired @Qualifier("hashingFakeEmbedder") private Embedder embedder;

    private Long tenantA;
    private Long tenantB;
    private Long collectionA;
    private Map<String, Long> chunkIds;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantA = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('t-a') RETURNING id", Long.class);
        tenantB = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('t-b') RETURNING id", Long.class);

        collectionA = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id", Long.class, tenantA);
        Long collectionB = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id", Long.class, tenantB);

        chunkIds = new java.util.HashMap<>();

        // Tenant A documents
        Long docA = createDoc(tenantA, collectionA, "manual.md");
        chunkIds.put("pricing_policy", insertChunk(tenantA, collectionA, docA, 0,
                "Our pricing policy charges customers monthly per active seat."));
        chunkIds.put("refund_window", insertChunk(tenantA, collectionA, docA, 1,
                "Customers can request a refund within 30 days of any purchase."));
        chunkIds.put("uptime_promise", insertChunk(tenantA, collectionA, docA, 2,
                "We promise 99.9 percent uptime measured monthly across regions."));
        chunkIds.put("data_retention", insertChunk(tenantA, collectionA, docA, 3,
                "User data is retained for 90 days after account deletion before purge."));
        chunkIds.put("support_hours", insertChunk(tenantA, collectionA, docA, 4,
                "Customer support is available 24 hours every day including weekends."));
        chunkIds.put("encryption_at_rest", insertChunk(tenantA, collectionA, docA, 5,
                "All stored data is encrypted at rest using AES 256."));
        chunkIds.put("audit_logs", insertChunk(tenantA, collectionA, docA, 6,
                "Audit logs capture every administrative action with timestamp and actor."));
        chunkIds.put("rate_limit_default", insertChunk(tenantA, collectionA, docA, 7,
                "API rate limit defaults to 60 requests per minute per key."));
        chunkIds.put("password_policy", insertChunk(tenantA, collectionA, docA, 8,
                "Passwords must be at least 12 characters and rotate every 90 days."));
        chunkIds.put("backup_schedule", insertChunk(tenantA, collectionA, docA, 9,
                "Backups run nightly at 02:00 UTC and are kept for 30 days."));
        chunkIds.put("ssl_required", insertChunk(tenantA, collectionA, docA, 10,
                "All connections require TLS 1.2 or higher; plaintext is rejected."));
        chunkIds.put("region_coverage", insertChunk(tenantA, collectionA, docA, 11,
                "Service is deployed across four regions: us-east, eu-west, ap-south, sa-east."));

        // Tenant B chunk that lexically matches one of our queries; the
        // retrievers must never return it.
        Long docB = createDoc(tenantB, collectionB, "tenant-b-manual.md");
        chunkIds.put("CROSS_TENANT_BAIT", insertChunk(tenantB, collectionB, docB, 0,
                "Our pricing policy is different from tenant A and should not appear."));
    }

    @Test
    void bm25BeatsVectorOnLexicalQueries() {
        // Each fixture is question -> set of chunk IDs that genuinely answer it.
        List<Fixture> fixtures = List.of(
                new Fixture("what is the refund policy",      Set.of(chunkIds.get("refund_window"))),
                new Fixture("how often are backups taken",    Set.of(chunkIds.get("backup_schedule"))),
                new Fixture("what is the password policy",    Set.of(chunkIds.get("password_policy"))),
                new Fixture("what is the api rate limit",     Set.of(chunkIds.get("rate_limit_default"))));

        double vectorRecall = recallAtK(vectorRetriever, fixtures, 5);
        double bm25Recall   = recallAtK(bm25Retriever,   fixtures, 5);

        // Fake embedder is essentially random; BM25 should dominate clearly.
        assertThat(bm25Recall).as("BM25 recall@5").isGreaterThan(vectorRecall);
        assertThat(bm25Recall).isEqualTo(1.0);
    }

    @Test
    void hybridRecallAtLeastMatchesTheBestSingleRetriever() {
        List<Fixture> fixtures = List.of(
                new Fixture("what is the refund policy",      Set.of(chunkIds.get("refund_window"))),
                new Fixture("how often are backups taken",    Set.of(chunkIds.get("backup_schedule"))),
                new Fixture("what is the password policy",    Set.of(chunkIds.get("password_policy"))));

        double vectorRecall = recallAtK(vectorRetriever, fixtures, 5);
        double bm25Recall   = recallAtK(bm25Retriever,   fixtures, 5);
        double hybridRecall = recallAtK(hybridRetriever, fixtures, 5);

        assertThat(hybridRecall).isGreaterThanOrEqualTo(Math.max(vectorRecall, bm25Recall));
    }

    @Test
    void crossTenantChunksNeverReturned() {
        // Question deliberately mentions the tenant-B chunk's exact phrase.
        // Retrievers scoped to tenant A must filter it out.
        RetrievalQuery q = embed("Our pricing policy is different");
        List<ScoredChunk> v = vectorRetriever.search(tenantA, collectionA, q, 20);
        List<ScoredChunk> b = bm25Retriever.search(tenantA, collectionA, q, 20);
        List<ScoredChunk> h = hybridRetriever.search(tenantA, collectionA, q, 20);

        Long bait = chunkIds.get("CROSS_TENANT_BAIT");
        assertThat(v).extracting(ScoredChunk::chunkId).doesNotContain(bait);
        assertThat(b).extracting(ScoredChunk::chunkId).doesNotContain(bait);
        assertThat(h).extracting(ScoredChunk::chunkId).doesNotContain(bait);
    }

    @Test
    void hybridRunsBothBranchesEvenWhenOneIsEmpty() {
        // Question whose embedding is meaningful but where BM25 returns
        // zero hits (no overlapping lexemes after Postgres stopword removal).
        RetrievalQuery q = embed("xxxxxxxx yyyyyyyy zzzzzzzz");
        List<ScoredChunk> hybrid = hybridRetriever.search(tenantA, collectionA, q, 5);
        // Hybrid must still return something from the vector lane.
        assertThat(hybrid).isNotEmpty();
    }

    // ----- helpers -----

    private double recallAtK(Retriever retriever, List<Fixture> fixtures, int k) {
        int hits = 0;
        int total = 0;
        for (Fixture f : fixtures) {
            RetrievalQuery q = embed(f.question);
            List<ScoredChunk> top = retriever.search(tenantA, collectionA, q, k);
            Set<Long> retrievedIds = new HashSet<>();
            top.forEach(c -> retrievedIds.add(c.chunkId()));
            for (Long expected : f.expectedIds) {
                total++;
                if (retrievedIds.contains(expected)) hits++;
            }
        }
        return total == 0 ? 0.0 : (double) hits / total;
    }

    private RetrievalQuery embed(String text) {
        float[] vec = embedder.embed(List.of(text)).get(0);
        return new RetrievalQuery(text, vec);
    }

    private Long createDoc(Long tenantId, Long collectionId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO pino_documents (tenant_id, collection_id, source_uri, status) " +
                        "VALUES (?, ?, ?, 'READY') RETURNING id",
                Long.class, tenantId, collectionId, name);
    }

    private Long insertChunk(Long tenantId, Long collectionId, Long docId, int ordinal, String body) {
        Long id = jdbc.queryForObject(
                "INSERT INTO pino_chunks (tenant_id, collection_id, document_id, ordinal, body) " +
                        "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, tenantId, collectionId, docId, ordinal, body);
        float[] vec = embedder.embed(List.of(body)).get(0);
        jdbc.update("INSERT INTO pino_embeddings (chunk_id, model, dim, embedding) " +
                        "VALUES (?, ?, ?, CAST(? AS vector))",
                id, embedder.id(), embedder.dimensions(), formatVector(vec));
        return id;
    }

    private static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private record Fixture(String question, Set<Long> expectedIds) {
        Fixture(String question, Long onlyExpected) {
            this(question, Set.of(onlyExpected));
        }
    }
}
