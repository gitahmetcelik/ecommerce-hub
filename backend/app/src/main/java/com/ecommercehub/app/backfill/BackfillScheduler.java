package com.ecommercehub.app.backfill;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enumerating "every connection that still needs backfill" is cross-org, same
 * reasoning as the dispatcher and reservation-expiry sweeps — reuses the existing
 * hub_system pool rather than adding a fourth one.
 */
@Component
class BackfillScheduler {

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final BackfillService backfillService;

    BackfillScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                       BackfillService backfillService) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.backfillService = backfillService;
    }

    @Scheduled(fixedDelayString = "${hub.backfill.cycle-period-ms:5000}")
    @SchedulerLock(name = "backfill-cycle", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
    void runPendingBackfills() {
        List<Map<String, Object>> pending = systemJdbcTemplate.queryForList("""
                SELECT id, organization_id FROM hub.channel_connection
                WHERE backfill_status IS NULL OR backfill_status->>'ordersDone' != 'true'
                """, Map.of());

        for (Map<String, Object> row : pending) {
            backfillService.runOneCycle((UUID) row.get("organization_id"), (UUID) row.get("id"));
        }
    }
}
