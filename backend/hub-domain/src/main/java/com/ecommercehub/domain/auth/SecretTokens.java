package com.ecommercehub.domain.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes the bearer secrets that are not JWTs: refresh tokens,
 * invitation tokens and password-reset tokens.
 *
 * <p><b>Only the hash is ever stored.</b> The plaintext is returned once, at creation,
 * and cannot be recovered afterwards — so a leaked database backup yields no live
 * sessions and no usable password resets.
 *
 * <p>SHA-256, not BCrypt, and that difference is deliberate. BCrypt is slow by design
 * to make guessing a low-entropy human password expensive. These tokens are 256 bits
 * from a CSPRNG; there is nothing to guess, so the cost would buy no security and
 * would instead be paid on every single API call that presents a refresh token.
 * Passwords go through {@code PasswordEncoder} (BCrypt); these do not.
 */
public final class SecretTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private SecretTokens() {
    }

    /** A fresh URL-safe token. Show it to the user once; store {@link #hash(String)} of it. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present on a supported JRE", e);
        }
    }
}
