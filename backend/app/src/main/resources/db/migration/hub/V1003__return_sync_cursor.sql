-- Flyway Migration V1003__return_sync_cursor.sql
-- Plan §11 row 2 ("hourly: the last 24 hours of orders and returns"): the hourly return delta pass
-- needs its own high-water mark. Sharing last_order_sync_at would make the two passes
-- fight over one cursor — whichever ran last would move it, and the other would skip
-- the window it had not covered yet.

ALTER TABLE hub.channel_connection
    ADD COLUMN last_return_sync_at TIMESTAMPTZ;
