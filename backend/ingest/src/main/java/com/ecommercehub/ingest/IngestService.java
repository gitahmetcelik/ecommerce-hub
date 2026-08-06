package com.ecommercehub.ingest;

import com.ecommercehub.domain.order.OrderEventPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Plan §4.1 (event-layer idempotency) + the outbox pattern (raw_event and work_batch
 * committed together, Plan §1.6's "the same transaction" requirement): the one and only
 * writer of hub.raw_event on the ingest path.
 *
 * <p>The <200ms ACK requirement (Plan Phase 2) is why this is two INSERTs and nothing
 * else — no synchronous order processing happens here; the dispatcher (Phase 0b) picks
 * up the work_batch row on its own schedule.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final String TASK_TYPE = "process-order-event";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IngestService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Returns true if this event was newly recorded, false if it was already seen (duplicate webhook delivery). */
    @Transactional
    public boolean ingest(UUID organizationId, UUID channelConnectionId, String channelEventId,
                           String rawBody, String signature, String traceId, OrderEventPayload payload) {
        // Closes the race a plain SELECT-then-INSERT would leave open for two
        // near-simultaneous deliveries of the exact same webhook — serializes them
        // so the second one always sees the first one's row.
        String lockKey = organizationId + ":" + channelConnectionId + ":" + channelEventId;
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", lockKey), (rs, rowNum) -> null);

        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.raw_event WHERE organization_id = :org AND channel_connection_id = :conn AND channel_event_id = :evt",
                new MapSqlParameterSource()
                        .addValue("org", organizationId)
                        .addValue("conn", channelConnectionId)
                        .addValue("evt", channelEventId),
                Integer.class);

        if (existing != null && existing > 0) {
            log.info("Duplicate webhook delivery for event {} — ACK without reprocessing", channelEventId);
            return false;
        }

        jdbcTemplate.update("""
                INSERT INTO hub.raw_event
                    (id, organization_id, channel_connection_id, channel_event_id, raw_body, signature, trace_id)
                VALUES (:id, :org, :conn, :evt, :body, :sig, :trace)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("org", organizationId)
                        .addValue("conn", channelConnectionId)
                        .addValue("evt", channelEventId)
                        .addValue("body", rawBody)
                        .addValue("sig", signature)
                        .addValue("trace", traceId));

        writeWorkBatch(organizationId, channelConnectionId, channelEventId, traceId, payload);
        return true;
    }

    private void writeWorkBatch(UUID organizationId, UUID channelConnectionId, String channelEventId,
                                 String traceId, OrderEventPayload payload) {
        try {
            // Plan §4.2: is_anahtari for 'olay-isle' is the channel event id — an event
            // is inherently one-shot, a repeat should never re-run it. work_batch.task_key
            // holds this raw business key; the dispatcher (Plan §1.1) is the only place
            // that combines it into the full org:type:key TaskKey text.
            String payloadJson = objectMapper.writeValueAsString(payload);

            jdbcTemplate.update("""
                    INSERT INTO hub.work_batch
                        (id, organization_id, channel_connection_id, task_type, task_key, payload, status, trace_id)
                    VALUES (:id, :org, :conn, :taskType, :taskKey, :payload::jsonb, 'PENDING', :trace)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("org", organizationId)
                            .addValue("conn", channelConnectionId)
                            .addValue("taskType", TASK_TYPE)
                            .addValue("taskKey", channelEventId)
                            .addValue("payload", payloadJson)
                            .addValue("trace", traceId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order event payload", e);
        }
    }
}
