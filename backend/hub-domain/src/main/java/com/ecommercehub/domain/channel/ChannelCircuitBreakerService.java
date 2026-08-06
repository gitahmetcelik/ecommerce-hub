package com.ecommercehub.domain.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan Phase 4 "channel circuit breaker and CREDENTIALS_INVALID". A channel that is down, rate
 * limiting everything, or holding revoked credentials must stop being called — not
 * because one more failed request is expensive, but because a dead channel's retries
 * consume the very rate-limit budget (Plan §9) that the healthy channels' pushes need.
 *
 * <p><b>Writes here are deliberately outside the caller's transaction.</b> They go
 * through the hub_system pool, which is not bound to the application transaction
 * manager, so each statement autocommits on its own connection. Failures are recorded
 * from a {@code catch} block whose surrounding transaction is usually about to roll
 * back; recording "this channel failed" inside that transaction would roll the record
 * back with it and the breaker would never trip, no matter how often the channel failed.
 *
 * <p>Using hub_system (BYPASSRLS) also sidesteps a second problem: Plan §3(c) makes the
 * tenant context strictly transaction-local, so a connection taken outside the caller's
 * transaction has no {@code hub.org_id} and every RLS-checked statement on it would
 * fail. This is the same "org-spanning bookkeeping runs as hub_system" case as the
 * dispatcher and the sweepers, not a loophole around isolation.
 */
@Service
public class ChannelCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(ChannelCircuitBreakerService.class);

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final int failureThreshold;
    private final Duration openDuration;

    public ChannelCircuitBreakerService(
            @org.springframework.beans.factory.annotation.Qualifier("systemJdbcTemplate")
            NamedParameterJdbcTemplate systemJdbcTemplate,
            @Value("${hub.channel.circuit-failure-threshold:5}") int failureThreshold,
            @Value("${hub.channel.circuit-open-seconds:60}") long openSeconds) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofSeconds(openSeconds);
    }

    /**
     * True when this connection may be called right now.
     *
     * <p>An open circuit whose backoff has elapsed is callable again — the half-open
     * trial. Something has to make the first call after an outage, and the alternative
     * (waiting for an explicit reset) means a channel that recovered on its own stays
     * dark until a human notices. That trial's outcome decides what happens next:
     * {@link #recordSuccess} returns the connection to ACTIVE, {@link #recordFailure}
     * pushes the backoff out again.
     */
    public boolean isCallable(UUID channelConnectionId) {
        Boolean callable = systemJdbcTemplate.queryForObject("""
                SELECT status IN ('ACTIVE', 'CIRCUIT_OPEN')
                       AND (circuit_open_until IS NULL OR circuit_open_until <= now())
                FROM hub.channel_connection WHERE id = :id
                """, new MapSqlParameterSource("id", channelConnectionId), Boolean.class);
        return Boolean.TRUE.equals(callable);
    }

    /** Clears the failure streak and closes the circuit. Any success is enough — the counter is a streak, not a total. */
    public void recordSuccess(UUID channelConnectionId) {
        systemJdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET consecutive_failures = 0,
                    circuit_open_until = NULL,
                    last_failure_reason = NULL,
                    status = CASE WHEN status = 'CIRCUIT_OPEN' THEN 'ACTIVE' ELSE status END,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND (consecutive_failures > 0 OR circuit_open_until IS NOT NULL OR status = 'CIRCUIT_OPEN')
                """, new MapSqlParameterSource("id", channelConnectionId));
    }

    /**
     * Records one failed call. Trips the circuit on the {@code failureThreshold}-th
     * consecutive failure. A connection already in CREDENTIALS_INVALID keeps that
     * status — it is the more specific and more actionable of the two.
     */
    public void recordFailure(UUID channelConnectionId, String reason) {
        Integer failures = systemJdbcTemplate.queryForObject("""
                UPDATE hub.channel_connection
                SET consecutive_failures = consecutive_failures + 1,
                    last_failure_reason = :reason,
                    updated_at = now(), version = version + 1
                WHERE id = :id
                RETURNING consecutive_failures
                """, new MapSqlParameterSource().addValue("id", channelConnectionId).addValue("reason", reason),
                Integer.class);

        if (failures != null && failures >= failureThreshold) {
            openCircuit(channelConnectionId, reason);
        }
    }

    private void openCircuit(UUID channelConnectionId, String reason) {
        systemJdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET status = CASE WHEN status = 'CREDENTIALS_INVALID' THEN status ELSE 'CIRCUIT_OPEN' END,
                    circuit_open_until = :until,
                    updated_at = now(), version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", channelConnectionId)
                .addValue("until", Timestamp.from(Instant.now().plus(openDuration))));

        log.warn("Circuit opened for channel connection {} for {}s after {} consecutive failures: {}",
                channelConnectionId, openDuration.toSeconds(), failureThreshold, reason);
    }

    /**
     * Plan Phase 4 gate: "invalid credentials: the channel is taken out of service and the operator queue is notified".
     * Unlike a transient failure this does not expire — {@code circuit_open_until} is
     * left null precisely so nothing reopens the connection until a human re-authorises it.
     */
    public void markCredentialsInvalid(UUID organizationId, UUID channelConnectionId, String reason) {
        int updated = systemJdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET status = 'CREDENTIALS_INVALID',
                    last_failure_reason = :reason,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND status <> 'CREDENTIALS_INVALID'
                """, new MapSqlParameterSource().addValue("id", channelConnectionId).addValue("reason", reason));

        if (updated == 0) {
            return; // Already flagged — one operator queue item per incident, not one per retry.
        }

        systemJdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (gen_random_uuid(), :org, 'CHANNEL_CREDENTIALS_INVALID', :description, :ref)
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("description", "Channel connection " + channelConnectionId
                        + " rejected our credentials and has been taken out of service: " + reason)
                .addValue("ref", channelConnectionId));

        log.error("Channel connection {} marked CREDENTIALS_INVALID and escalated to the operator queue: {}",
                channelConnectionId, reason);
    }
}
