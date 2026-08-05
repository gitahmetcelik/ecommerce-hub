package com.ecommercehub.app;

import com.ecommercehub.dispatcher.OrphanSweeperService;
import com.ecommercehub.dispatcher.WorkBatchDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class DispatcherGateTests extends AbstractTestcontainersTest {

    private static final String TASK_TYPE = "dispatch_gate_test_task";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkBatchDispatcher dispatcher;

    @Autowired
    private OrphanSweeperService orphanSweeper;

    private UUID orgAId;
    private UUID orgBId;

    @BeforeEach
    void setUp() {
        orgAId = UUID.randomUUID();
        orgBId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name, task_quota) VALUES (?, ?, ?)", orgAId, "Org A", 20);
        jdbcTemplate.update("INSERT INTO hub.organization (id, name, task_quota) VALUES (?, ?, ?)", orgBId, "Org B", 20);

        // The task type must be pre-registered in the engine's own gorev_tanimlari
        // table (FK constraint) — no domain handler exists yet at Faz 0b, so this
        // is a bare-minimum definition just to prove the dispatcher's own logic.
        jdbcTemplate.update("""
                INSERT INTO motor.gorev_tanimlari (tip, kuyruk)
                VALUES (?, 'default')
                ON CONFLICT (tip) DO NOTHING
                """, TASK_TYPE);
    }

    private void insertWorkBatch(UUID organizationId, String businessKey, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO hub.work_batch
                    (id, organization_id, task_type, task_key, payload, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?)
                """,
                UUID.randomUUID(), organizationId, TASK_TYPE, businessKey, "{}",
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    @Test
    @DisplayName("Dispatcher gate 1: org round-robin — Org B's single row is picked even though Org A queued far more")
    void test1_OrgRoundRobinFairness() {
        Instant base = Instant.now().minusSeconds(60);
        for (int i = 0; i < 20; i++) {
            insertWorkBatch(orgAId, "org-a-item-" + i, base.plus(i, ChronoUnit.MILLIS));
        }
        insertWorkBatch(orgBId, "org-b-item-0", base.plus(1, ChronoUnit.SECONDS));

        int dispatched = dispatcher.dispatchCycle();
        assertThat(dispatched).isGreaterThan(0);

        Integer orgBProcessing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.work_batch WHERE organization_id = ? AND status = 'PROCESSING'",
                Integer.class, orgBId);
        assertThat(orgBProcessing)
                .withFailMessage("Org B's only row must be picked up in the same cycle as Org A's flood, not starved behind it")
                .isEqualTo(1);

        Integer orgAProcessing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.work_batch WHERE organization_id = ? AND status = 'PROCESSING'",
                Integer.class, orgAId);
        assertThat(orgAProcessing)
                .withFailMessage("Per-cycle per-org cap must stop Org A from taking the whole cycle")
                .isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Dispatcher gate 2: dispatched rows get a real engine task with the correct TaskKey-derived idempotency key")
    void test2_DispatchedRowsCreateEngineTasks() {
        insertWorkBatch(orgAId, "single-item", Instant.now());

        dispatcher.dispatchCycle();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, task_id FROM hub.work_batch WHERE organization_id = ?", orgAId);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("task_id")).isNotNull();

        String expectedKey = orgAId + ":" + TASK_TYPE + ":single-item";
        String actualKey = jdbcTemplate.queryForObject(
                "SELECT idempotency_anahtari FROM motor.gorevler WHERE id = ?",
                String.class, (java.util.UUID) row.get("task_id"));
        assertThat(actualKey).isEqualTo(expectedKey);
    }

    @Test
    @DisplayName("Dispatcher gate 3: orphan sweeper closes a work_batch row once its engine task reaches a terminal state")
    void test3_OrphanSweeperClosesCompletedTask() {
        insertWorkBatch(orgAId, "completes-quickly", Instant.now());
        dispatcher.dispatchCycle();

        UUID taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM hub.work_batch WHERE organization_id = ?", UUID.class, orgAId);

        // No worker pipeline is registered for this test task type — simulate the
        // engine having finished it, exactly like a real handler completing would.
        jdbcTemplate.update("UPDATE motor.gorevler SET durum = 'TAMAMLANDI' WHERE id = ?", taskId);

        int closed = orphanSweeper.closeCompletedRows();
        assertThat(closed).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.work_batch WHERE organization_id = ?", String.class, orgAId);
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Dispatcher gate 4: a row stuck in PROCESSING past timeout*multiplier is escalated to the operator queue")
    void test4_StuckRowEscalatesToOperatorQueue() {
        UUID rowId = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        jdbcTemplate.update("""
                INSERT INTO hub.work_batch
                    (id, organization_id, task_type, task_key, payload, status, created_at, updated_at)
                VALUES (?, ?, ?, 'stuck-item', '{}'::jsonb, 'PROCESSING', ?, ?)
                """, rowId, orgAId, TASK_TYPE, Timestamp.from(longAgo), Timestamp.from(longAgo));

        int escalated = orphanSweeper.escalateStuckRows();
        assertThat(escalated).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.work_batch WHERE id = ?", String.class, rowId);
        assertThat(status).isEqualTo("FAILED");

        Integer operatorQueueCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.operator_queue WHERE reference_id = ? AND type = 'DISPATCH_TIMEOUT'",
                Integer.class, rowId);
        assertThat(operatorQueueCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Dispatcher gate 5: the dispatcher's org-spanning query fails outright under hub_app — it only works under hub_system")
    void test5_WrongRoleFailsInsteadOfSilentlyMisbehaving() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET ROLE hub_app;");
                // hub_app has no BYPASSRLS and no org context is set — any query
                // touching an RLS-protected table across organizations must fail,
                // never silently return an empty or partial cross-tenant view.
                assertThatThrownBy(() -> stmt.executeQuery(
                        "SELECT o.id FROM hub.organization o JOIN hub.work_batch wb ON wb.organization_id = o.id"))
                        .satisfies(thrown -> assertThat(thrown.getMessage()).containsAnyOf(
                                "unrecognized configuration parameter \"hub.org_id\"",
                                "invalid input syntax for type uuid"));
            } finally {
                conn.rollback();
            }
        }
    }
}
