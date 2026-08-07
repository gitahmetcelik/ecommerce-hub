-- Flyway Migration V1006__channel_rate_budget.sql
-- Plan v5 Faz 5 §5.3: RateLimitBudget was an in-memory ConcurrentHashMap per JVM
-- instance. The moment a second worker process exists (this migration's whole reason
-- to exist), each instance gets its OWN full budget for the same channel connection —
-- the 50/30/20 split Plan §9 relies on silently doubles, and the anti-oversell
-- interactive-class guarantee it exists to protect is gone. This table is the shared
-- state that fixes that: one row per (channel_connection_id, budget_class), token
-- acquisition done with FOR UPDATE SKIP LOCKED so concurrent workers never double-spend
-- the same token (same pattern as hub.channel_push's CLAIM_SQL and the dispatcher's
-- work_batch selection).

CREATE TABLE hub.channel_rate_budget (
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    budget_class TEXT NOT NULL,        -- INTERACTIVE / OPERATIONAL / BACKGROUND
    tokens INTEGER NOT NULL,
    refilled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    backoff_until TIMESTAMPTZ,
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    PRIMARY KEY (channel_connection_id, budget_class)
);

-- V1000's RLS loop only covers tables that existed at that migration — every table
-- added since (V1002, V1005, this one) has to enable it explicitly or it is silently
-- unprotected. RlsIsolationGateTests reads pg_class directly for exactly this reason:
-- forgetting this block is a red test, not a code review catch.
ALTER TABLE hub.channel_rate_budget ENABLE ROW LEVEL SECURITY;
ALTER TABLE hub.channel_rate_budget FORCE ROW LEVEL SECURITY;

CREATE POLICY org_isolation ON hub.channel_rate_budget
    USING (organization_id = current_setting('hub.org_id')::uuid)
    WITH CHECK (organization_id = current_setting('hub.org_id')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON hub.channel_rate_budget
    TO hub_app, hub_system;
