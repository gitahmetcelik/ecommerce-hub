-- Flyway Migration V1005__channel_push_failure_tracking.sql
-- Plan v5 §1.7 gate 3: a channel that keeps rejecting the same identifier (e.g. an
-- unknown barcode) must not retry forever. Before this, a per-item rejection sent the
-- row straight back to PENDING with no memory of the failure, and PushWindowScheduler
-- opens a fresh window on every tick as long as a PENDING row exists — the two
-- together retried the same broken push every 5 seconds, indefinitely, with nothing
-- ever surfacing it to a human.
--
-- consecutive_failures survives value changes on purpose (ChannelPushStore.upsert does
-- not touch it): the failure is about the identifier being unrecognised, not about
-- which quantity was being pushed, so a new stock value does not earn a fresh streak.
-- Only a channel-confirmed success resets it.

ALTER TABLE hub.channel_push
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
