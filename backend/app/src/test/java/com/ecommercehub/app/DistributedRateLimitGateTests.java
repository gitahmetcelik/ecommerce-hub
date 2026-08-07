package com.ecommercehub.app;

import com.ecommercehub.app.ratelimit.DbRateLimitBudget;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.ecommercehub.domain.channel.ChannelCircuitBreakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v5 Faz 5 §5.5: "two worker instances running at once, the total calls made to
 * one channel must not exceed its budget." Each instance in production would be a
 * separate JVM; here two independently-constructed {@link DbRateLimitBudget} objects
 * (never sharing an in-memory field) stand in for them, both pointed at the same row
 * in {@code hub.channel_rate_budget} — proving the sharing happens through the
 * database, not accidentally through the test's own object graph.
 *
 * <p>Before Faz 5 this test would have been meaningless: two {@code
 * InMemoryRateLimitBudget} instances share nothing by construction, so the assertion
 * below would have passed for the wrong reason (each getting its own full 100 tokens,
 * 200 total, never "exceeding" its own separate 100). What makes this test actually
 * prove something is that {@link DbRateLimitBudget}'s tokens live in Postgres, so two
 * objects reading/writing the same row is the only way either can succeed at all.
 */
@SpringBootTest
class DistributedRateLimitGateTests extends AbstractTestcontainersTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("systemJdbcTemplate")
    private NamedParameterJdbcTemplate systemJdbcTemplate;

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials)
                VALUES (?, ?, 'MOCK', 'n/a')
                """, channelConnectionId, orgId);
    }

    @Test
    @DisplayName("two DbRateLimitBudget instances for the same connection share one token pool, never doubling it")
    void twoInstancesShareOneTokenPoolAndNeverExceedTheBudget() {
        RateLimitBudget instanceA = new DbRateLimitBudget(systemJdbcTemplate, orgId, channelConnectionId, 100);
        RateLimitBudget instanceB = new DbRateLimitBudget(systemJdbcTemplate, orgId, channelConnectionId, 100);

        int totalAcquired = 0;
        // INTERACTIVE's own share is 50, but idle OPERATIONAL/BACKGROUND capacity
        // legitimately flows up to it too (Plan §9, already proven single-instance by
        // InMemoryRateLimitBudgetTests) — calling tryAcquire(INTERACTIVE) alone can
        // reach the full 100-token budget. Alternate between the two "instances" well
        // past that, exactly the way two worker processes racing for the same channel
        // would.
        for (int i = 0; i < 80; i++) {
            if (instanceA.tryAcquire(BudgetClass.INTERACTIVE)) {
                totalAcquired++;
            }
            if (instanceB.tryAcquire(BudgetClass.INTERACTIVE)) {
                totalAcquired++;
            }
        }

        assertThat(totalAcquired)
                .withFailMessage("Plan §9: the connection's WHOLE budget (100, once idle lower-priority capacity "
                        + "flows up) belongs to the connection, not to each instance separately — two instances "
                        + "must never together acquire more than the one shared pool allows")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("concurrent acquisition from two instances never over-acquires, even under real thread contention")
    void concurrentAcquisitionAcrossTwoInstancesIsSafe() {
        RateLimitBudget instanceA = new DbRateLimitBudget(systemJdbcTemplate, orgId, channelConnectionId, 100);
        RateLimitBudget instanceB = new DbRateLimitBudget(systemJdbcTemplate, orgId, channelConnectionId, 100);

        AtomicInteger acquiredByA = new AtomicInteger();
        AtomicInteger acquiredByB = new AtomicInteger();

        CompletableFuture<Void> threadA = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 60; i++) {
                if (instanceA.tryAcquire(BudgetClass.INTERACTIVE)) {
                    acquiredByA.incrementAndGet();
                }
            }
        });
        CompletableFuture<Void> threadB = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 60; i++) {
                if (instanceB.tryAcquire(BudgetClass.INTERACTIVE)) {
                    acquiredByB.incrementAndGet();
                }
            }
        });
        CompletableFuture.allOf(threadA, threadB).join();

        assertThat(acquiredByA.get() + acquiredByB.get())
                .withFailMessage("FOR UPDATE SKIP LOCKED must serialize concurrent decrements — real thread "
                        + "contention must not let the combined total exceed the connection's whole 100-token "
                        + "budget (INTERACTIVE alone can reach it via fallback into idle OPERATIONAL/BACKGROUND "
                        + "capacity, same as the single-instance case)")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("the channel circuit breaker (already DB-backed) opens on one instance and is honoured by another")
    void circuitBreakerOpenedByOneInstanceIsHonouredByAnother() {
        ChannelCircuitBreakerService instanceA = new ChannelCircuitBreakerService(systemJdbcTemplate, 2, 60);
        ChannelCircuitBreakerService instanceB = new ChannelCircuitBreakerService(systemJdbcTemplate, 2, 60);

        assertThat(instanceB.isCallable(channelConnectionId)).isTrue();

        instanceA.recordFailure(channelConnectionId, "simulated failure 1");
        instanceA.recordFailure(channelConnectionId, "simulated failure 2");

        assertThat(instanceB.isCallable(channelConnectionId))
                .withFailMessage("The circuit breaker's state already lives entirely in hub.channel_connection "
                        + "(Plan Phase 4) — a second instance reading it must see the same open circuit "
                        + "immediately, no separate change needed for Faz 5")
                .isFalse();
    }
}
