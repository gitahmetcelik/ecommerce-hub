-- Flyway Migration V1001__faz4_push_and_reconcile.sql
-- Phase 4: push coalescing, per-channel buffer + last-unit allocation, oversell
-- detection, reconcile layers and the channel circuit breaker.
--
-- Every table Phase 4 needs (channel_push, stock_buffer, stock_discrepancy,
-- oversell_event) already exists from V1000 — what was missing is the handful of
-- columns the policies in Plan §3/§11 are actually parameterised by, plus the
-- indexes the new hot paths query on.

-- Plan §3 "the last-unit policy": below this many sellable units the variant
-- switches from "show the same last unit to every channel" to allocating it to a
-- single channel. Default 1 = allocate only when literally one unit remains.
ALTER TABLE hub.organization
    ADD COLUMN low_stock_threshold INT NOT NULL DEFAULT 1;

ALTER TABLE hub.channel_connection
    -- Which channel wins the last unit when allocation kicks in. Higher wins;
    -- ties break on id so the choice is deterministic and reproducible rather
    -- than "whichever row the planner returned first".
    ADD COLUMN allocation_priority INT NOT NULL DEFAULT 0,
    -- Plan Phase 4 "Kanal devre kesici": consecutive failed connector calls. Reset
    -- to 0 by any success — this counts a *streak*, not a lifetime total.
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN circuit_open_until TIMESTAMPTZ,
    ADD COLUMN last_failure_reason TEXT,
    -- High-water mark for the delta order reconcile. Plan §8 sends "since" as
    -- (this − 5 min) on purpose: channel clocks and our own are not the same clock,
    -- and re-fetching a few minutes of overlap costs one page while missing an order
    -- costs an unshipped sale. The duplicates the overlap produces are absorbed by
    -- the event-layer idempotency in §4.1.
    ADD COLUMN last_order_sync_at TIMESTAMPTZ;

-- The push sender's per-window query: "PENDING rows for this connection".
CREATE INDEX idx_channel_push_connection_status
    ON hub.channel_push (channel_connection_id, status);

-- The ledger consistency check (Plan §11) replays movements per variant.
CREATE INDEX idx_stock_movement_org_variant
    ON hub.stock_movement (organization_id, variant_id);

-- The nightly drift report and the internal-consistency check both read "open
-- discrepancies for this org" — resolved rows accumulate and should not be scanned.
CREATE INDEX idx_stock_discrepancy_open
    ON hub.stock_discrepancy (organization_id, resolved);

-- Mapping lookup during push enqueue: "which channels sell this variant".
CREATE INDEX idx_channel_product_mapping_variant
    ON hub.channel_product_mapping (organization_id, variant_id);

-- The reconcile sweeper (Plan §1.5): one cron finds connections whose time has come.
CREATE INDEX idx_channel_connection_next_reconcile
    ON hub.channel_connection (next_reconcile_at)
    WHERE next_reconcile_at IS NOT NULL;
