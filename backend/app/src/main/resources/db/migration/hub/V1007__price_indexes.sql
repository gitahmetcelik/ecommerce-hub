-- Plan v5 Faz 6 §6.2: hub.price and hub.channel_price already exist (V1000) with RLS
-- already applied; the only thing missing is what makes "the" list price for a variant
-- well-defined. Without this, two concurrent setListPrice calls for a variant that has
-- never had one could each insert their own row instead of one updating the other.
ALTER TABLE hub.price ADD CONSTRAINT uk_price_org_variant UNIQUE (organization_id, variant_id);
