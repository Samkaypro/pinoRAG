package io.pinoRAG.auth;

import java.security.SecureRandom;
import java.util.Base64;

// Wire format: prk_<prefix>.<secret>
// prefix: 12 base64url chars, looked up cheaply in pino_api_keys.prefix.
// secret: 32 base64url chars, hashed with SHA-256.
public record ApiKeyMaterial(String prefix, String secret, String token) {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    public static final String TOKEN_PREFIX = "prk_";

    public static ApiKeyMaterial generate() {
        String prefix = randomBase64Url(9);
        String secret = randomBase64Url(24);
        String token = TOKEN_PREFIX + prefix + "." + secret;
        return new ApiKeyMaterial(prefix, secret, token);
    }

    public static ApiKeyMaterial parse(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String body = token.substring(TOKEN_PREFIX.length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1) {
            return null;
        }
        return new ApiKeyMaterial(body.substring(0, dot), body.substring(dot + 1), token);
    }

    private static String randomBase64Url(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return ENC.encodeToString(buf);
    }
}
