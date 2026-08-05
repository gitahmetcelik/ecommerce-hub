package com.ecommercehub.app.backfill;

import java.time.Instant;

/**
 * plan Faz 3: "sayfa imleci kalıcı, yarıda kesilince kaldığı yerden devam eder."
 * Serialized as-is into channel_connection.backfill_status (JSONB) after every
 * single page — a crash between two pages loses at most the in-flight page, and
 * even that is safe to refetch since catalog import and order processing are both
 * idempotent (mapping/matching and target-status idempotency respectively).
 */
public record BackfillCursor(int catalogPage, boolean catalogDone, int orderPage, Instant orderSince, boolean ordersDone) {

    public static BackfillCursor initial() {
        return new BackfillCursor(1, false, 1, Instant.EPOCH, false);
    }

    public boolean isComplete() {
        return catalogDone && ordersDone;
    }

    public BackfillCursor withNextCatalogPage() {
        return new BackfillCursor(catalogPage + 1, false, orderPage, orderSince, ordersDone);
    }

    public BackfillCursor withCatalogDone() {
        return new BackfillCursor(catalogPage, true, orderPage, orderSince, ordersDone);
    }

    public BackfillCursor withNextOrderPage() {
        return new BackfillCursor(catalogPage, catalogDone, orderPage + 1, orderSince, ordersDone);
    }

    public BackfillCursor withOrdersDone(Instant completedAt) {
        return new BackfillCursor(catalogPage, catalogDone, orderPage, completedAt, true);
    }
}
