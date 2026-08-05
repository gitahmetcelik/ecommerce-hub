package com.ecommercehub.connector;

/**
 * plan §8 Yetenek: a real capability matrix, not a uniform connector assumption —
 * merchant-of-record and API surface vary by channel. See §3's yetki matrisi and §7's
 * return-flow variants for what each missing capability means downstream.
 */
public enum Capability {
    FETCH_ORDERS,
    FETCH_CATALOG,
    STOCK_PUSH,
    PRICE_PUSH,

    /** Missing: the return decision is only ever kept in the hub, never sent to the channel. */
    RETURN_DECISION_SUBMIT,

    /** Missing: the channel generates its own shipping label; we only observe it. */
    SHIPMENT_CREATE,

    /** Missing: the channel refunds the customer; we only observe that it happened. */
    REFUND_BY_US,

    /** Missing: that channel runs polling-first — Faz 2's ingest path doesn't apply to it. */
    WEBHOOK,

    /** Missing: durumSorgula-based recovery is mandatory, a client idempotency key can't help. */
    REQUEST_IDEMPOTENCY_KEY
}
