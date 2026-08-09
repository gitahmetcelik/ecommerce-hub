package com.ecommercehub.domain.stock;

import com.ecommercehub.domain.push.ChannelPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only legitimate way to change stock.reserved/on_hand/damaged (Plan §3/§4.4's
 * eventual ledger-consistency check depends on every counter change having a matching
 * stock_movement row — nothing here changes a counter without one). Locks the stock
 * row with SELECT FOR UPDATE so concurrent adjustments to the same variant serialize
 * instead of losing an update.
 *
 * <p><b>Phase 4 adds two things to every adjustment.</b> First, counters are clamped so
 * reserved can never exceed on_hand and nothing can go negative — a reservation that
 * does not fit is an oversell, recorded in oversell_event rather than absorbed by
 * letting the counter go negative (Plan Phase 4 gate: hub stock never goes negative and
 * the oversell is recorded). Second, the resulting availability is queued for every
 * channel that sells the variant, in this same transaction.
 *
 * <p>The ledger always records the quantity <em>actually applied</em>, never the
 * requested one. That is what keeps the nightly recompute (Plan §11) able to derive
 * the stock row from its movements — if a clamped request wrote its full magnitude,
 * the consistency check would report a permanent phantom discrepancy on every oversell.
 */
@Service
public class StockLedgerService {

    private static final Logger log = LoggerFactory.getLogger(StockLedgerService.class);

    private final StockRepository stockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ChannelPushService channelPushService;
    private final JdbcTemplate jdbcTemplate;

    public StockLedgerService(StockRepository stockRepository, StockMovementRepository stockMovementRepository,
                               ChannelPushService channelPushService, JdbcTemplate jdbcTemplate) {
        this.stockRepository = stockRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.channelPushService = channelPushService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param channelConnectionId the channel the demand came from, so an oversell can
     *                            be attributed to it. Null only for reservations with no
     *                            channel origin (internal adjustments), which are still
     *                            clamped but recorded as a warning instead of a row.
     * @return the quantity actually reserved, which is less than requested on an oversell
     */
    @Transactional
    public int recordReservedIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId,
                                       UUID channelConnectionId) {
        Stock stock = lockOrCreate(organizationId, variantId);

        int headroom = Math.max(0, stock.getOnHand() - stock.getReserved());
        int applied = Math.min(quantity, headroom);

        if (applied < quantity) {
            recordOversell(organizationId, channelConnectionId, variantId, quantity, headroom);
        }

        apply(stock, applied, StockMovementReason.RESERVED_INCREASE, referenceId, Stock::adjustReserved);
        return applied;
    }

    @Transactional
    public void recordReservedDecrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        Stock stock = lockOrCreate(organizationId, variantId);
        int applied = Math.min(quantity, stock.getReserved());
        apply(stock, -applied, StockMovementReason.RESERVED_DECREASE, referenceId, Stock::adjustReserved);
    }

    @Transactional
    public void recordOnHandIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        Stock stock = lockOrCreate(organizationId, variantId);
        apply(stock, quantity, StockMovementReason.ON_HAND_INCREASE, referenceId, Stock::adjustOnHand);
    }

    @Transactional
    public void recordOnHandDecrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        Stock stock = lockOrCreate(organizationId, variantId);
        int applied = Math.min(quantity, stock.getOnHand());

        if (applied < quantity) {
            // Shipping units we never recorded as on hand. The oversell itself was already
            // recorded when the reservation was clamped; letting on_hand go negative here
            // would only invent inventory that never existed, so it is clamped and said
            // out loud instead of disappearing into the counter.
            log.warn("Shipment of {} unit(s) of variant {} exceeded on_hand ({}) — clamped, ledger records {}",
                    quantity, variantId, stock.getOnHand(), applied);
        }

        apply(stock, -applied, StockMovementReason.ON_HAND_DECREASE, referenceId, Stock::adjustOnHand);
    }

    @Transactional
    public void recordDamagedIncrease(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        Stock stock = lockOrCreate(organizationId, variantId);
        apply(stock, quantity, StockMovementReason.DAMAGED_INCREASE, referenceId, Stock::adjustDamaged);
    }

    /**
     * Receiving new goods. Separate from {@link #recordOnHandIncrease} only in intent —
     * both write the same reason, because the ledger's job is to explain the counter,
     * not to catalogue every business motive behind it.
     */
    @Transactional
    public void recordSupply(UUID organizationId, UUID variantId, int quantity, UUID referenceId) {
        recordOnHandIncrease(organizationId, variantId, quantity, referenceId);
    }

    /**
     * Plan v5 §7.2 point 3 / §U5: an operator setting on_hand to a new absolute value,
     * not a delta — the screen shows a count, not a movement. Written as ON_HAND_INCREASE/
     * DECREASE, exactly like every other on_hand change (Plan §7.5: the nightly ledger
     * replay in {@link StockConsistencyService} only knows those two reasons; inventing a
     * third would make every manual correction a permanent phantom discrepancy). What
     * makes it a manual correction is carried in the ledger row's own adjustmentReason/
     * note/actor columns (V1008), not in a different {@code reason}.
     *
     * @param expectedOnHand what the operator's screen showed when they started — if the
     *                       row has moved since, this throws {@link StockAdjustmentConflictException}
     *                       rather than silently applying the new value's delta on top of
     *                       a number nobody looked at (Plan §7.5's optimistic-lock gate)
     */
    @Transactional
    public void recordManualAdjustment(UUID organizationId, UUID variantId, int expectedOnHand, int newOnHand,
                                        StockAdjustmentReason reason, String note, UUID actorUserId, UUID referenceId) {
        Stock stock = lockOrCreate(organizationId, variantId);
        if (stock.getOnHand() != expectedOnHand) {
            throw new StockAdjustmentConflictException(stock.getOnHand());
        }

        int delta = newOnHand - expectedOnHand;
        StockMovementReason movementReason = delta >= 0 ? StockMovementReason.ON_HAND_INCREASE : StockMovementReason.ON_HAND_DECREASE;
        apply(stock, delta, movementReason, referenceId, Stock::adjustOnHand, reason, note, actorUserId);
    }

    private Stock lockOrCreate(UUID organizationId, UUID variantId) {
        return stockRepository.findByOrganizationIdAndVariantIdForUpdate(organizationId, variantId)
                .orElseGet(() -> stockRepository.saveAndFlush(new Stock(UUID.randomUUID(), organizationId, variantId)));
    }

    private void apply(Stock stock, int signedDelta, StockMovementReason reason, UUID referenceId,
                        CounterAdjuster adjuster) {
        apply(stock, signedDelta, reason, referenceId, adjuster, null, null, null);
    }

    private void apply(Stock stock, int signedDelta, StockMovementReason reason, UUID referenceId,
                        CounterAdjuster adjuster, StockAdjustmentReason adjustmentReason, String note, UUID actorUserId) {
        adjuster.apply(stock, signedDelta);

        if (signedDelta != 0) {
            // The ledger always records the magnitude actually applied, with the reason
            // saying which direction/counter — not a raw signed delta that would force
            // every reader to also know the reason to interpret the sign.
            stockMovementRepository.save(new StockMovement(
                    UUID.randomUUID(), stock.getOrganizationId(), stock.getVariantId(),
                    Math.abs(signedDelta), reason, referenceId, adjustmentReason, note, actorUserId));
        }

        // Queued even when the delta was clamped to zero: an oversell means the channel
        // is advertising stock we do not have, so it needs the corrected number more
        // urgently than usual, not less. ChannelPushStore.upsert drops it if the value
        // genuinely did not move.
        channelPushService.enqueueStockPush(stock.getOrganizationId(), stock.getVariantId(),
                stock.getOnHand(), stock.getReserved());
    }

    private void recordOversell(UUID organizationId, UUID channelConnectionId, UUID variantId,
                                 int requested, int available) {
        if (channelConnectionId == null) {
            log.warn("Reservation of {} for variant {} clamped to {} with no channel to attribute it to",
                    requested, variantId, available);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO hub.oversell_event
                    (id, organization_id, channel_connection_id, variant_id, requested, available)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)
                """, organizationId, channelConnectionId, variantId, requested, available);
        log.warn("Oversell on variant {} via channel {}: requested {}, only {} available",
                variantId, channelConnectionId, requested, available);
    }

    @FunctionalInterface
    private interface CounterAdjuster {
        void apply(Stock stock, int signedDelta);
    }
}
