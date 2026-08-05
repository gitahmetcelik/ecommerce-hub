package com.ecommercehub.domain.stock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only legitimate way to change stock.reserved/on_hand/damaged (plan §3/§4.4's
 * eventual ledger-consistency check depends on every counter change having a matching
 * stock_movement row — nothing here changes a counter without one). Locks the stock
 * row with SELECT FOR UPDATE so concurrent adjustments to the same variant serialize
 * instead of losing an update.
 */
@Service
public class StockLedgerService {

    private final StockRepository stockRepository;
    private final StockMovementRepository stockMovementRepository;

    public StockLedgerService(StockRepository stockRepository, StockMovementRepository stockMovementRepository) {
        this.stockRepository = stockRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Transactional
    public void recordReservedIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        adjust(organizationId, variantId, quantity, StockMovementReason.RESERVED_INCREASE, referenceId, Stock::adjustReserved);
    }

    @Transactional
    public void recordReservedDecrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        adjust(organizationId, variantId, -quantity, StockMovementReason.RESERVED_DECREASE, referenceId, Stock::adjustReserved);
    }

    @Transactional
    public void recordOnHandIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        adjust(organizationId, variantId, quantity, StockMovementReason.ON_HAND_INCREASE, referenceId, Stock::adjustOnHand);
    }

    @Transactional
    public void recordOnHandDecrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        adjust(organizationId, variantId, -quantity, StockMovementReason.ON_HAND_DECREASE, referenceId, Stock::adjustOnHand);
    }

    @Transactional
    public void recordDamagedIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        adjust(organizationId, variantId, quantity, StockMovementReason.DAMAGED_INCREASE, referenceId, Stock::adjustDamaged);
    }

    private void adjust(UUID organizationId, UUID variantId, int signedDelta, StockMovementReason reason,
                         UUID referenceId, CounterAdjuster adjuster) {
        Stock stock = stockRepository.findByOrganizationIdAndVariantIdForUpdate(organizationId, variantId)
                .orElseGet(() -> stockRepository.save(new Stock(UUID.randomUUID(), organizationId, variantId)));

        adjuster.apply(stock, signedDelta);

        // The ledger always records the magnitude actually applied, with the reason
        // saying which direction/counter — not a raw signed delta that would force
        // every reader to also know the reason to interpret the sign.
        stockMovementRepository.save(new StockMovement(
                UUID.randomUUID(), organizationId, variantId, Math.abs(signedDelta), reason, referenceId));
    }

    @FunctionalInterface
    private interface CounterAdjuster {
        void apply(Stock stock, int signedDelta);
    }
}
