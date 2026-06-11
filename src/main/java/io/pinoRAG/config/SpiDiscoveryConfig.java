package io.pinoRAG.config;

import io.pinoRAG.ingest.embed.Embedder;
import io.pinoRAG.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ServiceLoader;

// META-INF/services discovery for third-party SPI implementations.
//
// A plugin JAR ships an implementation of Embedder or LlmClient plus a
// META-INF/services/<fully-qualified-interface> file naming the class.
// ServiceLoader instantiates each impl via its public no-arg constructor;
// we then register the instance as a Spring singleton so it shows up in
// the List<Embedder> and List<LlmClient> the selectors inject.
//
// Notes for plugin authors:
//   - SPI-discovered impls do NOT get @Autowired - they are plain POJOs.
//     If you need Spring deps, ship an @AutoConfiguration in your JAR
//     instead and let Spring Boot wire it.
//   - Each impl's id() must be unique. The Selector logs available ids at
//     boot; misconfiguration fails fast.
//   - One bad plugin will NOT break boot; we log the failure and skip it.
@Configuration
public class SpiDiscoveryConfig {

    private static final Logger log = LoggerFactory.getLogger(SpiDiscoveryConfig.class);

    @Bean
    public static BeanFactoryPostProcessor spiBeanRegistrar() {
        return SpiDiscoveryConfig::registerAll;
    }

    private static void registerAll(ConfigurableListableBeanFactory factory) {
        register(factory, Embedder.class);
        register(factory, LlmClient.class);
    }

    private static <T> void register(ConfigurableListableBeanFactory factory, Class<T> type) {
        ServiceLoader<T> loader = ServiceLoader.load(type, SpiDiscoveryConfig.class.getClassLoader());
        for (ServiceLoader.Provider<T> provider : loader.stream().toList()) {
            T instance;
            try {
                instance = provider.get();
            } catch (Throwable ex) {
                log.warn("Skipping SPI {} provider {} (load failed)",
                        type.getSimpleName(), provider.type().getName(), ex);
                continue;
            }
            String name = "spi$" + type.getSimpleName() + "$" + instance.getClass().getName();
            if (factory.containsSingleton(name)) {
                continue;
            }
            factory.registerSingleton(name, instance);
            log.info("Registered SPI {} -> {}", type.getSimpleName(), instance.getClass().getName());
        }
    }
}
