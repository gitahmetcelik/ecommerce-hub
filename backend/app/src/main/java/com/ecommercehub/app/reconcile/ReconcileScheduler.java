package com.ecommercehub.app.reconcile;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.CallStatus;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.intent.ChannelCallIntentService;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.stock.StockConsistencyService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * plan §1.5's single sweeper. The engine's cron table has no organization column, so
 * per-tenant schedules cannot live there; instead one cron ticks, and the per-connection
 * cadence is read from {@code channel_connection.next_reconcile_at}. Adding a cron row
 * per tenant would have been the alternative, and it does not survive contact with a
 * few hundred tenants.
 *
 * <p>Cross-org enumeration runs on the hub_system pool, like every other sweeper.
 */
@Component
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);

    /** How long a SENT intent may sit before we go ask the channel what happened. */
    private static final Duration STUCK_INTENT_AGE = Duration.ofMinutes(2);

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final ReconcileService reconcileService;
    private final StockConsistencyService stockConsistencyService;
    private final ChannelCallIntentService channelCallIntentService;
    private final ChannelConnectionRepository channelConnectionRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final TenantContextService tenantContextService;
    private final TransactionTemplate transactionTemplate;
    private final boolean schedulingEnabled;

    public ReconcileScheduler(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                               ReconcileService reconcileService,
                               StockConsistencyService stockConsistencyService,
                               ChannelCallIntentService channelCallIntentService,
                               ChannelConnectionRepository channelConnectionRepository,
                               CredentialEncryptionService credentialEncryptionService,
                               ConnectorRegistry connectorRegistry,
                               TenantContextService tenantContextService,
                               TransactionTemplate transactionTemplate,
                               @Value("${hub.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.reconcileService = reconcileService;
        this.stockConsistencyService = stockConsistencyService;
        this.channelCallIntentService = channelCallIntentService;
        this.channelConnectionRepository = channelConnectionRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.tenantContextService = tenantContextService;
        this.transactionTemplate = transactionTemplate;
        this.schedulingEnabled = schedulingEnabled;
    }

    /** plan §11 row 1: per-connection delta reconcile at its own configured cadence. */
    @Scheduled(fixedDelayString = "${hub.reconcile.sweep-period-ms:30000}")
    @SchedulerLock(name = "reconcile-delta-sweep", lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
    public void sweepDueConnections() {
        if (!schedulingEnabled) {
            return;
        }

        List<Map<String, Object>> due = systemJdbcTemplate.queryForList("""
                SELECT id, organization_id, reconcile_interval_minutes
                FROM hub.channel_connection
                WHERE status = 'ACTIVE'
                  AND (next_reconcile_at IS NULL OR next_reconcile_at <= now())
                """, Map.of());

        for (Map<String, Object> row : due) {
            UUID connectionId = (UUID) row.get("id");
            UUID organizationId = (UUID) row.get("organization_id");
            int intervalMinutes = ((Number) row.get("reconcile_interval_minutes")).intValue();

            // Scheduled before the work, not after: a connection whose reconcile throws
            // must not be retried on every single tick — it would monopolise the sweeper.
            scheduleNext(connectionId, intervalMinutes);
            try {
                reconcileService.reconcileOpenOrders(organizationId, connectionId);
            } catch (RuntimeException e) {
                log.warn("Delta reconcile failed for connection {}", connectionId, e);
            }
        }
    }

    /**
     * plan §11 row 2: the hourly pass. Returns enter the hub here — the flow in plan §7
     * has no other way to start, so without this the whole return machine only ever runs
     * for returns a person typed in by hand.
     */
    @Scheduled(fixedDelayString = "${hub.reconcile.return-sweep-period-ms:3600000}")
    @SchedulerLock(name = "reconcile-returns", lockAtLeastFor = "PT10S", lockAtMostFor = "PT30M")
    public void sweepReturns() {
        if (!schedulingEnabled) {
            return;
        }
        for (Map<String, Object> row : activeConnections()) {
            try {
                reconcileService.reconcileReturns((UUID) row.get("organization_id"), (UUID) row.get("id"));
            } catch (RuntimeException e) {
                log.warn("Return reconcile failed for connection {}", row.get("id"), e);
            }
        }
    }

    /** plan §11 rows 3-4: the nightly passes — channel drift, then the local ledger replay. */
    @Scheduled(cron = "${hub.reconcile.nightly-cron:0 0 3 * * *}")
    @SchedulerLock(name = "reconcile-nightly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT6H")
    public void runNightly() {
        if (!schedulingEnabled) {
            return;
        }

        for (Map<String, Object> row : activeConnections()) {
            try {
                reconcileService.reconcileChannelStock((UUID) row.get("organization_id"), (UUID) row.get("id"));
            } catch (RuntimeException e) {
                log.warn("Nightly stock reconcile failed for connection {}", row.get("id"), e);
            }
        }

        for (UUID organizationId : allOrganizationIds()) {
            try {
                runLedgerConsistencyCheck(organizationId);
            } catch (RuntimeException e) {
                log.warn("Ledger consistency check failed for organization {}", organizationId, e);
            }
        }
    }

    /**
     * plan §11 row 5: SENT intents that never got a result. Asks the channel instead of
     * retrying the call — a retried refund is a second refund.
     */
    @Scheduled(fixedDelayString = "${hub.reconcile.intent-sweep-period-ms:60000}")
    @SchedulerLock(name = "reconcile-stuck-intents", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void resolveStuckIntents() {
        if (!schedulingEnabled) {
            return;
        }

        for (UUID organizationId : allOrganizationIds()) {
            try {
                resolveStuckIntentsFor(organizationId);
            } catch (RuntimeException e) {
                log.warn("Stuck intent sweep failed for organization {}", organizationId, e);
            }
        }
    }

    /**
     * Wrapped in an explicit TransactionTemplate rather than annotated @Transactional:
     * both of these are called from another method on this same bean, and a self-call
     * never passes through the proxy. The annotation would silently do nothing, leaving
     * {@code set_config(..., true)} to run in a transaction of its own and be discarded
     * on commit — so the actual work would then run with no tenant context at all.
     */
    public void runLedgerConsistencyCheck(UUID organizationId) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);
            stockConsistencyService.checkOrganization(organizationId);
        });
    }

    public void resolveStuckIntentsFor(UUID organizationId) {
        transactionTemplate.executeWithoutResult(status ->
                resolveStuckIntentsInTransaction(organizationId));
    }

    private void resolveStuckIntentsInTransaction(UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        channelCallIntentService.recoverStuckIntents(intent -> {
            Optional<ChannelConnection> connection = channelConnectionRepository.findById(intent.getChannelConnectionId());
            if (connection.isEmpty()) {
                return Optional.empty();
            }
            ChannelConnectionRef ref = new ChannelConnectionRef(
                    connection.get().getId(), organizationId, connection.get().getChannelType(),
                    credentialEncryptionService.decrypt(connection.get().getEncryptedCredentials(),
                            connection.get().getKeyVersion()));

            CallStatus status = connectorRegistry.require(connection.get().getChannelType())
                    .queryCallStatus(ref, new CallIntentRef(intent.getId(), intent.getChannelIdempotencyKey()));
            return status.resolved() ? Optional.of(status.resultJson()) : Optional.empty();
        }, STUCK_INTENT_AGE);
    }

    private void scheduleNext(UUID connectionId, int intervalMinutes) {
        systemJdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET next_reconcile_at = now() + make_interval(mins => :minutes),
                    updated_at = now(), version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", connectionId)
                .addValue("minutes", intervalMinutes));
    }

    private List<Map<String, Object>> activeConnections() {
        return systemJdbcTemplate.queryForList("""
                SELECT id, organization_id FROM hub.channel_connection WHERE status = 'ACTIVE'
                """, Map.of());
    }

    private List<UUID> allOrganizationIds() {
        return systemJdbcTemplate.queryForList("SELECT id FROM hub.organization", Map.of(), UUID.class);
    }
}
