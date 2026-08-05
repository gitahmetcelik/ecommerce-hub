package com.ecommercehub.dispatcher;

import com.gorevplatformu.motorcekirdek.GorevDurumu;
import com.gorevplatformu.motorspringstarter.GorevOzeti;
import com.gorevplatformu.motorspringstarter.GorevYonetimServisi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Closes the gap between work_batch.status and the task engine's own status (plan
 * §2.3): a task that reaches a terminal state — or dies into the DLQ — does not
 * itself go back and update the work_batch row that spawned it, so without this
 * sweeper a PROCESSING row would hold its organization's concurrency quota forever.
 */
@Service
public class OrphanSweeperService {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweeperService.class);

    private static final Set<GorevDurumu> TERMINAL = Set.of(
            GorevDurumu.TAMAMLANDI, GorevDurumu.BASARISIZ, GorevDurumu.IPTAL_EDILDI);

    private static final String SELECT_PROCESSING_WITH_TASK_SQL = """
            SELECT id, task_id FROM hub.work_batch
            WHERE status = 'PROCESSING' AND task_id IS NOT NULL
            """;

    private static final String CLOSE_ROW_SQL = """
            UPDATE hub.work_batch
            SET status = :status, updated_at = now(), version = version + 1
            WHERE id = :id AND status = 'PROCESSING'
            """;

    private static final String SELECT_STUCK_ROWS_SQL = """
            SELECT id, organization_id, task_type
            FROM hub.work_batch
            WHERE status = 'PROCESSING'
              AND updated_at < now() - (:timeoutSeconds * :multiplier) * interval '1 second'
            """;

    private static final String ESCALATE_ROW_SQL = """
            UPDATE hub.work_batch
            SET status = 'FAILED', updated_at = now(), version = version + 1
            WHERE id = :id AND status = 'PROCESSING'
            """;

    private static final String INSERT_OPERATOR_QUEUE_SQL = """
            INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
            VALUES (gen_random_uuid(), :organizationId, 'DISPATCH_TIMEOUT', :description, :referenceId)
            """;

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final GorevYonetimServisi gorevYonetimServisi;
    private final DispatcherProperties properties;

    public OrphanSweeperService(NamedParameterJdbcTemplate systemJdbcTemplate,
                                 GorevYonetimServisi gorevYonetimServisi,
                                 DispatcherProperties properties) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.gorevYonetimServisi = gorevYonetimServisi;
        this.properties = properties;
    }

    /** Closes work_batch rows whose engine task already reached a terminal state. */
    public int closeCompletedRows() {
        List<TaskRef> rows = systemJdbcTemplate.query(SELECT_PROCESSING_WITH_TASK_SQL, this::mapTaskRef);
        int closed = 0;
        for (TaskRef row : rows) {
            Optional<GorevOzeti> summary = gorevYonetimServisi.ozet(row.taskId());
            if (summary.isEmpty()) {
                continue;
            }
            GorevDurumu durum = summary.get().durum();
            if (!TERMINAL.contains(durum)) {
                continue;
            }
            String status = durum == GorevDurumu.TAMAMLANDI ? "DONE" : "FAILED";
            int updated = systemJdbcTemplate.update(CLOSE_ROW_SQL, new MapSqlParameterSource()
                    .addValue("id", row.workBatchId())
                    .addValue("status", status));
            closed += updated;
        }
        return closed;
    }

    /**
     * Escalates work_batch rows that have been PROCESSING for longer than
     * processingTimeoutSeconds * orphanTimeoutMultiplier — including rows whose
     * task_id never got set because dispatch itself crashed mid-cycle.
     */
    public int escalateStuckRows() {
        List<StuckRow> rows = systemJdbcTemplate.query(SELECT_STUCK_ROWS_SQL, new MapSqlParameterSource()
                .addValue("timeoutSeconds", properties.getProcessingTimeoutSeconds())
                .addValue("multiplier", properties.getOrphanTimeoutMultiplier()), this::mapStuckRow);

        int escalated = 0;
        for (StuckRow row : rows) {
            int updated = systemJdbcTemplate.update(ESCALATE_ROW_SQL,
                    new MapSqlParameterSource().addValue("id", row.id()));
            if (updated == 0) {
                continue; // another sweeper pass/instance already moved it on
            }
            systemJdbcTemplate.update(INSERT_OPERATOR_QUEUE_SQL, new MapSqlParameterSource()
                    .addValue("organizationId", row.organizationId())
                    .addValue("description", "Dispatch stuck in PROCESSING past timeout for task type " + row.taskType())
                    .addValue("referenceId", row.id()));
            escalated++;
            log.warn("Escalated stuck work_batch row {} (task type {}) to operator queue", row.id(), row.taskType());
        }
        return escalated;
    }

    private TaskRef mapTaskRef(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRef((UUID) rs.getObject("id"), (UUID) rs.getObject("task_id"));
    }

    private StuckRow mapStuckRow(ResultSet rs, int rowNum) throws SQLException {
        return new StuckRow((UUID) rs.getObject("id"), (UUID) rs.getObject("organization_id"), rs.getString("task_type"));
    }

    private record TaskRef(UUID workBatchId, UUID taskId) {
    }

    private record StuckRow(UUID id, UUID organizationId, String taskType) {
    }
}
