package com.ecommercehub.app.returns;

import com.ecommercehub.domain.returns.ReturnApprovalTimerService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the return approval deadlines. Cross-org enumeration on the hub_system pool,
 * like every other sweeper — and, like them, gated by {@code hub.scheduling.enabled}
 * so cached test contexts do not sweep each other's data.
 */
@Component
public class ReturnScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReturnScheduler.class);

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final ReturnApprovalTimerService timerService;
    private final boolean schedulingEnabled;

    public ReturnScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                            ReturnApprovalTimerService timerService,
                            @Value("${hub.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.timerService = timerService;
        this.schedulingEnabled = schedulingEnabled;
    }

    @Scheduled(fixedDelayString = "${hub.returns.sweep-period-ms:60000}")
    @SchedulerLock(name = "return-approval-deadlines", lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
    public void sweepApprovalDeadlines() {
        if (!schedulingEnabled) {
            return;
        }
        for (UUID organizationId : allOrganizationIds()) {
            try {
                timerService.sendReminders(organizationId);
                timerService.escalateTimeouts(organizationId);
            } catch (RuntimeException e) {
                // One tenant's bad data must not stop the others' deadlines from running.
                log.warn("Return deadline sweep failed for organization {}", organizationId, e);
            }
        }
    }

    private List<UUID> allOrganizationIds() {
        return systemJdbcTemplate.queryForList("SELECT id FROM hub.organization", Map.of(), UUID.class);
    }
}
