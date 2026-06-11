package io.pinoRAG.retrieval;

import io.pinoRAG.ingest.embed.Embedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Array;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Real ACL enforcement test. Seeds three documents in the same tenant +
// collection: alice-owned, bob-owned, and public. Then queries as alice,
// as bob, as an anonymous caller (no subject), and as a member of a group
// the doc allows. Asserts each caller sees exactly what they should.
@SpringBootTest
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class AclEnforcementTest {

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
    @Autowired private BM25Retriever bm25;
    @Autowired private VectorRetriever vector;
    @Autowired @Qualifier("hashingFakeEmbedder") private Embedder embedder;

    private Long tenantId;
    private Long collectionId;
    private Long aliceChunkId;
    private Long bobChunkId;
    private Long publicChunkId;
    private Long engGroupChunkId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM pino_embeddings");
        jdbc.update("DELETE FROM pino_chunks");
        jdbc.update("DELETE FROM pino_documents");
        jdbc.update("DELETE FROM pino_collections");
        jdbc.update("DELETE FROM pino_tenants");

        tenantId = jdbc.queryForObject(
                "INSERT INTO pino_tenants (name) VALUES ('acl-t') RETURNING id", Long.class);
        collectionId = jdbc.queryForObject(
                "INSERT INTO pino_collections (tenant_id, name, embedding_model) " +
                        "VALUES (?, 'main', 'fake') RETURNING id",
                Long.class, tenantId);

        Long aliceDoc = insertDoc("alice-notes.md", "alice", new String[]{}, false);
        Long bobDoc   = insertDoc("bob-notes.md",   "bob",   new String[]{}, false);
        Long pubDoc   = insertDoc("public.md",      null,    new String[]{}, true);
        Long engDoc   = insertDoc("eng-secrets.md", "alice", new String[]{"engineering"}, false);

        aliceChunkId    = insertChunk(aliceDoc, "alice secret notes about pricing strategy");
        bobChunkId      = insertChunk(bobDoc,   "bob private memo about quarterly planning");
        publicChunkId   = insertChunk(pubDoc,   "company public mission statement about quality");
        engGroupChunkId = insertChunk(engDoc,   "engineering on-call rotation policy");
    }

    @Test
    void aliceSeesHerOwnDocsAndPublicButNotBobs() {
        RetrievalQuery q = embed("strategy policy memo notes mission rotation", "alice", new String[]{});
        List<ScoredChunk> hits = bm25.search(tenantId, collectionId, q, 50);
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();

        assertThat(ids).contains(aliceChunkId);
        assertThat(ids).contains(publicChunkId);
        assertThat(ids).contains(engGroupChunkId); // owner_subject=alice
        assertThat(ids).doesNotContain(bobChunkId);
    }

    @Test
    void bobSeesHisOwnDocsAndPublicButNotAlices() {
        RetrievalQuery q = embed("strategy policy memo notes mission rotation", "bob", new String[]{});
        List<ScoredChunk> hits = bm25.search(tenantId, collectionId, q, 50);
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();

        assertThat(ids).contains(bobChunkId);
        assertThat(ids).contains(publicChunkId);
        assertThat(ids).doesNotContain(aliceChunkId);
        assertThat(ids).doesNotContain(engGroupChunkId); // alice-owned + eng group, bob is in neither
    }

    @Test
    void anonymousSeesPublicOnly() {
        // null subject + empty groups -> only public docs (in this test
        // setup there are no owner_subject IS NULL docs except public).
        RetrievalQuery q = embed("strategy policy memo mission rotation", null, new String[]{});
        List<ScoredChunk> hits = bm25.search(tenantId, collectionId, q, 50);
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();

        assertThat(ids).contains(publicChunkId);
        assertThat(ids).doesNotContain(aliceChunkId);
        assertThat(ids).doesNotContain(bobChunkId);
        assertThat(ids).doesNotContain(engGroupChunkId);
    }

    @Test
    void groupMembershipGrantsAccessEvenIfNotOwner() {
        // carol is not alice or bob, but she is in the engineering group.
        // She should see the engineering doc (group match) and public.
        RetrievalQuery q = embed("strategy policy memo mission rotation",
                "carol", new String[]{"engineering"});
        List<ScoredChunk> hits = bm25.search(tenantId, collectionId, q, 50);
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();

        assertThat(ids).contains(engGroupChunkId);
        assertThat(ids).contains(publicChunkId);
        assertThat(ids).doesNotContain(aliceChunkId);
        assertThat(ids).doesNotContain(bobChunkId);
    }

    @Test
    void vectorRetrieverEnforcesAclToo() {
        // Asserts vector path filters identically to BM25; without this
        // a forgotten ACL clause in only one retriever would slip through.
        RetrievalQuery q = embed("anything", "bob", new String[]{});
        List<ScoredChunk> hits = vector.search(tenantId, collectionId, q, 50);
        List<Long> ids = hits.stream().map(ScoredChunk::chunkId).toList();

        assertThat(ids).doesNotContain(aliceChunkId);
        assertThat(ids).doesNotContain(engGroupChunkId);
    }

    // ----- helpers -----

    private Long insertDoc(String name, String owner, String[] groups, boolean isPublic) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            Array g = con.createArrayOf("text", groups);
            var ps = con.prepareStatement(
                    "INSERT INTO pino_documents " +
                            "(tenant_id, collection_id, source_uri, status, owner_subject, group_ids, is_public) " +
                            "VALUES (?, ?, ?, 'READY', ?, ?, ?) RETURNING id");
            ps.setLong(1, tenantId);
            ps.setLong(2, collectionId);
            ps.setString(3, name);
            ps.setString(4, owner);
            ps.setArray(5, g);
            ps.setBoolean(6, isPublic);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        });
    }

    private Long insertChunk(Long docId, String body) {
        Long id = jdbc.queryForObject(
                "INSERT INTO pino_chunks (tenant_id, collection_id, document_id, ordinal, body) " +
                        "VALUES (?, ?, ?, 0, ?) RETURNING id",
                Long.class, tenantId, collectionId, docId, body);
        float[] vec = embedder.embed(List.of(body)).get(0);
        jdbc.update("INSERT INTO pino_embeddings (chunk_id, model, dim, embedding) " +
                        "VALUES (?, ?, ?, CAST(? AS vector))",
                id, embedder.id(), embedder.dimensions(), formatVector(vec));
        return id;
    }

    private RetrievalQuery embed(String text, String subject, String[] groups) {
        float[] vec = embedder.embed(List.of(text)).get(0);
        return new RetrievalQuery(text, vec, subject, groups);
    }

    private static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder().append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
