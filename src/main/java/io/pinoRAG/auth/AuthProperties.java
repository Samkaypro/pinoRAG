package io.pinoRAG.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pinorag.auth")
public record AuthProperties(
        Jwt jwt,
        RateLimit rateLimit
) {

    public record Jwt(
            // HMAC-SHA256 secret. Must be at least 32 bytes in prod.
            String secret,
            // Expected issuer claim. Optional; if null, no check.
            String issuer,
            // Tolerated clock skew in seconds.
            long allowedClockSkewSeconds
    ) {}

    public record RateLimit(
            int requestsPerMinute,
            int burst
    ) {}
}
