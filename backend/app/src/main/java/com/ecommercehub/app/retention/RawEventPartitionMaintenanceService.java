package com.ecommercehub.app.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Matches the names this class creates, and deliberately nothing else. */
    private static final Pattern PARTITION_NAME = Pattern.compile("raw_event_y(\\d{4})_m(\\d{2})");

    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties properties;

    public RawEventPartitionMaintenanceService(JdbcTemplate jdbcTemplate, RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * Runs once at boot, before any webhook traffic can reach hub.raw_event. Without
     * this, a fresh deployment's first month of rows lands in raw_event_default, and
     * Postgres refuses to ever attach a real partition for that month afterward
     * ("updated partition constraint... would be violated by some row") — discovered
     * by test classes racing each other against a shared database, but it's a real
     * production ordering requirement, not just a test artifact.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensurePartitionsOnStartup() {
        ensureUpcomingPartitions();
    }

    /** Creates this month's and the next N months' partitions if they don't already exist. */
    public void ensureUpcomingPartitions() {
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= properties.getRawEventPartitionsAheadMonths(); i++) {
            createPartitionIfMissing(current.plusMonths(i));
        }
    }

    /**
     * Drops every partition whose entire range is older than the retention window.
     *
     * <p><b>Enumerates what actually exists</b> instead of guessing names inside a
     * lookback window. The earlier version walked 36 months back from the cutoff and
     * dropped whatever it found by name; anything older never came up at all, so a
     * database restored from an old backup — or one where maintenance had been off for a
     * couple of years — would hold personal data past its retention indefinitely, and
     * silently. There is no window here to be wrong about.
     */
    public void dropExpiredPartitions() {
        YearMonth cutoff = YearMonth.from(LocalDate.now(ZoneOffset.UTC).minusDays(properties.getRawEventRetentionDays()));

        for (String partitionName : existingPartitionNames()) {
            YearMonth month = monthOf(partitionName);
            if (month == null) {
                // raw_event_default, and anything else not following our naming scheme.
                // The default partition must survive: it is the catch-all for rows whose
                // month was never provisioned, and dropping it would start losing writes.
                continue;
            }
            if (month.isBefore(cutoff)) {
                jdbcTemplate.execute("DROP TABLE hub." + partitionName);
                log.info("Dropped expired hub.raw_event partition {}", partitionName);
            }
        }
    }

    /** Every table currently attached to hub.raw_event. */
    private List<String> existingPartitionNames() {
        return jdbcTemplate.queryForList("""
                SELECT child.relname
                FROM pg_inherits
                JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
                JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
                JOIN pg_namespace n  ON n.oid      = parent.relnamespace
                WHERE n.nspname = 'hub' AND parent.relname = 'raw_event'
                """, String.class);
    }

    /** @return the month a partition covers, or null when the name is not one of ours. */
    private YearMonth monthOf(String partitionName) {
        Matcher matcher = PARTITION_NAME.matcher(partitionName);
        if (!matcher.matches()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
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

    private String partitionName(YearMonth month) {
        return String.format("raw_event_y%d_m%02d", month.getYear(), month.getMonthValue());
    }
}
