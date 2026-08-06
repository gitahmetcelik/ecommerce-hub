package com.ecommercehub.connector;

import java.math.BigDecimal;

/**
 * @param amount   plan §3's money rule: NUMERIC-backed BigDecimal with the currency
 *                 alongside it. Never a double — a refund off by a rounding error is a
 *                 customer complaint, and floating point cannot represent 0.10 exactly.
 * @param currency ISO 4217, three letters.
 */
public record RefundRequest(String channelOrderId, String channelReturnId, BigDecimal amount, String currency) {
}
