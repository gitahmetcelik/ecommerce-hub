package com.ecommercehub.domain.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * plan §11's local (no API calls) nightly pass: recompute every stock counter from
 * the stock_movement ledger and report anything that disagrees.
 *
 * <p>This is not redundant with the channel reconcile. A channel reconcile compares
 * our number to a channel's number; if our own counter has drifted away from the
 * movements that produced it — a direct UPDATE, a bug in a new write path, a partially
 * applied transaction — then the number we compare <em>with</em> is already wrong and
 * the comparison cannot see it. Only replaying the ledger can.
 */
@Service
public class StockConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(StockConsistencyService.class);

    /**
     * Replays the ledger per variant and returns only the rows that disagree. The
     * ledger stores unsigned magnitudes with the direction in {@code reason} (see
     * StockLedgerService), so each counter is a sum of its increases minus its decreases.
     */
    private static final String MISMATCHES_SQL = """
            WITH replayed AS (
                SELECT variant_id,
                       SUM(CASE WHEN reason = 'ON_HAND_INCREASE'  THEN quantity
                                WHEN reason = 'ON_HAND_DECREASE'  THEN -quantity ELSE 0 END) AS on_hand,
                       SUM(CASE WHEN reason = 'RESERVED_INCREASE' THEN quantity
                                WHEN reason = 'RESERVED_DECREASE' THEN -quantity ELSE 0 END) AS reserved,
                       SUM(CASE WHEN reason = 'DAMAGED_INCREASE'  THEN quantity ELSE 0 END)  AS damaged
                FROM hub.stock_movement
                WHERE organization_id = :org
                GROUP BY variant_id
            )
            SELECT s.variant_id,
                   s.on_hand   AS actual_on_hand,
                   s.reserved  AS actual_reserved,
                   s.damaged   AS actual_damaged,
                   COALESCE(r.on_hand, 0)  AS expected_on_hand,
                   COALESCE(r.reserved, 0) AS expected_reserved,
                   COALESCE(r.damaged, 0)  AS expected_damaged
            FROM hub.stock s
            LEFT JOIN replayed r ON r.variant_id = s.variant_id
            WHERE s.organization_id = :org
              AND (s.on_hand  <> COALESCE(r.on_hand, 0)
                OR s.reserved <> COALESCE(r.reserved, 0)
                OR s.damaged  <> COALESCE(r.damaged, 0))
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final StockDiscrepancyRecorder discrepancyRecorder;

    public StockConsistencyService(NamedParameterJdbcTemplate jdbcTemplate, StockDiscrepancyRecorder discrepancyRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.discrepancyRecorder = discrepancyRecorder;
    }

    /** @return how many variants were found inconsistent (and reported) */
    @Transactional
    public int checkOrganization(UUID organizationId) {
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                MISMATCHES_SQL, new MapSqlParameterSource("org", organizationId));

        for (Map<String, Object> row : mismatches) {
            UUID variantId = (UUID) row.get("variant_id");
            report(organizationId, variantId, "on_hand", row.get("expected_on_hand"), row.get("actual_on_hand"));
            report(organizationId, variantId, "reserved", row.get("expected_reserved"), row.get("actual_reserved"));
            report(organizationId, variantId, "damaged", row.get("expected_damaged"), row.get("actual_damaged"));
        }

        if (!mismatches.isEmpty()) {
            log.warn("Ledger consistency check found {} variant(s) whose stock row disagrees with its movements",
                    mismatches.size());
        }
        return mismatches.size();
    }

    private void report(UUID organizationId, UUID variantId, String counter, Object expected, Object actual) {
        int expectedValue = ((Number) expected).intValue();
        int actualValue = ((Number) actual).intValue();
        if (expectedValue == actualValue) {
            return;
        }
        discrepancyRecorder.record(organizationId, null, variantId,
                StockDiscrepancyType.INTERNAL_INCONSISTENCY, expectedValue, actualValue);
        log.warn("Variant {} counter {}: ledger says {}, stock row says {}", variantId, counter, expectedValue, actualValue);
    }
}
