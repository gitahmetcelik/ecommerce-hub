package com.ecommercehub.domain.intent;

public class DuplicateIntentException extends RuntimeException {
    public DuplicateIntentException(String message, Throwable cause) {
        super(message, cause);
    }
}
