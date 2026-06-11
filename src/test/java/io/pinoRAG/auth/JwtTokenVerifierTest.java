package io.pinoRAG.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenVerifierTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32b";
    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new JwtTokenVerifier(props(SECRET, ""), new MockEnvironment());
    }

    @Test
    void happyPath() {
        String token = Jwts.builder()
                .subject("alice")
                .claim("tid", 42L)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        Optional<JwtTokenVerifier.VerifiedJwt> r = verifier.verify(token);
        assertThat(r).isPresent();
        assertThat(r.get().tenantId()).isEqualTo(42L);
        assertThat(r.get().subject()).isEqualTo("alice");
    }

    @Test
    void rejectsTokenWithoutExpClaim() {
        String token = Jwts.builder()
                .subject("alice").claim("tid", 1L)
                .issuedAt(Date.from(Instant.now()))
                .signWith(KEY).compact();
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        String token = Jwts.builder()
                .subject("alice").claim("tid", 1L)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(10))))
                .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void rejectsMissingTid() {
        String token = Jwts.builder()
                .subject("alice")
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void rejectsTidOfZeroOrNegative() {
        String token = Jwts.builder()
                .subject("alice").claim("tid", 0L)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(token)).isEmpty();

        String token2 = Jwts.builder()
                .subject("alice").claim("tid", -7L)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(token2)).isEmpty();
    }

    @Test
    void rejectsWrongSignature() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-another-secret-other-32b".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("alice").claim("tid", 1L)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(otherKey).compact();
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void rejectsWrongIssuerWhenConfigured() {
        JwtTokenVerifier strict = new JwtTokenVerifier(props(SECRET, "pinorag"), new MockEnvironment());
        String token = Jwts.builder()
                .subject("alice").claim("tid", 1L).issuer("attacker")
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(strict.verify(token)).isEmpty();
    }

    @Test
    void rejectsGarbageToken() {
        assertThat(verifier.verify("not.a.real.jwt")).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
    }

    @Test
    void rejectsInsecureDefaultSecretOnProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() ->
                new JwtTokenVerifier(props(JwtTokenVerifier.INSECURE_DEFAULT_SECRET, ""), env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insecure default");
    }

    @Test
    void scopesParsedFromArrayOrSpaceDelimited() {
        String tokenList = Jwts.builder()
                .subject("alice").claim("tid", 1L).claim("scopes", List.of("read", "write"))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(tokenList).orElseThrow().scopes())
                .containsExactlyInAnyOrder("read", "write");

        String tokenString = Jwts.builder()
                .subject("alice").claim("tid", 1L).claim("scopes", "read write admin")
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY).compact();
        assertThat(verifier.verify(tokenString).orElseThrow().scopes())
                .containsExactlyInAnyOrder("read", "write", "admin");
    }

    private static AuthProperties props(String secret, String issuer) {
        return new AuthProperties(new AuthProperties.Jwt(secret, issuer, 30));
    }
}
