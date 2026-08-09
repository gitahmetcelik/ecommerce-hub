package com.ecommercehub.app.reconcile;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.ChannelProduct;
import com.ecommercehub.connector.ChannelReturn;
import com.ecommercehub.connector.ChannelReturnItem;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.ecommercehub.app.backfill.ChannelBudgetRegistry;
import com.ecommercehub.domain.channel.ChannelCircuitBreakerService;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.returns.ReturnService;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.stock.ChannelAvailability;
import com.ecommercehub.domain.stock.StockAvailabilityService;
import com.ecommercehub.domain.stock.StockDiscrepancyRecorder;
import com.ecommercehub.domain.stock.StockDiscrepancyType;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan §11's reconcile layers, minus the scheduling (see {@link ReconcileScheduler}).
 *
 * <p>Two rules run through everything here. <b>Nothing is auto-corrected</b> — the
 * nightly stock pass writes stock_discrepancy rows and stops, per Plan §0. And
 * <b>reconcile is not an event</b>: it re-asserts a target state rather than applying
 * a delta, which is only safe because Plan §6's transitions are target-status
 * idempotent. Without that property, a reconcile followed by a deferred webhook for
 * the same transition would decrement stock twice.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("worker")} — its only caller is {@link ReconcileScheduler}.
 */
@Service
@Profile("worker")
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** Plan §8: delta fetches always overlap the previous window by this much. */
    private static final Duration SINCE_OVERLAP = Duration.ofMinutes(5);
    private static final int PAGE_SIZE = 100;

    private final ChannelConnectionRepository channelConnectionRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final ChannelBudgetRegistry budgetRegistry;
    private final ChannelCircuitBreakerService circuitBreaker;
    private final StockAvailabilityService stockAvailabilityService;
    private final StockDiscrepancyRecorder discrepancyRecorder;
    private final OrderProcessingService orderProcessingService;
    private final ReturnService returnService;
    private final TenantContextService tenantContextService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconcileService(ChannelConnectionRepository channelConnectionRepository,
                             CredentialEncryptionService credentialEncryptionService,
                             ConnectorRegistry connectorRegistry,
                             ChannelBudgetRegistry budgetRegistry,
                             ChannelCircuitBreakerService circuitBreaker,
                             StockAvailabilityService stockAvailabilityService,
                             StockDiscrepancyRecorder discrepancyRecorder,
                             OrderProcessingService orderProcessingService,
                             ReturnService returnService,
                             TenantContextService tenantContextService,
                             NamedParameterJdbcTemplate jdbcTemplate) {
        this.channelConnectionRepository = channelConnectionRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.budgetRegistry = budgetRegistry;
        this.circuitBreaker = circuitBreaker;
        this.stockAvailabilityService = stockAvailabilityService;
        this.discrepancyRecorder = discrepancyRecorder;
        this.orderProcessingService = orderProcessingService;
        this.returnService = returnService;
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Nightly full stock pass (Plan §11, BACKGROUND class): walks the channel's whole
     * catalog and reports every quantity that disagrees with ours.
     *
     * @return how many drifting variants were reported
     */
    @Transactional
    public int reconcileChannelStock(UUID organizationId, UUID channelConnectionId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        if (!circuitBreaker.isCallable(channelConnectionId)) {
            return 0;
        }

        ChannelConnection connection = requireConnection(channelConnectionId);
        PlatformConnector connector = connectorRegistry.require(connection.getChannelType());
        ChannelConnectionRef ref = toRef(organizationId, connection);
        RateLimitBudget budget = budgetRegistry.forConnection(organizationId, channelConnectionId);

        int reported = 0;
        int pageNumber = 1; // mock-pazaryeri (and every real channel so far) pages from 1
        boolean hasMore = true;

        while (hasMore) {
            if (!budget.tryAcquire(BudgetClass.BACKGROUND)) {
                log.info("Nightly stock reconcile for connection {} paused at page {} — BACKGROUND budget exhausted",
                        channelConnectionId, pageNumber);
                break;
            }

            PagedResult<ChannelProduct> page = connector.fetchCatalog(ref, new Page(pageNumber, PAGE_SIZE));
            for (ChannelProduct product : page.items()) {
                reported += compareOne(organizationId, channelConnectionId, product);
            }
            hasMore = page.hasMore();
            pageNumber++;
        }

        circuitBreaker.recordSuccess(channelConnectionId);
        if (reported > 0) {
            log.warn("Nightly reconcile reported {} drifting variant(s) on connection {} — reported, not corrected",
                    reported, channelConnectionId);
        }
        return reported;
    }

    private int compareOne(UUID organizationId, UUID channelConnectionId, ChannelProduct product) {
        if (product.availableQuantity() == null) {
            return 0; // Channel does not report stock — nothing to compare against.
        }

        Optional<UUID> variantId = findMappedVariant(organizationId, channelConnectionId, product.channelVariantId());
        if (variantId.isEmpty()) {
            // Unmatched channel items are Phase 3's mapping_candidate problem, not a stock
            // drift — reporting them here would flood the drift report with catalog noise.
            return 0;
        }

        int expected = stockAvailabilityService.computeFor(organizationId, variantId.get()).stream()
                .filter(a -> a.channelConnectionId().equals(channelConnectionId))
                .mapToInt(ChannelAvailability::quantity)
                .findFirst()
                .orElse(0);

        if (expected == product.availableQuantity()) {
            return 0;
        }

        discrepancyRecorder.record(organizationId, channelConnectionId, variantId.get(),
                StockDiscrepancyType.CHANNEL_DRIFT, expected, product.availableQuantity());
        return 1;
    }

    /**
     * Delta order reconcile (Plan §11, OPERATIONAL class): the safety net for webhooks
     * that never arrived. Feeds the very same OrderProcessingService the webhook path
     * uses, so a re-observed order takes the identical code path and the identical
     * idempotency guarantees.
     *
     * @return how many orders were re-observed
     */
    @Transactional
    public int reconcileOpenOrders(UUID organizationId, UUID channelConnectionId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        if (!circuitBreaker.isCallable(channelConnectionId)) {
            return 0;
        }

        ChannelConnection connection = requireConnection(channelConnectionId);
        PlatformConnector connector = connectorRegistry.require(connection.getChannelType());
        ChannelConnectionRef ref = toRef(organizationId, connection);
        RateLimitBudget budget = budgetRegistry.forConnection(organizationId, channelConnectionId);

        Instant since = lastOrderSyncAt(channelConnectionId)
                .map(last -> last.minus(SINCE_OVERLAP))
                .orElse(Instant.EPOCH);

        int observed = 0;
        int pageNumber = 1; // mock-pazaryeri (and every real channel so far) pages from 1
        boolean hasMore = true;
        Instant startedAt = Instant.now();

        while (hasMore) {
            if (!budget.tryAcquire(BudgetClass.OPERATIONAL)) {
                log.info("Delta reconcile for connection {} paused — OPERATIONAL budget exhausted", channelConnectionId);
                // Leaves last_order_sync_at untouched, so the next run re-covers this window.
                return observed;
            }

            PagedResult<ChannelOrder> page = connector.fetchOrders(ref, since, new Page(pageNumber, PAGE_SIZE));
            for (ChannelOrder order : page.items()) {
                // Bug found post-Faz-8: process() joins this method's own @Transactional
                // (REQUIRED), so one order throwing (a deferred transition, a malformed
                // real-world order) marked the whole delta-reconcile window rollback-only
                // — undetectable here because Spring does that the moment the exception
                // crosses process()'s own transactional boundary, before a catch at this
                // call site ever runs. markSynced() below would never be reached, so the
                // same window (and the same poison order) got re-walked forever, silently
                // blocking recovery for every other order in it too — exactly the failure
                // this reconcile pass exists to prevent. processIsolated() runs the order
                // in its own transaction, so a failure here can't take the window with it.
                try {
                    orderProcessingService.processIsolated(toPayload(organizationId, channelConnectionId, order));
                    observed++;
                } catch (RuntimeException e) {
                    escalateUnresolvableOrder(organizationId, channelConnectionId, order.channelOrderId(), e);
                }
            }
            hasMore = page.hasMore();
            pageNumber++;
        }

        markSynced(channelConnectionId, startedAt);
        circuitBreaker.recordSuccess(channelConnectionId);
        return observed;
    }

    /**
     * Plan §11 row 2: the hourly return delta pass.
     *
     * <p>This is how a return actually enters the hub. Without it the return flow exists
     * but nothing ever starts it — Plan §7's machine would only ever run for returns
     * somebody typed in by hand.
     *
     * <p>Idempotent by way of {@code channel_return_id}: the overlap window (and any
     * redelivery) re-presents returns we already know about, and each one resolves to
     * the existing row rather than opening a second approval for the same parcel.
     *
     * @return how many returns were newly opened
     */
    @Transactional
    public int reconcileReturns(UUID organizationId, UUID channelConnectionId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        if (!circuitBreaker.isCallable(channelConnectionId)) {
            return 0;
        }

        ChannelConnection connection = requireConnection(channelConnectionId);
        PlatformConnector connector = connectorRegistry.require(connection.getChannelType());
        ChannelConnectionRef ref = toRef(organizationId, connection);
        RateLimitBudget budget = budgetRegistry.forConnection(organizationId, channelConnectionId);

        Instant since = lastReturnSyncAt(channelConnectionId)
                .map(last -> last.minus(SINCE_OVERLAP))
                .orElse(Instant.EPOCH);

        int opened = 0;
        int pageNumber = 1;
        boolean hasMore = true;
        Instant startedAt = Instant.now();

        while (hasMore) {
            if (!budget.tryAcquire(BudgetClass.OPERATIONAL)) {
                log.info("Return reconcile for connection {} paused — OPERATIONAL budget exhausted", channelConnectionId);
                // Cursor untouched, so the next run re-covers this window rather than skipping it.
                return opened;
            }

            PagedResult<ChannelReturn> page = connector.fetchReturns(ref, since, new Page(pageNumber, PAGE_SIZE));
            for (ChannelReturn channelReturn : page.items()) {
                opened += openIfResolvable(organizationId, channelConnectionId, channelReturn) ? 1 : 0;
            }
            hasMore = page.hasMore();
            pageNumber++;
        }

        markReturnsSynced(channelConnectionId, startedAt);
        circuitBreaker.recordSuccess(channelConnectionId);
        return opened;
    }

    /**
     * Turns one channel return into a hub return, or escalates it.
     *
     * <p>A return we cannot attach to an order and its items is <b>not</b> dropped. That
     * is the same rule Phase 3 applies to unmatched catalogue items (Plan §3: an unmatched
     * line is never silently dropped) and it matters more here — a silently discarded return
     * is a customer waiting for a refund that no one in the system knows is owed.
     */
    private boolean openIfResolvable(UUID organizationId, UUID channelConnectionId, ChannelReturn channelReturn) {
        Optional<SalesOrderRef> order = findOrder(organizationId, channelConnectionId, channelReturn.channelOrderId());
        if (order.isEmpty()) {
            escalateUnresolvableReturn(organizationId, channelReturn,
                    "no sales_order matches channel order " + channelReturn.channelOrderId());
            return false;
        }

        List<ReturnService.RequestedItem> items = new java.util.ArrayList<>();
        for (ChannelReturnItem line : channelReturn.items()) {
            Optional<UUID> orderItemId = findOrderItem(organizationId, order.get().id(), line.sku());
            if (orderItemId.isEmpty()) {
                escalateUnresolvableReturn(organizationId, channelReturn,
                        "order " + channelReturn.channelOrderId() + " has no line for sku " + line.sku());
                return false;
            }
            items.add(new ReturnService.RequestedItem(orderItemId.get(), line.quantity()));
        }

        if (items.isEmpty()) {
            escalateUnresolvableReturn(organizationId, channelReturn,
                    "the channel reported no line items, so what came back is unknown");
            return false;
        }

        return returnService.recordChannelReturnIfNew(organizationId, order.get().id(),
                channelReturn.channelReturnId(), channelReturn.status(), items).created();
    }

    private void escalateUnresolvableOrder(UUID organizationId, UUID channelConnectionId, String channelOrderId, RuntimeException cause) {
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                SELECT gen_random_uuid(), :org, 'ORDER_RECONCILE_FAILED', :description, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM hub.operator_queue
                    WHERE organization_id = :org AND type = 'ORDER_RECONCILE_FAILED'
                      AND description = :description AND status = 'PENDING'
                )
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("description", "Channel connection " + channelConnectionId + " order " + channelOrderId
                        + " could not be reconciled: " + cause.getMessage()));

        log.warn("Order {} from connection {} could not be reconciled — escalated rather than dropped",
                channelOrderId, channelConnectionId, cause);
    }

    private void escalateUnresolvableReturn(UUID organizationId, ChannelReturn channelReturn, String reason) {
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                SELECT gen_random_uuid(), :org, 'RETURN_UNRESOLVABLE', :description, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM hub.operator_queue
                    WHERE organization_id = :org AND type = 'RETURN_UNRESOLVABLE'
                      AND description = :description AND status = 'PENDING'
                )
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("description", "Channel return " + channelReturn.channelReturnId()
                        + " could not be attached to an order: " + reason));

        log.warn("Channel return {} could not be resolved ({}) — escalated rather than dropped",
                channelReturn.channelReturnId(), reason);
    }

    private Optional<SalesOrderRef> findOrder(UUID organizationId, UUID channelConnectionId, String channelOrderNumber) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT id FROM hub.sales_order
                WHERE organization_id = :org AND channel_connection_id = :conn AND channel_order_number = :number
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("conn", channelConnectionId)
                .addValue("number", channelOrderNumber), UUID.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(new SalesOrderRef(ids.get(0)));
    }

    private Optional<UUID> findOrderItem(UUID organizationId, UUID salesOrderId, String sku) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT oi.id FROM hub.order_item oi
                JOIN hub.variant v ON v.id = oi.variant_id
                WHERE oi.organization_id = :org AND oi.sales_order_id = :order AND v.sku = :sku
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("order", salesOrderId)
                .addValue("sku", sku), UUID.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private Optional<Instant> lastReturnSyncAt(UUID channelConnectionId) {
        Timestamp last = jdbcTemplate.queryForObject(
                "SELECT last_return_sync_at FROM hub.channel_connection WHERE id = :id",
                new MapSqlParameterSource("id", channelConnectionId), Timestamp.class);
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    private void markReturnsSynced(UUID channelConnectionId, Instant startedAt) {
        jdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET last_return_sync_at = :at, updated_at = now(), version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", channelConnectionId)
                .addValue("at", Timestamp.from(startedAt)));
    }

    private record SalesOrderRef(UUID id) {
    }

    private Optional<UUID> findMappedVariant(UUID organizationId, UUID channelConnectionId, String channelVariantId) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT variant_id FROM hub.channel_product_mapping
                WHERE organization_id = :org AND channel_connection_id = :conn AND channel_variant_id = :cvid
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("conn", channelConnectionId)
                .addValue("cvid", channelVariantId), UUID.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private Optional<Instant> lastOrderSyncAt(UUID channelConnectionId) {
        Timestamp last = jdbcTemplate.queryForObject(
                "SELECT last_order_sync_at FROM hub.channel_connection WHERE id = :id",
                new MapSqlParameterSource("id", channelConnectionId), Timestamp.class);
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    /**
     * Stamped with the time the fetch <em>started</em>, never the time it finished — an
     * order created while a long page walk was in progress must fall inside the next
     * window, not into the gap between the two.
     */
    private void markSynced(UUID channelConnectionId, Instant startedAt) {
        jdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET last_order_sync_at = :at, updated_at = now(), version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", channelConnectionId)
                .addValue("at", Timestamp.from(startedAt)));
    }

    private OrderEventPayload toPayload(UUID organizationId, UUID channelConnectionId, ChannelOrder order) {
        // item.item().sku() is genuinely null on a barcode-keyed channel (Plan v5 §1) —
        // channelVariantId is the one identifier every shape guarantees, so it is what
        // stands in for channelProductId too when the channel gives us nothing more
        // specific to distinguish the two.
        List<OrderEventPayload.OrderEventItem> items = order.items().stream()
                .map(item -> new OrderEventPayload.OrderEventItem(item.item().sku(), item.item().channelVariantId(),
                        item.item().channelVariantId(), item.item().barcode(),
                        item.quantity(), item.unitPrice(), BigDecimal.ZERO, OrderItemStatus.CREATED))
                .toList();

        BigDecimal total = order.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrderEventPayload(organizationId, channelConnectionId, "reconcile:" + order.channelOrderId(),
                order.channelOrderId(), order.eventAt(), order.channelEventSequence(), total, "USD", items, null);
    }

    private ChannelConnection requireConnection(UUID channelConnectionId) {
        return channelConnectionRepository.findById(channelConnectionId)
                .orElseThrow(() -> new IllegalArgumentException("No channel_connection " + channelConnectionId));
    }

    private ChannelConnectionRef toRef(UUID organizationId, ChannelConnection connection) {
        return new ChannelConnectionRef(connection.getId(), organizationId, connection.getChannelType(),
                credentialEncryptionService.decrypt(connection.getEncryptedCredentials(), connection.getKeyVersion()));
    }
}
