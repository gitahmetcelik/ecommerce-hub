package com.ecommercehub.domain.auth;

/** Any token that cannot be trusted: bad signature, expired, malformed, revoked, or already used. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
