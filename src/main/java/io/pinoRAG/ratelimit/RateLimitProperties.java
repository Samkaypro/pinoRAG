package io.pinoRAG.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pinorag.rate-limit")
public record RateLimitProperties(
        int requestsPerMinute,
        int burst,
        int anonymousRequestsPerMinute
) {
}
