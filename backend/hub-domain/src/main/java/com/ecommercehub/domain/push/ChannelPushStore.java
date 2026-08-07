package com.ecommercehub.domain.push;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Plan §3 "Giden push": the coalescing table's only writer.
 *
 * <p>Every operation here is a single statement on purpose. The upsert is the
 * coalescing itself (a new value updates the one row for (connection, variant, type)
 * rather than appending a second one — 50 changes to one variant leave 50 generations
 * on <em>one</em> row), and each close is a compare-and-set on the generation the
 * sender read. There is no read-modify-write anywhere in this class, so two
 * concurrent windows and an enqueue racing them cannot interleave into a lost update.
 */
@Component
public class ChannelPushStore {

    /**
     * Plan §3: "a new value updates the row (upsert) rather than appending another".
     *
     * <p>The DO UPDATE is skipped when the target value is unchanged. Without that
     * guard, a stock movement that leaves a channel's sellable quantity identical
     * (a buffer already clamped it to 0, say) would still bump the generation — which
     * both invalidates an in-flight send's CAS for no reason and re-queues a push
     * that would tell the channel exactly what it already knows.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO hub.channel_push
                (id, organization_id, channel_connection_id, variant_id, type, target_value, generation, status)
            VALUES (gen_random_uuid(), :org, :conn, :variant, :type, CAST(:value AS jsonb), 1, 'PENDING')
            ON CONFLICT (channel_connection_id, variant_id, type) DO UPDATE
            SET target_value = EXCLUDED.target_value,
                generation   = hub.channel_push.generation + 1,
                status       = 'PENDING',
                updated_at   = now(),
                version      = hub.channel_push.version + 1
            WHERE hub.channel_push.target_value IS DISTINCT FROM EXCLUDED.target_value
            """;

    private static final String CLAIM_SQL = """
            UPDATE hub.channel_push
            SET status = 'SENDING', last_attempt_at = now(), updated_at = now(), version = version + 1
            WHERE id IN (
                SELECT id FROM hub.channel_push
                WHERE channel_connection_id = :conn AND type = :type AND status = 'PENDING'
                ORDER BY updated_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, organization_id, channel_connection_id, variant_id, type,
                      CAST(target_value AS text) AS target_value, generation
            """;

    /**
     * Plan §3's closing condition, verbatim: {@code WHERE id = ? AND nesil = :okunanNesil}.
     * Zero rows affected means a new value landed while this send was in flight — the row
     * is already back at PENDING carrying the newer value, and reporting success here
     * would strand the channel on the old one forever.
     */
    private static final String CLOSE_SQL = """
            UPDATE hub.channel_push
            SET status = :status, updated_at = now(), version = version + 1
            WHERE id = :id AND generation = :generation
            """;

    /**
     * Plan v5 §1.7 gate 3: a per-item rejection counts against the row rather than
     * just bouncing back to PENDING forever. Crossing {@code threshold} moves the row
     * to STUCK instead of PENDING, which is what actually breaks the retry loop —
     * PushWindowScheduler and CLAIM_SQL both only look at PENDING rows.
     */
    private static final String RECORD_FAILURE_SQL = """
            UPDATE hub.channel_push
            SET consecutive_failures = consecutive_failures + 1,
                status = CASE WHEN consecutive_failures + 1 >= :threshold THEN 'STUCK' ELSE 'PENDING' END,
                updated_at = now(), version = version + 1
            WHERE id = :id AND generation = :generation
            RETURNING consecutive_failures, status
            """;

    /**
     * Idempotent on purpose: once a row is STUCK, an enqueue can put it back to PENDING
     * and a retried attempt can fail again immediately (the failure count is already
     * past the threshold), which would otherwise raise a fresh operator_queue row every
     * single window instead of once.
     */
    private static final String RAISE_STUCK_ALERT_SQL = """
            INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
            SELECT gen_random_uuid(), :org, 'CHANNEL_PUSH_STUCK', :description, :pushId
            WHERE NOT EXISTS (
                SELECT 1 FROM hub.operator_queue
                WHERE type = 'CHANNEL_PUSH_STUCK' AND reference_id = :pushId AND status = 'PENDING'
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ChannelPushStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(UUID organizationId, UUID channelConnectionId, UUID variantId, String type, String targetValueJson) {
        jdbcTemplate.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("conn", channelConnectionId)
                .addValue("variant", variantId)
                .addValue("type", type)
                .addValue("value", targetValueJson));
    }

    /** Marks up to {@code limit} pending rows SENDING and returns them with the generation to close against. */
    public List<ChannelPushRow> claimPending(UUID channelConnectionId, String type, int limit) {
        return jdbcTemplate.query(CLAIM_SQL, new MapSqlParameterSource()
                        .addValue("conn", channelConnectionId)
                        .addValue("type", type)
                        .addValue("limit", limit),
                (rs, rowNum) -> new ChannelPushRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("organization_id"),
                        (UUID) rs.getObject("channel_connection_id"),
                        (UUID) rs.getObject("variant_id"),
                        rs.getString("type"),
                        rs.getString("target_value"),
                        rs.getLong("generation")));
    }

    /** @return true when the CAS held, false when the value changed mid-flight and the row must stay PENDING. */
    public boolean closeAsSent(UUID pushId, long generation) {
        return close(pushId, generation, ChannelPushStatus.SENT);
    }

    /** Failure path: hand the row back to the next window, unless a newer value already did that for us. */
    public boolean releaseToPending(UUID pushId, long generation) {
        return close(pushId, generation, ChannelPushStatus.PENDING);
    }

    /**
     * The channel rejected this specific item (not a whole-call failure). Bumps the
     * failure streak and, past {@code threshold}, moves the row to STUCK instead of
     * PENDING so it stops being retried automatically.
     *
     * @return the outcome, or {@link FailureOutcome#NOT_APPLIED} if the generation had
     *         already moved on (a newer value superseded this attempt)
     */
    public FailureOutcome recordFailure(UUID pushId, long generation, int threshold) {
        List<FailureOutcome> rows = jdbcTemplate.query(RECORD_FAILURE_SQL, new MapSqlParameterSource()
                        .addValue("id", pushId)
                        .addValue("generation", generation)
                        .addValue("threshold", threshold),
                (rs, rowNum) -> new FailureOutcome(true, rs.getInt("consecutive_failures"),
                        ChannelPushStatus.STUCK.equals(rs.getString("status"))));
        return rows.isEmpty() ? FailureOutcome.NOT_APPLIED : rows.get(0);
    }

    /** Idempotent: safe to call every time a row crosses into STUCK, even repeatedly. */
    public void raiseStuckAlert(UUID organizationId, UUID pushId, String description) {
        jdbcTemplate.update(RAISE_STUCK_ALERT_SQL, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("pushId", pushId)
                .addValue("description", description));
    }

    public record FailureOutcome(boolean applied, int consecutiveFailures, boolean stuck) {
        public static final FailureOutcome NOT_APPLIED = new FailureOutcome(false, 0, false);
    }

    private boolean close(UUID pushId, long generation, String status) {
        return jdbcTemplate.update(CLOSE_SQL, new MapSqlParameterSource()
                .addValue("id", pushId)
                .addValue("generation", generation)
                .addValue("status", status)) == 1;
    }

    public int countPending(UUID channelConnectionId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.channel_push
                WHERE channel_connection_id = :conn AND status <> 'SENT'
                """, new MapSqlParameterSource("conn", channelConnectionId), Integer.class);
        return count == null ? 0 : count;
    }
}
