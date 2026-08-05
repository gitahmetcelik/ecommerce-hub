package com.ecommercehub.connector;

public record CredentialStatus(boolean valid, String reason) {
    public static CredentialStatus ok() {
        return new CredentialStatus(true, null);
    }

    public static CredentialStatus invalid(String reason) {
        return new CredentialStatus(false, reason);
    }
}
