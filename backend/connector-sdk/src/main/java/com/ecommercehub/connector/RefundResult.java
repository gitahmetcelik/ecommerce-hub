package com.ecommercehub.connector;

/** The channel's own identifier for the refund it just made — what a later reconcile matches against. */
public record RefundResult(String channelRefundId) {
}
