package com.ecommercehub.app.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cleans up the task engine's own tables — verified to have no built-in retention
 * (plan §1.3: a source-wide grep for temizle|retention|arsiv|purge|cleanup returned
 * nothing). Left alone, gorevler/gorev_denemeleri/giden_mesajlar grow forever, and
 * gorevler.idempotency_anahtari is a global UNIQUE column with no expiry — every task
 * key that has ever run stays reserved permanently.
 *
 * <p>Only TAMAMLANDI (completed) tasks are purged, per plan §4.4; BASARISIZ/DLQ'd tasks
 * are left for operator review. Deletion follows the FK chain
 * (gorev_denemeleri, giden_mesajlar, olu_mektup_kutusu → gorevler) so a completed task's
 * children are always gone before the parent row.
 */
@Service
public class MotorRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MotorRetentionService.class);

    private static final String SELECT_ELIGIBLE_TYPES_SQL =
            "SELECT DISTINCT tip FROM motor.gorevler WHERE durum = 'TAMAMLANDI'";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final RetentionProperties properties;

    public MotorRetentionService(JdbcTemplate jdbcTemplate, RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.properties = properties;
    }

    /** Purges completed engine tasks older than their type's retention window. Returns rows deleted. */
    public int purgeCompletedTasks() {
        List<String> taskTypes = jdbcTemplate.queryForList(SELECT_ELIGIBLE_TYPES_SQL, String.class);
        int totalDeleted = 0;
        for (String taskType : taskTypes) {
            totalDeleted += purgeForType(taskType);
        }
        return totalDeleted;
    }

    private int purgeForType(String taskType) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tip", taskType)
                .addValue("days", properties.retentionDaysFor(taskType));

        String eligibleIdsSubquery = """
                SELECT id FROM motor.gorevler
                WHERE tip = :tip AND durum = 'TAMAMLANDI'
                  AND guncellenme < now() - (:days * interval '1 day')
                """;

        namedJdbcTemplate.update(
                "DELETE FROM motor.gorev_denemeleri WHERE gorev_id IN (" + eligibleIdsSubquery + ")", params);
        namedJdbcTemplate.update(
                "DELETE FROM motor.giden_mesajlar WHERE gorev_id IN (" + eligibleIdsSubquery + ")", params);
        namedJdbcTemplate.update(
                "DELETE FROM motor.olu_mektup_kutusu WHERE gorev_id IN (" + eligibleIdsSubquery + ")", params);
        int deleted = namedJdbcTemplate.update(
                "DELETE FROM motor.gorevler WHERE tip = :tip AND durum = 'TAMAMLANDI' " +
                "AND guncellenme < now() - (:days * interval '1 day')", params);

        if (deleted > 0) {
            log.info("Purged {} completed '{}' task(s) older than {} day(s)", deleted, taskType, properties.retentionDaysFor(taskType));
        }
        return deleted;
    }
}
