package com.ecommercehub.domain.order;

import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.stock.StockReservation;
import com.ecommercehub.domain.stock.StockReservationRepository;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies one order event (plan Faz 2) end to end: find-or-create the sales_order,
 * apply the plan §6 transition decision per item, run the matching stock side
 * effects, and recompute derived_status — all under the order row's lock, in one
 * transaction, per plan §3.
 */
@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);
    private static final int RESERVATION_HOLD_HOURS = 24;

    private final SalesOrderRepository salesOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CatalogMatchingService catalogMatchingService;
    private final StockLedgerService stockLedgerService;
    private final StockReservationRepository stockReservationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContextService tenantContextService;

    public OrderProcessingService(SalesOrderRepository salesOrderRepository, OrderItemRepository orderItemRepository,
                                   CatalogMatchingService catalogMatchingService, StockLedgerService stockLedgerService,
                                   StockReservationRepository stockReservationRepository, JdbcTemplate jdbcTemplate,
                                   TenantContextService tenantContextService) {
        this.salesOrderRepository = salesOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.catalogMatchingService = catalogMatchingService;
        this.stockLedgerService = stockLedgerService;
        this.stockReservationRepository = stockReservationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContextService = tenantContextService;
    }

    /**
     * Sets its own RLS tenant context rather than trusting the caller to have done
     * so — this runs from a webhook handler, a task handler, and tests alike, and
     * plan §3(c)'s "one place sets the context" is about avoiding scattered raw
     * set_config calls, not about forbidding an entry point from asserting its own.
     */
    @Transactional
    public void process(OrderEventPayload event) {
        tenantContextService.setTransactionTenantContext(event.organizationId());
        UUID orderId = findOrCreateOrder(event).getId();
        final SalesOrder order = salesOrderRepository.findByIdForUpdate(orderId).orElseThrow();

        Long currentSequence = order.getChannelEventSequence();
        Instant currentEventAt = order.getChannelEventAt();

        List<String> deferred = new ArrayList<>();
        for (OrderEventPayload.OrderEventItem incoming : event.items()) {
            CatalogMatchingService.MatchResult match = catalogMatchingService.resolve(
                    event.organizationId(), event.channelConnectionId(),
                    incoming.channelProductId() != null ? incoming.channelProductId() : incoming.sku(),
                    incoming.channelVariantId() != null ? incoming.channelVariantId() : incoming.sku(),
                    incoming.sku(), incoming.barcode(), incoming.sku());

            if (!match.matched()) {
                // plan Faz 3 gate: unmatched items never touch stock and never silently
                // vanish — CatalogMatchingService already queued it for operator review;
                // there is no variant_id to create an order_item against (NOT NULL FK),
                // so this item is simply absent from the order until someone resolves it.
                log.info("Skipping unmatched item sku={} for order {} — queued for catalog review",
                        incoming.sku(), event.channelOrderNumber());
                continue;
            }

            UUID variantId = match.variantId();
            OrderItem item = orderItemRepository.findBySalesOrderIdAndVariantId(order.getId(), variantId)
                    .orElseGet(() -> createItem(order, variantId, incoming));

            OrderItemTransitionDecision.Decision decision = OrderItemTransitionDecision.decide(
                    item.getStatus(), currentSequence, currentEventAt,
                    incoming.targetStatus(), event.channelEventSequence(), event.channelEventAt());

            applyDecision(order, item, decision, incoming);

            if (decision.action() == OrderItemTransitionDecision.Action.DEFER) {
                deferred.add(item.getId() + " -> " + incoming.targetStatus() + ": " + decision.reason());
            }
        }

        if (!deferred.isEmpty()) {
            throw new OrderItemTransitionDeferredException(
                    "Order " + order.getChannelOrderNumber() + " has deferred item transitions: " + deferred);
        }

        order.observeEvent(event.channelEventAt(), event.channelEventSequence());
        recomputeDerivedStatus(order);
    }

    private SalesOrder findOrCreateOrder(OrderEventPayload event) {
        // channelEventAt/channelEventSequence start at "nothing observed yet"
        // (Instant.EPOCH / null), NOT this event's own values — baking the creating
        // event's sequence in here would make it compare equal to itself in
        // OrderItemTransitionDecision right below and get treated as stale, so the
        // very first event that creates an order would apply to no items at all
        // beyond CREATED. observeEvent() is the only place that should advance these,
        // and only after the event has actually been applied.
        return salesOrderRepository.findByOrganizationIdAndChannelConnectionIdAndChannelOrderNumber(
                        event.organizationId(), event.channelConnectionId(), event.channelOrderNumber())
                .orElseGet(() -> salesOrderRepository.save(new SalesOrder(
                        UUID.randomUUID(), event.organizationId(), event.channelConnectionId(), event.channelOrderNumber(),
                        Instant.EPOCH, null, event.total(), event.currency())));
    }

    private OrderItem createItem(SalesOrder order, UUID variantId, OrderEventPayload.OrderEventItem incoming) {
        OrderItem item = orderItemRepository.save(new OrderItem(
                UUID.randomUUID(), order.getOrganizationId(), order.getId(), variantId,
                incoming.quantity(), incoming.unitPrice(), incoming.vatRate() == null ? java.math.BigDecimal.ZERO : incoming.vatRate()));

        stockLedgerService.recordReservedIncrease(order.getOrganizationId(), variantId, incoming.quantity(), item.getId());
        stockReservationRepository.save(new StockReservation(
                UUID.randomUUID(), order.getOrganizationId(), item.getId(), variantId, incoming.quantity(),
                Instant.now().plus(RESERVATION_HOLD_HOURS, ChronoUnit.HOURS)));

        return item;
    }

    private void applyDecision(SalesOrder order, OrderItem item, OrderItemTransitionDecision.Decision decision,
                                OrderEventPayload.OrderEventItem incoming) {
        switch (decision.action()) {
            case NO_OP, DEFER -> {
                // DEFER's whole-event rollback is handled by the caller after this loop.
            }
            case APPLY -> {
                OrderItemStatus target = incoming.targetStatus();
                applyStockSideEffect(order, item, target);
                item.setStatus(target);
            }
            case ESCALATE -> escalateToOperatorQueue(order, item, decision);
        }
    }

    private void applyStockSideEffect(SalesOrder order, OrderItem item, OrderItemStatus target) {
        Optional<StockReservation> reservation = stockReservationRepository.findByOrderItemId(item.getId());

        switch (target) {
            case PAID -> reservation.ifPresent(StockReservation::clearExpiry);
            case SHIPPED -> {
                stockLedgerService.recordOnHandDecrease(order.getOrganizationId(), item.getVariantId(), item.getQuantity(), item.getId());
                stockLedgerService.recordReservedDecrease(order.getOrganizationId(), item.getVariantId(), item.getQuantity(), item.getId());
                reservation.ifPresent(stockReservationRepository::delete);
            }
            case CANCELLED, PAYMENT_TIMEOUT -> {
                stockLedgerService.recordReservedDecrease(order.getOrganizationId(), item.getVariantId(), item.getQuantity(), item.getId());
                reservation.ifPresent(stockReservationRepository::delete);
            }
            case IN_RETURN -> {
                // Intact/damaged stock disposition is Faz 5 (return flow) — no effect here yet.
            }
            default -> {
                // AWAITING_PAYMENT, PREPARING, DELIVERED, CREATED: no stock counter change.
            }
        }
    }

    private void escalateToOperatorQueue(SalesOrder order, OrderItem item, OrderItemTransitionDecision.Decision decision) {
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (gen_random_uuid(), ?, 'ORDER_ITEM_ESCALATION', ?, ?)
                """,
                order.getOrganizationId(), decision.reason(), item.getId());
        log.warn("Escalated order item {} to operator queue: {}", item.getId(), decision.reason());
    }

    private void recomputeDerivedStatus(SalesOrder order) {
        List<OrderItem> items = orderItemRepository.findBySalesOrderId(order.getId());
        if (items.isEmpty()) {
            return;
        }

        Set<OrderItemStatus> distinct = items.stream().map(OrderItem::getStatus).collect(java.util.stream.Collectors.toSet());
        if (distinct.size() == 1) {
            order.setDerivedStatus(distinct.iterator().next().name());
            return;
        }

        boolean anyCancelled = distinct.contains(OrderItemStatus.CANCELLED);
        if (anyCancelled) {
            order.setDerivedStatus("PARTIALLY_CANCELLED");
            return;
        }

        Optional<OrderItemStatus> lowestMainline = items.stream()
                .map(OrderItem::getStatus)
                .filter(OrderItemStatus::isMainline)
                .min((a, b) -> Integer.compare(a.rank(), b.rank()));

        order.setDerivedStatus(lowestMainline.map(Enum::name).orElse("MIXED"));
    }
}
