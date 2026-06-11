package io.pinoRAG;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

// Smoke test that doesn't touch external services. The full schema and
// health checks are exercised in domain.SchemaIntegrationTest.
class PinoRagApplicationTests {

    @Test
    void mainClassIsLoadable() {
        // Spring's classpath is loaded but no application context starts here.
        // Guards against compile-time mistakes in the bootstrap class.
        Class<?> appClass = PinoRagApplication.class;
        if (appClass.getName().isEmpty()) {
            throw new IllegalStateException("bootstrap class missing");
        }
        // SpringApplication import keeps the dependency intentional.
        new SpringApplication(appClass);
    }
}
