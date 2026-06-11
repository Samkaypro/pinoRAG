package io.pinoRAG.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pinoRagOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("pinoRAG API")
                        .version("v1")
                        .description("Multi-tenant, citation-backed RAG API. " +
                                "Authenticate with X-API-Key or Authorization: Bearer."))
                .addSecurityItem(new SecurityRequirement()
                        .addList("ApiKey").addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key"))
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
