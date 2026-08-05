package com.ecommercehub.app;

import com.ecommercehub.dispatcher.WorkBatchDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §4.2: "tekrar çalışması gereken her görevde is_anahtari bir nesil/pencere
 * bileşeni içermek zorundadır" — a repeatable task type's business key must fold in
 * whatever changes between legitimate repeats (a window start, a generation counter),
 * or every subsequent attempt collapses into the first one forever. This exercises
 * the actual mechanism the dispatcher relies on: the engine's own
 * findByIdempotencyAnahtari-before-insert check (verified by decompiling
 * GorevGondericiImpl — not something this repo's code controls, so it's worth
 * pinning down with a real test rather than trusting the plan's description alone).
 */
@SpringBootTest
public class TaskKeyIdempotencyGateTests extends AbstractTestcontainersTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkBatchDispatcher dispatcher;

    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name, task_quota) VALUES (?, ?, ?)", orgId, "Org", 20);
        jdbcTemplate.update(
                "INSERT INTO motor.gorev_tanimlari (tip, kuyruk) VALUES ('idempotency_gate_test_task', 'default') ON CONFLICT (tip) DO NOTHING");
    }

    private void insertWorkBatch(String taskKey, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO hub.work_batch (id, organization_id, task_type, task_key, payload, status, created_at, updated_at)
                VALUES (?, ?, 'idempotency_gate_test_task', ?, '{}'::jsonb, 'PENDING', ?, ?)
                """, UUID.randomUUID(), orgId, taskKey, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    @Test
    @DisplayName("Same business key twice collapses into one engine task, not two")
    void sameBusinessKeyTwiceCollapsesIntoOneEngineTask() {
        Instant base = Instant.now();
        insertWorkBatch("same-window", base);
        insertWorkBatch("same-window", base.plusMillis(1));

        dispatcher.dispatchCycle();

        List<UUID> taskIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT task_id FROM hub.work_batch WHERE organization_id = ?", UUID.class, orgId);
        assertThat(taskIds)
                .withFailMessage("Both rows must resolve to the SAME engine task — that's what makes the key idempotent")
                .hasSize(1);

        String expectedKey = orgId + ":idempotency_gate_test_task:same-window";
        Integer engineTaskCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM motor.gorevler WHERE idempotency_anahtari = ?", Integer.class, expectedKey);
        assertThat(engineTaskCount).isEqualTo(1);
    }

    @Test
    @DisplayName("A different window/generation in the business key produces a distinct engine task")
    void differentWindowProducesADistinctEngineTask() {
        Instant base = Instant.now();
        insertWorkBatch("window-1", base);
        insertWorkBatch("window-2", base.plusMillis(1));

        dispatcher.dispatchCycle();

        List<UUID> taskIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT task_id FROM hub.work_batch WHERE organization_id = ?", UUID.class, orgId);
        assertThat(taskIds)
                .withFailMessage("Different windows are legitimately different units of work — coalescing them would silently drop one")
                .hasSize(2);
    }
}
