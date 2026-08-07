package com.ecommercehub.app.stock;

import com.ecommercehub.domain.stock.StockReservationExpiryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Reservation expiry is inherently cross-org (Plan §3), same as the dispatcher (Faz
 * 0b) — enumerating organizations needs BYPASSRLS, which only hub_system has. Reuses
 * dispatcher's existing systemJdbcTemplate bean rather than standing up a second
 * hub_system connection pool; the actual per-org expiry work still runs through the
 * normal RLS-protected path, one tenant context (and one transaction) per org.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("worker")} — see {@link com.ecommercehub.app.reconcile.ReconcileScheduler}'s javadoc.
 */
@Component
@Profile("worker")
class StockReservationScheduler {

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final StockReservationExpiryService expiryService;

    StockReservationScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                               StockReservationExpiryService expiryService) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${hub.stock.reservation-sweep-period-ms:60000}")
    @SchedulerLock(name = "stock-reservation-expiry-sweep", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    void runExpirySweep() {
        List<UUID> organizationIds = systemJdbcTemplate.queryForList(
                "SELECT id FROM hub.organization", java.util.Map.of(), UUID.class);
        for (UUID organizationId : organizationIds) {
            expiryService.releaseExpiredReservations(organizationId);
        }
    }
}
