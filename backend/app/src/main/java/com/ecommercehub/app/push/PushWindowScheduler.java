package com.ecommercehub.app.push;

import com.ecommercehub.domain.push.ChannelPushWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opens one send window per channel connection that has pending pushes, by writing a
 * {@code push-send} work_batch row the dispatcher (Phase 0b) picks up like any other work.
 *
 * <p>The task key is {@code channelConnectionId:windowStart}, exactly Plan §4.2's
 * {@code kanal_baglantisi_id + pencere_baslangic}. Because the engine's idempotency key
 * is globally unique and never expires (Plan §1.3), a key without a window component
 * would let a connection's push task run <em>once, ever</em>, and every later change
 * would be silently swallowed. The window is what makes the key repeat-safe.
 *
 * <p>Enumerating pending pushes is cross-org, so it uses the hub_system pool like the
 * other sweepers.
 */
@Component
@EnableConfigurationProperties(PushProperties.class)
public class PushWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(PushWindowScheduler.class);
    static final String TASK_TYPE = "push-send";

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PushProperties properties;
    private final boolean schedulingEnabled;

    public PushWindowScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                                ObjectMapper objectMapper, PushProperties properties,
                                @Value("${hub.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.schedulingEnabled = schedulingEnabled;
    }

    @Scheduled(fixedDelayString = "${hub.push.window-ms:5000}")
    @SchedulerLock(name = "push-window", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
    public void openWindows() {
        if (schedulingEnabled) {
            openWindows(currentWindowStart());
        }
    }

    /** @return how many windows were opened */
    public int openWindows(Instant windowStart) {
        List<Map<String, Object>> connections = systemJdbcTemplate.queryForList("""
                SELECT DISTINCT p.organization_id, p.channel_connection_id
                FROM hub.channel_push p
                JOIN hub.channel_connection c ON c.id = p.channel_connection_id
                WHERE p.status = 'PENDING'
                  -- Matches ChannelCircuitBreakerService.isCallable, half-open trial
                  -- included: opening a window for a connection the sender would refuse
                  -- to call only burns a task, and skipping one it would happily call
                  -- keeps a recovered channel stale.
                  AND c.status IN ('ACTIVE', 'CIRCUIT_OPEN')
                  AND (c.circuit_open_until IS NULL OR c.circuit_open_until <= now())
                """, Map.of());

        int opened = 0;
        for (Map<String, Object> row : connections) {
            UUID organizationId = (UUID) row.get("organization_id");
            UUID channelConnectionId = (UUID) row.get("channel_connection_id");
            if (enqueueWindow(organizationId, channelConnectionId, windowStart)) {
                opened++;
            }
        }
        if (opened > 0) {
            log.debug("Opened {} push window(s) for window start {}", opened, windowStart);
        }
        return opened;
    }

    private boolean enqueueWindow(UUID organizationId, UUID channelConnectionId, Instant windowStart) {
        String taskKey = channelConnectionId + ":" + windowStart;
        try {
            String payload = objectMapper.writeValueAsString(
                    new ChannelPushWindow(organizationId, channelConnectionId, windowStart.toString()));

            // The engine would dedupe a repeat submission by idempotency key anyway; this
            // guard just avoids leaving a second work_batch row behind pointing at the
            // same task, which would make the queue depth reported by the internal screen
            // (and the Phase 4 load gate) read high for no reason.
            int inserted = systemJdbcTemplate.update("""
                    INSERT INTO hub.work_batch
                        (id, organization_id, channel_connection_id, task_type, task_key, payload, status)
                    SELECT gen_random_uuid(), :org, :conn, :taskType, :taskKey, CAST(:payload AS jsonb), 'PENDING'
                    WHERE NOT EXISTS (
                        SELECT 1 FROM hub.work_batch
                        WHERE task_type = :taskType AND task_key = :taskKey
                    )
                    """, new MapSqlParameterSource()
                    .addValue("org", organizationId)
                    .addValue("conn", channelConnectionId)
                    .addValue("taskType", TASK_TYPE)
                    .addValue("taskKey", taskKey)
                    .addValue("payload", payload));
            return inserted > 0;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push window payload", e);
        }
    }

    /** Floors the clock to the window grid, so every instance in a cluster agrees on the same window key. */
    Instant currentWindowStart() {
        long windowMs = properties.getWindowMs();
        return Instant.ofEpochMilli(System.currentTimeMillis() / windowMs * windowMs);
    }
}
