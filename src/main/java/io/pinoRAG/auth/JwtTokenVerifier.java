package io.pinoRAG.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

@Component
public class JwtTokenVerifier {

    static final String INSECURE_DEFAULT_SECRET =
            "change-me-change-me-change-me-change-me-change-me-32b";

    private final SecretKey signingKey;
    private final String expectedIssuer;
    private final long allowedClockSkewSeconds;

    public JwtTokenVerifier(AuthProperties props, Environment env) {
        String secret = props.jwt().secret();
        guardAgainstInsecureSecret(secret, env);
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "pinorag.auth.jwt.secret must be at least 32 bytes for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expectedIssuer = props.jwt().issuer();
        this.allowedClockSkewSeconds = props.jwt().allowedClockSkewSeconds();
    }

    public Optional<VerifiedJwt> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clockSkewSeconds(allowedClockSkewSeconds)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Require an explicit expiration. jjwt validates the timestamp
            // when present but does not require its presence, and a token
            // that never expires is a worse outcome than no JWT support.
            if (claims.getExpiration() == null) {
                return Optional.empty();
            }
            if (expectedIssuer != null && !expectedIssuer.isBlank()
                    && !expectedIssuer.equals(claims.getIssuer())) {
                return Optional.empty();
            }

            Long tenantId = readTenantId(claims.get("tid"));
            if (tenantId == null) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedJwt(tenantId, claims.getSubject(), readScopes(claims)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Long readTenantId(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) {
            long v = n.longValue();
            return v > 0 ? v : null;
        }
        if (raw instanceof String s) {
            try { long v = Long.parseLong(s); return v > 0 ? v : null; }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static String[] readScopes(Claims claims) {
        Object scopesObj = claims.get("scopes");
        if (scopesObj instanceof java.util.Collection<?> c) {
            return c.stream().map(Object::toString).toArray(String[]::new);
        }
        if (scopesObj instanceof String s && !s.isBlank()) {
            return s.split("\\s+");
        }
        return new String[0];
    }

    private static void guardAgainstInsecureSecret(String secret, Environment env) {
        if (!INSECURE_DEFAULT_SECRET.equals(secret)) {
            return;
        }
        Set<String> active = Set.of(env.getActiveProfiles());
        boolean inProd = active.contains("prod") || active.contains("production");
        if (inProd) {
            throw new IllegalStateException(
                    "pinorag.auth.jwt.secret is set to the insecure default. " +
                            "Set PINORAG_JWT_SECRET to a strong random value (32+ bytes).");
        }
        // Non-prod profiles get a warning via the framework logs at the
        // call site so dev experience is not blocked.
    }

    public record VerifiedJwt(Long tenantId, String subject, String[] scopes) {}
}
