package com.ecommercehub.app.ratelimit;

import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plan v5 Faz 5 §5.3: {@code hub.channel_rate_budget}-backed {@link RateLimitBudget} —
 * every worker instance sharing one channel connection's tokens through the same rows,
 * instead of each getting its own in-memory copy of the full budget.
 *
 * <p>Runs on the hub_system (BYPASSRLS) pool, the same choice {@link
 * com.ecommercehub.domain.channel.ChannelCircuitBreakerService} already made and for
 * the same reason: this is cross-cutting infrastructure bookkeeping called from
 * schedulers and send paths that do not necessarily have the caller's tenant context
 * set on the current transaction, not tenant-scoped business data a user reads directly.
 *
 * <p>Token acquisition is a single {@code UPDATE ... WHERE ... FOR UPDATE SKIP LOCKED}
 * statement (same idiom as {@code ChannelPushStore.CLAIM_SQL} and the dispatcher's
 * work_batch selection) — two workers racing for the same connection's last token never
 * double-spend it, and neither blocks waiting for the other; the loser simply sees zero
 * rows updated and falls back to the next lower-priority class, exactly like the
 * in-memory implementation's own fallback did.
 */
public class DbRateLimitBudget implements RateLimitBudget {

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final UUID organizationId;
    private final UUID channelConnectionId;
    private final int totalCapacityPerPeriod;

    public DbRateLimitBudget(NamedParameterJdbcTemplate systemJdbcTemplate, UUID organizationId,
                              UUID channelConnectionId, int totalCapacityPerPeriod) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.organizationId = organizationId;
        this.channelConnectionId = channelConnectionId;
        this.totalCapacityPerPeriod = totalCapacityPerPeriod;
        seedRowsIfAbsent();
    }

    /**
     * Idempotent — safe to call from every worker instance's first use of this
     * connection. {@code ON CONFLICT DO NOTHING} means whichever instance gets there
     * first wins and nobody else's insert resets an already-in-progress budget.
     */
    private void seedRowsIfAbsent() {
        for (BudgetClass budgetClass : BudgetClass.values()) {
            systemJdbcTemplate.update("""
                    INSERT INTO hub.channel_rate_budget (channel_connection_id, budget_class, tokens, organization_id)
                    VALUES (:conn, :class, :tokens, :org)
                    ON CONFLICT (channel_connection_id, budget_class) DO NOTHING
                    """, params(budgetClass).addValue("tokens", capacityFor(budgetClass)));
        }
    }

    @Override
    public boolean tryAcquire(BudgetClass requested) {
        if (requested == BudgetClass.BACKGROUND && isBackedOff()) {
            return false;
        }
        if (acquireFrom(requested)) {
            return true;
        }
        for (BudgetClass lowerPriority : lowerPriorityThan(requested)) {
            if (acquireFrom(lowerPriority)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBackedOff() {
        Boolean backedOff = systemJdbcTemplate.queryForObject("""
                SELECT backoff_until IS NOT NULL AND backoff_until > now()
                FROM hub.channel_rate_budget WHERE channel_connection_id = :conn AND budget_class = :class
                """, params(BudgetClass.BACKGROUND), Boolean.class);
        return Boolean.TRUE.equals(backedOff);
    }

    private boolean acquireFrom(BudgetClass budgetClass) {
        List<Integer> updated = systemJdbcTemplate.query("""
                UPDATE hub.channel_rate_budget
                SET tokens = tokens - 1
                WHERE (channel_connection_id, budget_class) IN (
                    SELECT channel_connection_id, budget_class FROM hub.channel_rate_budget
                    WHERE channel_connection_id = :conn AND budget_class = :class AND tokens > 0
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING tokens
                """, params(budgetClass), (rs, rowNum) -> rs.getInt("tokens"));
        return !updated.isEmpty();
    }

    @Override
    public void reportRateLimited(BudgetClass requested, Duration backoff) {
        if (requested != BudgetClass.BACKGROUND) {
            return;
        }
        systemJdbcTemplate.update("""
                UPDATE hub.channel_rate_budget SET backoff_until = :until
                WHERE channel_connection_id = :conn AND budget_class = :class
                """, params(BudgetClass.BACKGROUND).addValue("until", Timestamp.from(Instant.now().plus(backoff))));
    }

    @Override
    public void refill() {
        for (BudgetClass budgetClass : BudgetClass.values()) {
            systemJdbcTemplate.update("""
                    UPDATE hub.channel_rate_budget SET tokens = :tokens, refilled_at = now()
                    WHERE channel_connection_id = :conn AND budget_class = :class
                    """, params(budgetClass).addValue("tokens", capacityFor(budgetClass)));
        }
    }

    @Override
    public int remaining(BudgetClass budgetClass) {
        Integer tokens = systemJdbcTemplate.queryForObject("""
                SELECT tokens FROM hub.channel_rate_budget WHERE channel_connection_id = :conn AND budget_class = :class
                """, params(budgetClass), Integer.class);
        return tokens == null ? 0 : tokens;
    }

    private int capacityFor(BudgetClass budgetClass) {
        return (int) Math.floor(totalCapacityPerPeriod * budgetClass.share());
    }

    private MapSqlParameterSource params(BudgetClass budgetClass) {
        return new MapSqlParameterSource()
                .addValue("conn", channelConnectionId)
                .addValue("class", budgetClass.name())
                .addValue("org", organizationId);
    }

    private static List<BudgetClass> lowerPriorityThan(BudgetClass budgetClass) {
        BudgetClass[] values = BudgetClass.values();
        List<BudgetClass> lower = new ArrayList<>();
        for (int i = budgetClass.ordinal() + 1; i < values.length; i++) {
            lower.add(values[i]);
        }
        return lower;
    }
}
