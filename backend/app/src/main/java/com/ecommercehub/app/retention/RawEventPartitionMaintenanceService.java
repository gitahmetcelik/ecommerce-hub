package com.ecommercehub.app.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Creates and drops hub.raw_event's monthly partitions (plan §3, §4.4, §12 Faz 7 gate).
 * 90-day retention here is a partition DROP, not a DELETE — the entire reason raw_event
 * is partitioned instead of being one ever-growing table.
 *
 * <p>Partition DDL (CREATE/DROP TABLE) requires ownership of the parent table, which
 * only the migration-running role has (hub_owner conceptually; in this dev setup, the
 * same primary connection Flyway itself uses). Neither hub_app (request-serving) nor
 * hub_system (BYPASSRLS background queries) own these tables, and giving either of them
 * DDL rights would widen their blast radius well past what the plan's three-role split
 * (§3a) intends. Revisit this once the primary pool moves off its superuser dev default.
 */
@Service
public class RawEventPartitionMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(RawEventPartitionMaintenanceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties properties;

    public RawEventPartitionMaintenanceService(JdbcTemplate jdbcTemplate, RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** Creates this month's and the next N months' partitions if they don't already exist. */
    public void ensureUpcomingPartitions() {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= properties.getRawEventPartitionsAheadMonths(); i++) {
            createPartitionIfMissing(current.plusMonths(i));
        }
    }

    /** Drops partitions whose entire range is older than the retention window. */
    public void dropExpiredPartitions() {
        YearMonth cutoff = YearMonth.from(LocalDate.now(ZoneOffset.UTC).minusDays(properties.getRawEventRetentionDays()));
        // A generous lookback so nothing is left behind if maintenance hasn't run in a while.
        YearMonth earliest = cutoff.minusMonths(36);
        for (YearMonth month = earliest; month.isBefore(cutoff); month = month.plusMonths(1)) {
            dropPartitionIfPresent(month);
        }
    }

    private void createPartitionIfMissing(YearMonth month) {
        String partitionName = partitionName(month);
        String from = month.atDay(1).toString();
        String to = month.plusMonths(1).atDay(1).toString();

        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS hub.%s PARTITION OF hub.raw_event FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, from, to));

        // Mirrors the RLS setup V1000 applies to every hub table (plan §3 a-d) — a
        // partition attached after the migration ran would otherwise be unprotected
        // if ever queried by its own name instead of through the parent.
        jdbcTemplate.execute(String.format("ALTER TABLE hub.%s ENABLE ROW LEVEL SECURITY", partitionName));
        jdbcTemplate.execute(String.format("ALTER TABLE hub.%s FORCE ROW LEVEL SECURITY", partitionName));
        jdbcTemplate.execute(String.format("DROP POLICY IF EXISTS org_isolation ON hub.%s", partitionName));
        jdbcTemplate.execute(String.format(
                "CREATE POLICY org_isolation ON hub.%s USING (organization_id = current_setting('hub.org_id')::uuid) " +
                "WITH CHECK (organization_id = current_setting('hub.org_id')::uuid)",
                partitionName));
    }

    private void dropPartitionIfPresent(YearMonth month) {
        String partitionName = partitionName(month);
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'hub' AND c.relname = ?",
                Integer.class, partitionName);
        if (exists == null || exists == 0) {
            return;
        }
        jdbcTemplate.execute("DROP TABLE hub." + partitionName);
        log.info("Dropped expired hub.raw_event partition {}", partitionName);
    }

    private String partitionName(YearMonth month) {
        return String.format("raw_event_y%d_m%02d", month.getYear(), month.getMonthValue());
    }
}
