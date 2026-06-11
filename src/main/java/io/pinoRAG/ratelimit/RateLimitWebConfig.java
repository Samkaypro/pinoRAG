package io.pinoRAG.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor perKey;
    private final AnonymousRateLimitInterceptor perIp;
    private final UploadRateLimitInterceptor uploadPerKey;

    public RateLimitWebConfig(RateLimitInterceptor perKey,
                              AnonymousRateLimitInterceptor perIp,
                              UploadRateLimitInterceptor uploadPerKey) {
        this.perKey = perKey;
        this.perIp = perIp;
        this.uploadPerKey = uploadPerKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Order matters: anonymous bucket fires first so unauthenticated
        // floods stop before they touch the auth chain. Authenticated
        // traffic then goes through the generic per-key bucket. The upload
        // bucket is a tighter additional gate for POST /v1/collections/.../documents.
        registry.addInterceptor(perIp).addPathPatterns("/v1/**").order(0);
        registry.addInterceptor(perKey).addPathPatterns("/v1/**").order(1);
        registry.addInterceptor(uploadPerKey)
                .addPathPatterns("/v1/collections/*/documents")
                .order(2);
    }
}
