package com.ecommercehub.connector;

public record SignatureVerification(boolean valid, String reason) {
    public static SignatureVerification ok() {
        return new SignatureVerification(true, null);
    }

    public static SignatureVerification invalid(String reason) {
        return new SignatureVerification(false, reason);
    }
}
