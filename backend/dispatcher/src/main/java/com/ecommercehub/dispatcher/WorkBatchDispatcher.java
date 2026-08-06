package com.ecommercehub.dispatcher;

import com.ecommercehub.domain.vo.TaskKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gorevplatformu.motorcekirdek.GorevGonderici;
import com.gorevplatformu.motorcekirdek.GorevOpsiyonlari;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The org round-robin dispatcher (Plan §1.2, §2.3). Runs against the hub_system
 * (BYPASSRLS) connection pool — it is the one component allowed to see every
 * organization's pending work in a single query, because fairness across
 * organizations is impossible to compute one tenant at a time.
 *
 * <p>Selection is a single "SELECT ... FOR UPDATE SKIP LOCKED" so concurrent
 * dispatcher instances (or future horizontal scaling) never double-pick a row —
 * each backend just skips whatever another backend already has locked.
 */
@Service
public class WorkBatchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkBatchDispatcher.class);

    /**
     * Ranks PENDING rows within each organization by age (oldest first), caps that
     * rank at both the per-cycle limit and the organization's remaining task_quota
     * headroom (quota minus rows already PROCESSING), and separately caps how many
     * PROCESSING rows a single channel connection may have at once. Interleaving by
     * rank before organization_id is what makes this round-robin instead of
     * first-organization-first: an org with a single pending row is picked in the
     * very first slot no matter how many rows another org has queued behind it.
     */
    private static final String SELECT_AND_LOCK_SQL = """
            WITH org_capacity AS (
                SELECT o.id AS organization_id,
                       GREATEST(o.task_quota - COALESCE(inflight.cnt, 0), 0) AS remaining
                FROM hub.organization o
                LEFT JOIN (
                    SELECT organization_id, count(*) AS cnt
                    FROM hub.work_batch
                    WHERE status = 'PROCESSING'
                    GROUP BY organization_id
                ) inflight ON inflight.organization_id = o.id
            ),
            channel_capacity AS (
                SELECT channel_connection_id, count(*) AS cnt
                FROM hub.work_batch
                WHERE status = 'PROCESSING' AND channel_connection_id IS NOT NULL
                GROUP BY channel_connection_id
            ),
            eligible AS (
                SELECT wb.id,
                       wb.organization_id,
                       ROW_NUMBER() OVER (PARTITION BY wb.organization_id ORDER BY wb.created_at) AS org_rank,
                       oc.remaining
                FROM hub.work_batch wb
                JOIN org_capacity oc ON oc.organization_id = wb.organization_id AND oc.remaining > 0
                LEFT JOIN channel_capacity cc ON cc.channel_connection_id = wb.channel_connection_id
                WHERE wb.status = 'PENDING'
                  AND (wb.channel_connection_id IS NULL OR COALESCE(cc.cnt, 0) < :channelConcurrencyLimit)
            )
            SELECT wb.id, wb.organization_id, wb.channel_connection_id, wb.task_type, wb.task_key, wb.payload, wb.trace_id
            FROM hub.work_batch wb
            JOIN eligible e ON e.id = wb.id
            WHERE e.org_rank <= LEAST(:perOrgPerCycle, e.remaining)
            ORDER BY e.org_rank, wb.organization_id
            LIMIT :totalPerCycle
            FOR UPDATE OF wb SKIP LOCKED
            """;

    private static final String MARK_PROCESSING_SQL = """
            UPDATE hub.work_batch
            SET status = 'PROCESSING', task_id = :taskId, updated_at = now(), version = version + 1
            WHERE id = :id
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE hub.work_batch
            SET status = 'FAILED', updated_at = now(), version = version + 1
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final GorevGonderici gorevGonderici;
    private final ObjectMapper objectMapper;
    private final DispatcherProperties properties;

    public WorkBatchDispatcher(NamedParameterJdbcTemplate systemJdbcTemplate,
                                GorevGonderici gorevGonderici,
                                ObjectMapper objectMapper,
                                DispatcherProperties properties) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.gorevGonderici = gorevGonderici;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Runs one dispatch cycle: selects and locks eligible rows, submits each to the
     * task engine, and marks it PROCESSING with the returned task id. Returns the
     * number of rows dispatched.
     */
    public int dispatchCycle() {
        MapSqlParameterSource selectParams = new MapSqlParameterSource()
                .addValue("perOrgPerCycle", properties.getPerOrgPerCycle())
                .addValue("totalPerCycle", properties.getTotalPerCycle())
                .addValue("channelConcurrencyLimit", properties.getChannelConcurrencyLimit());

        List<WorkBatchRow> rows = systemJdbcTemplate.query(SELECT_AND_LOCK_SQL, selectParams, this::mapRow);

        for (WorkBatchRow row : rows) {
            dispatchOne(row);
        }

        if (!rows.isEmpty()) {
            log.info("Dispatch cycle picked up {} work_batch row(s)", rows.size());
        }
        return rows.size();
    }

    private void dispatchOne(WorkBatchRow row) {
        TaskKey taskKey = new TaskKey(row.organizationId(), row.taskType(), row.taskKey());
        try {
            Map<String, Object> payload = row.payloadJson() == null
                    ? new HashMap<>()
                    : objectMapper.readValue(row.payloadJson(), Map.class);

            UUID taskId = gorevGonderici.gonder(row.taskType(), payload,
                    new GorevOpsiyonlari(taskKey.asText(), null, null));

            systemJdbcTemplate.update(MARK_PROCESSING_SQL, new MapSqlParameterSource()
                    .addValue("id", row.id())
                    .addValue("taskId", taskId));
        } catch (Exception e) {
            // Submission itself failed (not a task-execution failure) — this row never
            // reached the engine, so there is nothing for the orphan sweeper to correlate
            // it with. Fail it directly rather than leaving it PENDING forever or
            // PROCESSING with no task_id.
            log.error("Failed to dispatch work_batch row {} (task type {})", row.id(), row.taskType(), e);
            systemJdbcTemplate.update(MARK_FAILED_SQL, new MapSqlParameterSource().addValue("id", row.id()));
        }
    }

    private WorkBatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkBatchRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("organization_id"),
                (UUID) rs.getObject("channel_connection_id"),
                rs.getString("task_type"),
                rs.getString("task_key"),
                rs.getString("payload"),
                rs.getString("trace_id")
        );
    }

    private record WorkBatchRow(UUID id, UUID organizationId, UUID channelConnectionId,
                                 String taskType, String taskKey, String payloadJson, String traceId) {
    }
}
