package com.ecommercehub.app.backfill;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
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
 *
 * <p>Plan v5 Faz 5: {@code @Profile("worker")} — see {@link com.ecommercehub.app.reconcile.ReconcileScheduler}'s
 * javadoc for why sweepers do not run in the "api" process.
 */
@Component
@Profile("worker")
class BackfillScheduler {

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final BackfillService backfillService;
    private final boolean schedulingEnabled;

    BackfillScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                       BackfillService backfillService,
                       @Value("${hub.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.backfillService = backfillService;
        this.schedulingEnabled = schedulingEnabled;
    }

    @Scheduled(fixedDelayString = "${hub.backfill.cycle-period-ms:5000}")
    @SchedulerLock(name = "backfill-cycle", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
    void runPendingBackfills() {
        if (!schedulingEnabled) {
            return;
        }
        List<Map<String, Object>> pending = systemJdbcTemplate.queryForList("""
                SELECT id, organization_id FROM hub.channel_connection
                WHERE backfill_status IS NULL OR backfill_status->>'ordersDone' != 'true'
                """, Map.of());

        for (Map<String, Object> row : pending) {
            backfillService.runOneCycle((UUID) row.get("organization_id"), (UUID) row.get("id"));
        }
    }
}
