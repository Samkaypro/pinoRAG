package io.pinoRAG.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor perKey;
    private final AnonymousRateLimitInterceptor perIp;

    public RateLimitWebConfig(RateLimitInterceptor perKey,
                              AnonymousRateLimitInterceptor perIp) {
        this.perKey = perKey;
        this.perIp = perIp;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Order matters. The anonymous bucket fires first so unauthenticated
        // floods stop here. Authenticated requests fall through to the
        // per-key bucket.
        registry.addInterceptor(perIp).addPathPatterns("/v1/**").order(0);
        registry.addInterceptor(perKey).addPathPatterns("/v1/**").order(1);
    }
}
