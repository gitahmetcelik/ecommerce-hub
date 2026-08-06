package com.ecommercehub.domain.push;

import com.ecommercehub.domain.stock.ChannelAvailability;
import com.ecommercehub.domain.stock.StockAvailabilityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns "this variant's stock changed" into one coalesced push row per channel that
 * sells it.
 *
 * <p>Runs in the caller's transaction by design: the stock movement and the push
 * intent commit together or not at all. That is the same transactional-outbox
 * guarantee Plan §1.6 pins the engine to — a committed stock change with no queued
 * push would leave the channel permanently stale, and a queued push with no committed
 * change would advertise a quantity we never had.
 */
@Service
public class ChannelPushService {

    public static final String TYPE_STOCK = "STOCK";

    private final StockAvailabilityService stockAvailabilityService;
    private final ChannelPushStore pushStore;
    private final ObjectMapper objectMapper;

    public ChannelPushService(StockAvailabilityService stockAvailabilityService, ChannelPushStore pushStore,
                               ObjectMapper objectMapper) {
        this.stockAvailabilityService = stockAvailabilityService;
        this.pushStore = pushStore;
        this.objectMapper = objectMapper;
    }

    /** Enqueues from counters the caller already holds (post-adjustment, pre-flush). */
    @Transactional
    public void enqueueStockPush(UUID organizationId, UUID variantId, int onHand, int reserved) {
        enqueue(organizationId, variantId, stockAvailabilityService.computeFor(organizationId, variantId, onHand, reserved));
    }

    /** Enqueues from whatever the stock row currently says — used by reconcile, not by the ledger. */
    @Transactional
    public void enqueueStockPush(UUID organizationId, UUID variantId) {
        enqueue(organizationId, variantId, stockAvailabilityService.computeFor(organizationId, variantId));
    }

    private void enqueue(UUID organizationId, UUID variantId, List<ChannelAvailability> availabilities) {
        for (ChannelAvailability availability : availabilities) {
            pushStore.upsert(organizationId, availability.channelConnectionId(), variantId,
                    TYPE_STOCK, toTargetValueJson(availability));
        }
    }

    private String toTargetValueJson(ChannelAvailability availability) {
        // LinkedHashMap: stable key order keeps the jsonb "did the value actually change"
        // comparison in ChannelPushStore.upsert honest and the stored JSON diffable by eye.
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("channelVariantId", availability.channelVariantId());
        value.put("sku", availability.sku());
        value.put("quantity", availability.quantity());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push target value", e);
        }
    }
}
