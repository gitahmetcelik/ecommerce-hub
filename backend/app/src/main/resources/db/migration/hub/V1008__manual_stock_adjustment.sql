-- Plan v5 Faz 7 §7.2 point 3: a manual stock correction is still an ON_HAND_INCREASE/
-- ON_HAND_DECREASE for the ledger's own replay purposes (StockConsistencyService's
-- CASE only knows those two reasons — a new reason value would show up as a permanent
-- phantom discrepancy every night). What makes a manual correction distinct is WHY a
-- human did it and WHO did it, carried in these three new, nullable columns — nullable
-- because every other movement reason (order, return, shipment) leaves them null.
ALTER TABLE hub.stock_movement ADD COLUMN adjustment_reason TEXT;
ALTER TABLE hub.stock_movement ADD COLUMN note TEXT;
ALTER TABLE hub.stock_movement ADD COLUMN actor_user_id UUID;
