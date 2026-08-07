package com.ecommercehub.app.backfill;

import com.ecommercehub.app.ratelimit.DbRateLimitBudget;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One RateLimitBudget (Plan §9) per channel connection, lazily created and cached
 * per-instance. §5's "Kanal API budget" row is a measured number as of Plan v5 Faz 4
 * (a real Shopify spike) for that one channel — this default remains a placeholder for
 * every other channel type until each gets its own spike.
 *
 * <p>Plan v5 Faz 5: {@link RateLimitBudget} moved from an in-memory implementation to
 * {@link DbRateLimitBudget} (hub.channel_rate_budget) — the cache here is now just a
 * per-instance handle to that shared row, not the state itself, so two worker
 * processes calling {@link #forConnection} for the same connection get two objects
 * that both read/write the same tokens rather than two independent budgets.
 *
 * <p>{@code @Profile("worker")}: refilling on a timer only makes sense where the
 * scheduler runs — see {@link com.ecommercehub.app.reconcile.ReconcileScheduler}'s
 * javadoc note on the same split for why "api" carries no sweepers at all.
 */
@Component
@Profile("worker")
@EnableConfigurationProperties(BackfillProperties.class)
public class ChannelBudgetRegistry {

    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final Map<UUID, RateLimitBudget> budgets = new ConcurrentHashMap<>();

    public ChannelBudgetRegistry(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate) {
        this.systemJdbcTemplate = systemJdbcTemplate;
    }

    public RateLimitBudget forConnection(UUID organizationId, UUID channelConnectionId) {
        return budgets.computeIfAbsent(channelConnectionId, id ->
                new DbRateLimitBudget(systemJdbcTemplate, organizationId, id, DEFAULT_REQUESTS_PER_MINUTE));
    }

    @Scheduled(fixedRateString = "${hub.backfill.refill-period-ms:60000}")
    void refillAll() {
        budgets.values().forEach(RateLimitBudget::refill);
    }
}
