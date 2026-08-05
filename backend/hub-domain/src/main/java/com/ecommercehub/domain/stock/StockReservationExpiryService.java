package com.ecommercehub.domain.stock;

import com.ecommercehub.domain.order.OrderItem;
import com.ecommercehub.domain.order.OrderItemRepository;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * plan §3 rezervasyon semantiği: "Rezervasyon süresi doldu: rezerve -n, sipariş
 * ODEME_ZAMAN_ASIMI." A reservation past its expiry only means anything if payment
 * never arrived in the meantime — if it did, PAID already cleared expiresAt (plan
 * §3: "Ödeme onaylandı: son_gecerlilik kaldırılır"), so it never shows up in this
 * sweep's query in the first place. No transition-decision machinery needed here:
 * an expired reservation whose item is still pre-payment mainline can only mean
 * PAYMENT_TIMEOUT is legitimate.
 */
@Service
public class StockReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationExpiryService.class);

    private final StockReservationRepository stockReservationRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockLedgerService stockLedgerService;
    private final TenantContextService tenantContextService;

    public StockReservationExpiryService(StockReservationRepository stockReservationRepository,
                                          OrderItemRepository orderItemRepository,
                                          StockLedgerService stockLedgerService,
                                          TenantContextService tenantContextService) {
        this.stockReservationRepository = stockReservationRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockLedgerService = stockLedgerService;
        this.tenantContextService = tenantContextService;
    }

    /**
     * Releases every reservation past its expiry for one organization and times out
     * the order item it belongs to. Sets its own RLS context (plan §3c) — callers
     * enumerate organizations cross-org via hub_system, then call this once per org,
     * each in its own transaction.
     */
    @Transactional
    public int releaseExpiredReservations(UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        List<StockReservation> expired = stockReservationRepository.findByExpiresAtNotNullAndExpiresAtBefore(Instant.now());

        int released = 0;
        for (StockReservation reservation : expired) {
            Optional<OrderItem> maybeItem = orderItemRepository.findById(reservation.getOrderItemId());
            if (maybeItem.isEmpty()) {
                stockReservationRepository.delete(reservation);
                continue;
            }

            OrderItem item = maybeItem.get();
            if (item.getStatus() != OrderItemStatus.CREATED && item.getStatus() != OrderItemStatus.AWAITING_PAYMENT) {
                // Already moved on by some other event (e.g. cancelled) between the
                // query and now — nothing to time out, just drop the stale reservation row.
                stockReservationRepository.delete(reservation);
                continue;
            }

            stockLedgerService.recordReservedDecrease(item.getOrganizationId(), item.getVariantId(),
                    reservation.getQuantity(), item.getId());
            item.setStatus(OrderItemStatus.PAYMENT_TIMEOUT);
            stockReservationRepository.delete(reservation);
            released++;
            log.info("Reservation for order item {} expired — item timed out, {} unit(s) released", item.getId(), reservation.getQuantity());
        }
        return released;
    }
}
