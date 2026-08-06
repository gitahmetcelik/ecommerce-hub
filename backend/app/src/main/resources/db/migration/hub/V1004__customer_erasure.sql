-- Flyway Migration V1004__customer_erasure.sql
-- Plan §12 Phase 7 + §14: a single customer's erasure request.
--
-- Partitioning solves BULK expiry — Plan §3 drops raw_event partitions past 90 days.
-- It does not solve a single person asking to be erased today, which is a different
-- problem with a different answer: find their data wherever it sits and redact it.

ALTER TABLE hub.customer
    -- Set when the erasure ran. The row itself survives so orders still join to
    -- something; what leaves is the identifying content, not the reference. Deleting
    -- the row would orphan every order the person ever placed, which is data loss
    -- dressed up as privacy.
    ADD COLUMN erased_at TIMESTAMPTZ;

CREATE INDEX idx_customer_erased ON hub.customer (organization_id) WHERE erased_at IS NULL;
