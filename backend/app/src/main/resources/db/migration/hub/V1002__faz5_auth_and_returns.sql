-- Flyway Migration V1002__faz5_auth_and_returns.sql
-- Phase 5: authentication (Plan §10) and the return flow (Plan §7).
--
-- V1000 already created app_user, user_role, return_request, return_item,
-- return_payment and shipment as bare skeletons. What was missing is everything a
-- login actually needs (account state, invitations, resets, refresh tokens) and the
-- columns the return state machine turns on.

-- =============================================================================
-- 1. USER ACCOUNT STATE
-- =============================================================================
ALTER TABLE hub.app_user
    -- INVITED accounts exist (so the invitation can reference them and the email is
    -- reserved) but cannot authenticate: password_hash holds a placeholder no password
    -- can produce until the invitation is accepted.
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN last_login_at TIMESTAMPTZ;

-- =============================================================================
-- 2. INVITATIONS, PASSWORD RESETS, REFRESH TOKENS
-- =============================================================================
-- Every one of these stores a HASH of the secret, never the secret itself. The token
-- is shown once, at creation, and is unrecoverable afterwards — a database dump must
-- not hand out live sessions and password resets.

CREATE TABLE hub.user_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    user_id UUID NOT NULL REFERENCES hub.app_user(id),
    email TEXT NOT NULL,
    role_name TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    invited_by_user_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.password_reset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    user_id UUID NOT NULL REFERENCES hub.app_user(id),
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- Access tokens are short-lived and stateless (no row here); the refresh token is the
-- thing with a lifetime worth revoking, which is what makes "log this session out"
-- possible at all. Rotated on every use: a refresh token presented twice is either a
-- replay or a stolen copy, and the reuse is detectable because the first use revoked it.
CREATE TABLE hub.refresh_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    user_id UUID NOT NULL REFERENCES hub.app_user(id),
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- Lookup is always "find the row for this presented token", so the hash is the index.
CREATE UNIQUE INDEX idx_user_invitation_token ON hub.user_invitation (token_hash);
CREATE UNIQUE INDEX idx_password_reset_token ON hub.password_reset (token_hash);
CREATE UNIQUE INDEX idx_refresh_token_token ON hub.refresh_token (token_hash);
CREATE INDEX idx_refresh_token_user ON hub.refresh_token (user_id) WHERE revoked_at IS NULL;

-- =============================================================================
-- 3. RETURN FLOW (Plan §7)
-- =============================================================================
ALTER TABLE hub.return_request
    ADD COLUMN channel_return_id TEXT,
    ADD COLUMN reason TEXT,
    -- Two separate deadlines, not one: 24h is a reminder, 48h is the timeout. Plan §0
    -- is explicit that the timeout escalates to a human and never auto-rejects — an
    -- automatic rejection is a customer-visible decision nobody made.
    ADD COLUMN reminder_at TIMESTAMPTZ,
    ADD COLUMN reminded_at TIMESTAMPTZ,
    ADD COLUMN rejection_reason TEXT,
    -- Plan §7: "return label: five retries, then the DLQ and the operator queue". The engine's
    -- retry produces the DLQ entry; this counter is what makes the operator queue half
    -- happen, and it has to be persisted because each retry is a separate task attempt
    -- in a separate transaction with no memory of the previous one.
    ADD COLUMN shipment_attempts INT NOT NULL DEFAULT 0;

-- A return arriving twice from the channel must not become two returns.
CREATE UNIQUE INDEX idx_return_request_channel
    ON hub.return_request (organization_id, sales_order_id, channel_return_id)
    WHERE channel_return_id IS NOT NULL;

CREATE INDEX idx_return_request_open
    ON hub.return_request (status, timeout_at)
    WHERE status = 'AWAITING_APPROVAL';

ALTER TABLE hub.return_item
    -- Disposition is per item, not per return: one parcel can come back with one
    -- sellable unit and one broken one, and a single flag on the return would force
    -- the whole parcel into whichever answer was wrong for the other half.
    ADD COLUMN intact_quantity INT,
    ADD COLUMN damaged_quantity INT;

ALTER TABLE hub.return_payment
    ADD COLUMN channel_refund_id TEXT,
    ADD COLUMN paid_at TIMESTAMPTZ;

ALTER TABLE hub.shipment
    ADD COLUMN channel_shipment_id TEXT,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'CREATED';

-- =============================================================================
-- 4. RLS FOR THE NEW TABLES (Plan §3 a-d)
-- =============================================================================
-- The Phase 0a gate test reads the table list from pg_class rather than a hardcoded
-- list, so a table added without these three statements fails that test instead of
-- quietly shipping unprotected. This block is what keeps that test green.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_invitation', 'password_reset', 'refresh_token'] LOOP
        EXECUTE format('ALTER TABLE hub.%I ENABLE ROW LEVEL SECURITY;', t);
        EXECUTE format('ALTER TABLE hub.%I FORCE  ROW LEVEL SECURITY;', t);
        EXECUTE format('DROP POLICY IF EXISTS org_isolation ON hub.%I;', t);
        EXECUTE format(
            'CREATE POLICY org_isolation ON hub.%I '
            'USING (organization_id = current_setting(''hub.org_id'')::uuid) '
            'WITH CHECK (organization_id = current_setting(''hub.org_id'')::uuid);', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON hub.user_invitation, hub.password_reset, hub.refresh_token
    TO hub_app, hub_system;
