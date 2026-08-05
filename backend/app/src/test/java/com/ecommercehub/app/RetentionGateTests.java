package com.ecommercehub.app;

import com.ecommercehub.app.retention.MotorRetentionService;
import com.ecommercehub.app.retention.RawEventPartitionMaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RetentionGateTests extends AbstractTestcontainersTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RawEventPartitionMaintenanceService partitionMaintenanceService;

    @Autowired
    private MotorRetentionService motorRetentionService;

    @Test
    @DisplayName("Retention gate 1: upcoming raw_event partitions are created and RLS-protected")
    void test1_UpcomingPartitionsAreCreatedAndProtected() {
        partitionMaintenanceService.ensureUpcomingPartitions();

        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        String partitionName = String.format("raw_event_y%d_m%02d", current.getYear(), current.getMonthValue());

        Integer relCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'hub' AND c.relname = ?", Integer.class, partitionName);
        assertThat(relCount).isEqualTo(1);

        Integer policyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE schemaname = 'hub' AND tablename = ?",
                Integer.class, partitionName);
        assertThat(policyCount)
                .withFailMessage("A newly created partition must carry the same org_isolation policy as every other hub table")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Retention gate 2: partitions past the retention window are dropped, current ones are not")
    void test2_ExpiredPartitionsAreDroppedCurrentOnesSurvive() {
        YearMonth old = YearMonth.now(ZoneOffset.UTC).minusMonths(4);
        String oldPartitionName = String.format("raw_event_y%d_m%02d", old.getYear(), old.getMonthValue());
        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS hub.%s PARTITION OF hub.raw_event FOR VALUES FROM ('%s') TO ('%s')",
                oldPartitionName, old.atDay(1), old.plusMonths(1).atDay(1)));

        partitionMaintenanceService.ensureUpcomingPartitions();
        partitionMaintenanceService.dropExpiredPartitions();

        Integer oldPartitionExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'hub' AND c.relname = ?", Integer.class, oldPartitionName);
        assertThat(oldPartitionExists).isEqualTo(0);

        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        String currentPartitionName = String.format("raw_event_y%d_m%02d", current.getYear(), current.getMonthValue());
        Integer currentPartitionExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'hub' AND c.relname = ?", Integer.class, currentPartitionName);
        assertThat(currentPartitionExists).isEqualTo(1);
    }

    @Test
    @DisplayName("Retention gate 3: an old completed engine task and its attempts are purged in FK-safe order")
    void test3_OldCompletedTaskIsPurged() {
        String taskType = "retention_gate_test_task";
        jdbcTemplate.update("INSERT INTO motor.gorev_tanimlari (tip, kuyruk) VALUES (?, 'default') ON CONFLICT (tip) DO NOTHING", taskType);

        UUID taskId = UUID.randomUUID();
        Timestamp longAgo = Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS));
        jdbcTemplate.update("""
                INSERT INTO motor.gorevler (id, tip, payload, durum, idempotency_anahtari, guncellenme)
                VALUES (?, ?, '{}'::jsonb, 'TAMAMLANDI', ?, ?)
                """, taskId, taskType, "retention-test:" + taskId, longAgo);
        jdbcTemplate.update("""
                INSERT INTO motor.gorev_denemeleri (id, gorev_id, deneme_no, durum, worker_kimlik)
                VALUES (?, ?, 1, 'TAMAMLANDI', 'test-worker')
                """, UUID.randomUUID(), taskId);

        int deleted = motorRetentionService.purgeCompletedTasks();
        assertThat(deleted).isGreaterThanOrEqualTo(1);

        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM motor.gorevler WHERE id = ?", Integer.class, taskId);
        assertThat(taskCount).isEqualTo(0);

        Integer attemptCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM motor.gorev_denemeleri WHERE gorev_id = ?", Integer.class, taskId);
        assertThat(attemptCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Retention gate 4: a recently completed task is left alone")
    void test4_RecentCompletedTaskSurvives() {
        String taskType = "retention_gate_test_task";
        jdbcTemplate.update("INSERT INTO motor.gorev_tanimlari (tip, kuyruk) VALUES (?, 'default') ON CONFLICT (tip) DO NOTHING", taskType);

        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO motor.gorevler (id, tip, payload, durum, idempotency_anahtari, guncellenme)
                VALUES (?, ?, '{}'::jsonb, 'TAMAMLANDI', ?, now())
                """, taskId, taskType, "retention-test:" + taskId);

        motorRetentionService.purgeCompletedTasks();

        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM motor.gorevler WHERE id = ?", Integer.class, taskId);
        assertThat(taskCount).isEqualTo(1);
    }
}
