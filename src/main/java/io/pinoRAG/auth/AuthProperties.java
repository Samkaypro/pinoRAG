package io.pinoRAG.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pinorag.auth")
public record AuthProperties(
        Jwt jwt
) {

    public record Jwt(
            // HMAC-SHA256 secret. Must be at least 32 bytes in prod.
            String secret,
            // Expected issuer claim. Optional; if blank, no check.
            String issuer,
            // Tolerated clock skew in seconds.
            long allowedClockSkewSeconds
    ) {}
}
