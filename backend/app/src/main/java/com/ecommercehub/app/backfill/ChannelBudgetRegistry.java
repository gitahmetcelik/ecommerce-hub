package com.ecommercehub.app.backfill;

import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One RateLimitBudget (plan §9) per channel connection, lazily created. §5's "Kanal
 * API bütçesi" row is explicitly empty pending a real-channel spike that was skipped
 * (Mock-only decision) — this default is a placeholder, not a measured number.
 *
 * <p>Refills every known budget back to its full share on a timer — without this,
 * BACKGROUND's 20% floor is a one-time allowance, not a recurring one, and a
 * multi-page backfill would silently stall the moment it's spent.
 */
@Component
@EnableConfigurationProperties(BackfillProperties.class)
public class ChannelBudgetRegistry {

    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;

    private final Map<UUID, RateLimitBudget> budgets = new ConcurrentHashMap<>();

    public RateLimitBudget forConnection(UUID channelConnectionId) {
        return budgets.computeIfAbsent(channelConnectionId, id -> new RateLimitBudget(DEFAULT_REQUESTS_PER_MINUTE));
    }

    @Scheduled(fixedRateString = "${hub.backfill.refill-period-ms:60000}")
    void refillAll() {
        budgets.values().forEach(RateLimitBudget::refill);
    }
}
