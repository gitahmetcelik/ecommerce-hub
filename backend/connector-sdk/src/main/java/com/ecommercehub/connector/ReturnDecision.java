package com.ecommercehub.connector;

public record ReturnDecision(String channelReturnId, Decision decision) {
    public enum Decision { ACCEPT, REJECT }
}
