package io.pinoRAG.config;

import io.pinoRAG.ingest.embed.Embedder;
import io.pinoRAG.ingest.embed.EmbedderSelector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Proves that a third-party Embedder shipped via META-INF/services is
// discovered, registered as a Spring bean, and selectable by id.
// The implementing class is TestSpiEmbedder; its registration file is at
// src/test/resources/META-INF/services/io.pinoRAG.ingest.embed.Embedder.
@SpringBootTest
@EnableAutoConfiguration(exclude = BatchAutoConfiguration.class)
@Testcontainers
class SpiDiscoveryIntegrationTest {

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
        // Pick the SPI-registered embedder as the active one.
        r.add("pinorag.embedder.id", () -> "test-spi");
        r.add("pinorag.llm.id", () -> "fake");
        r.add("pinorag.ingest.upload-dir", () -> System.getProperty("java.io.tmpdir"));
    }

    @Autowired private EmbedderSelector selector;
    @Autowired private List<Embedder> allEmbedders;

    @Test
    void discoveredEmbedderShowsUpInTheRegistry() {
        // Both the @Component-registered HashingFakeEmbedder (gated off in
        // this run by the id property) and the SPI-registered TestSpiEmbedder
        // should be wired into the application context.
        boolean spiFound = allEmbedders.stream().anyMatch(e -> "test-spi".equals(e.id()));
        assertThat(spiFound)
                .as("test-spi embedder must be in the registry")
                .isTrue();
    }

    @Test
    void selectorChoosesSpiImplWhenConfigured() {
        assertThat(selector.active().id()).isEqualTo("test-spi");
        assertThat(selector.active().dimensions()).isEqualTo(1536);
    }

    @Test
    void spiInstanceIsRegisteredAsSpringBean() {
        // The bean is registered via BeanFactoryPostProcessor at boot;
        // it must be retrievable by type from the context.
        boolean asSpringBean = allEmbedders.stream()
                .map(Object::getClass)
                .map(Class::getName)
                .anyMatch("io.pinoRAG.config.TestSpiEmbedder"::equals);
        assertThat(asSpringBean).isTrue();
    }
}
