-- Flyway Migration V1000__init_hub_schema.sql
-- E-commerce Hub Platform — canonical schema and Postgres RLS isolation

CREATE SCHEMA IF NOT EXISTS hub;

-- Role creation (defensive, idempotent)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hub_owner') THEN
        CREATE ROLE hub_owner;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hub_app') THEN
        CREATE ROLE hub_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS LOGIN PASSWORD 'hub_app_pass';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hub_system') THEN
        CREATE ROLE hub_system NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS LOGIN PASSWORD 'hub_system_pass';
    END IF;
END $$;

-- Schema privileges
GRANT ALL ON SCHEMA hub TO hub_owner;
GRANT USAGE ON SCHEMA hub TO hub_app, hub_system;

-- =============================================================================
-- 1. ORGANIZATION
-- =============================================================================
CREATE TABLE hub.organization (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    task_quota INT NOT NULL DEFAULT 20,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 2. USER & ROLE
-- =============================================================================
CREATE TABLE hub.app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    full_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_app_user_email UNIQUE (organization_id, email)
);

CREATE TABLE hub.user_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    user_id UUID NOT NULL REFERENCES hub.app_user(id),
    role_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 3. CHANNEL CONNECTION
-- =============================================================================
CREATE TABLE hub.channel_connection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_type TEXT NOT NULL,
    encrypted_credentials TEXT NOT NULL,
    key_version SMALLINT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    reconcile_interval_minutes INT NOT NULL DEFAULT 5,
    next_reconcile_at TIMESTAMPTZ,
    backfill_status JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 4. CATALOG (PRODUCT, VARIANT, OPTION, MAPPING, CANDIDATE)
-- =============================================================================
CREATE TABLE hub.product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    title TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.variant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    product_id UUID NOT NULL REFERENCES hub.product(id),
    sku TEXT NOT NULL,
    barcode TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_variant_sku UNIQUE (organization_id, sku)
);

CREATE TABLE hub.variant_option (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.channel_product_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    channel_product_id TEXT NOT NULL,
    channel_variant_id TEXT NOT NULL,
    mapping_source TEXT NOT NULL,
    matched_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_channel_mapping UNIQUE (organization_id, channel_connection_id, channel_variant_id)
);

CREATE TABLE hub.mapping_candidate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    channel_product_id TEXT NOT NULL,
    channel_variant_id TEXT NOT NULL,
    barcode TEXT,
    title TEXT,
    candidate_variant_ids JSONB,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 5. PRICE
-- =============================================================================
CREATE TABLE hub.price (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    list_price NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    vat_rate NUMERIC(5,2) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.channel_price (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    price NUMERIC(19,4) NOT NULL,
    discounted_price NUMERIC(19,4),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_channel_price UNIQUE (organization_id, channel_connection_id, variant_id)
);

-- =============================================================================
-- 6. STOCK (LEDGER, BUFFER, RESERVATION, DISCREPANCY, OVERSELL)
-- =============================================================================
CREATE TABLE hub.stock (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    on_hand INT NOT NULL DEFAULT 0,
    reserved INT NOT NULL DEFAULT 0,
    damaged INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_variant UNIQUE (organization_id, variant_id)
);

CREATE TABLE hub.stock_buffer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    buffer INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_buffer UNIQUE (organization_id, channel_connection_id, variant_id)
);

CREATE TABLE hub.stock_reservation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    order_item_id UUID NOT NULL,
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    quantity INT NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.stock_movement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    quantity INT NOT NULL,
    reason TEXT NOT NULL,
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.stock_discrepancy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID,
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    type TEXT NOT NULL,
    expected INT NOT NULL,
    actual INT NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.oversell_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    requested INT NOT NULL,
    available INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 7. SALES ORDER & CUSTOMER
-- =============================================================================
CREATE TABLE hub.customer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    email TEXT,
    phone TEXT,
    address TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.sales_order (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    channel_order_number TEXT NOT NULL,
    customer_id UUID REFERENCES hub.customer(id),
    channel_event_at TIMESTAMPTZ NOT NULL,
    channel_event_sequence BIGINT,
    derived_status TEXT NOT NULL DEFAULT 'CREATED',
    total NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sales_order_channel UNIQUE (organization_id, channel_connection_id, channel_order_number)
);

CREATE TABLE hub.order_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    sales_order_id UUID NOT NULL REFERENCES hub.sales_order(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    quantity INT NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    vat_rate NUMERIC(5,2) NOT NULL,
    status TEXT NOT NULL DEFAULT 'CREATED',
    channel_commission NUMERIC(19,4),
    channel_deductions JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 8. RETURN, RETURN PAYMENT & SHIPMENT
-- =============================================================================
CREATE TABLE hub.return_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    sales_order_id UUID NOT NULL REFERENCES hub.sales_order(id),
    status TEXT NOT NULL DEFAULT 'REQUESTED',
    approved_by_user_id UUID,
    approved_at TIMESTAMPTZ,
    timeout_at TIMESTAMPTZ,
    is_intact BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.return_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    return_request_id UUID NOT NULL REFERENCES hub.return_request(id),
    order_item_id UUID NOT NULL REFERENCES hub.order_item(id),
    quantity INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.return_payment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    return_request_id UUID NOT NULL REFERENCES hub.return_request(id),
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    approved_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.shipment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    sales_order_id UUID REFERENCES hub.sales_order(id),
    return_request_id UUID REFERENCES hub.return_request(id),
    tracking_number TEXT,
    direction TEXT NOT NULL,
    source TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- 9. OPERATIONAL QUEUES (OPERATOR, WORK BATCH, INTENT, PUSH)
-- =============================================================================
CREATE TABLE hub.operator_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    type TEXT NOT NULL,
    description TEXT NOT NULL,
    reference_id UUID,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.work_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID,
    task_type TEXT NOT NULL,
    task_key TEXT NOT NULL,
    payload JSONB,
    task_id UUID,
    status TEXT NOT NULL DEFAULT 'PENDING',
    last_attempt_at TIMESTAMPTZ,
    trace_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE hub.channel_call_intent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    type TEXT NOT NULL,
    target_reference UUID NOT NULL,
    request_summary JSONB,
    status TEXT NOT NULL DEFAULT 'PREPARED',
    channel_idempotency_key TEXT,
    channel_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_channel_call_intent UNIQUE (organization_id, type, target_reference)
);

CREATE TABLE hub.channel_push (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    variant_id UUID NOT NULL REFERENCES hub.variant(id),
    type TEXT NOT NULL,
    target_value JSONB NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'PENDING',
    last_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_channel_push UNIQUE (channel_connection_id, variant_id, type)
);

-- =============================================================================
-- 10. EVENT & AUDIT
-- =============================================================================
-- Monthly-partitioned (Plan §3): 90-day retention is a partition DROP, not a
-- row-by-row DELETE — the whole reason to partition an append-only audit trail.
-- A unique/primary key on a partitioned table must include the partition key
-- column (received_at) — a Postgres requirement, not a design choice; duplicate
-- webhook detection (Plan §4.1) is therefore exact within a partition and only
-- approximate across a month boundary, an accepted tradeoff for redelivered
-- webhooks which arrive within seconds/minutes, never months apart.
CREATE TABLE hub.raw_event (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    channel_connection_id UUID NOT NULL REFERENCES hub.channel_connection(id),
    channel_event_id TEXT NOT NULL,
    raw_body TEXT NOT NULL,
    signature TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    trace_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id, received_at),
    CONSTRAINT uk_raw_event_channel_event UNIQUE (organization_id, channel_connection_id, channel_event_id, received_at)
) PARTITION BY RANGE (received_at);

-- Catches any row outside an explicitly created monthly partition (clock skew,
-- backfill, or simply a month nobody has provisioned yet). RawEventPartitionMaintenanceService
-- creates real monthly partitions ahead of time and drops ones past the retention window.
CREATE TABLE hub.raw_event_default PARTITION OF hub.raw_event DEFAULT;

CREATE TABLE hub.audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES hub.organization(id),
    user_id UUID,
    action TEXT NOT NULL,
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- DML PRIVILEGES
-- =============================================================================
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA hub TO hub_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA hub TO hub_system;
ALTER DEFAULT PRIVILEGES IN SCHEMA hub GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hub_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA hub GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hub_system;

-- =============================================================================
-- POSTGRES ROW LEVEL SECURITY (RLS) POLICIES (Plan §3 a-d)
-- =============================================================================

DO $$
DECLARE
    r RECORD;
    tenant_column TEXT;
BEGIN
    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'hub') LOOP
        EXECUTE format('ALTER TABLE hub.%I ENABLE ROW LEVEL SECURITY;', r.tablename);
        EXECUTE format('ALTER TABLE hub.%I FORCE ROW LEVEL SECURITY;', r.tablename);

        -- Drop first for idempotent re-run of this migration.
        EXECUTE format('DROP POLICY IF EXISTS org_isolation ON hub.%I;', r.tablename);

        -- hub.organization IS the tenant root: its own id is the tenant boundary,
        -- it has no organization_id column referencing itself. Every other table
        -- carries organization_id.
        IF r.tablename = 'organization' THEN
            tenant_column := 'id';
        ELSE
            tenant_column := 'organization_id';
        END IF;

        -- current_setting is called WITHOUT the missing_ok argument — raises an
        -- ERROR (not empty result) when the session variable was never set.
        EXECUTE format('CREATE POLICY org_isolation ON hub.%I USING (%I = current_setting(''hub.org_id'')::uuid) WITH CHECK (%I = current_setting(''hub.org_id'')::uuid);', r.tablename, tenant_column, tenant_column);
    END LOOP;
END $$;
