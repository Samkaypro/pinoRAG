package io.pinoRAG.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class JwtTokenVerifier {

    private final SecretKey signingKey;
    private final String expectedIssuer;
    private final long allowedClockSkewSeconds;

    public JwtTokenVerifier(AuthProperties props) {
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "pinorag.auth.jwt.secret must be at least 32 bytes for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
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

            if (expectedIssuer != null && !expectedIssuer.equals(claims.getIssuer())) {
                return Optional.empty();
            }

            Object rawTenant = claims.get("tid");
            if (rawTenant == null) {
                return Optional.empty();
            }
            Long tenantId = switch (rawTenant) {
                case Number n -> n.longValue();
                case String s -> {
                    try { yield Long.parseLong(s); }
                    catch (NumberFormatException e) { yield null; }
                }
                default -> null;
            };
            if (tenantId == null || tenantId <= 0) {
                return Optional.empty();
            }

            Object scopesObj = claims.get("scopes");
            String[] scopes;
            if (scopesObj instanceof java.util.Collection<?> c) {
                scopes = c.stream().map(Object::toString).toArray(String[]::new);
            } else if (scopesObj instanceof String s && !s.isBlank()) {
                scopes = s.split("\\s+");
            } else {
                scopes = new String[0];
            }

            return Optional.of(new VerifiedJwt(tenantId, claims.getSubject(), scopes));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record VerifiedJwt(Long tenantId, String subject, String[] scopes) {}
}
