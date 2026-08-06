package com.ecommercehub.domain.stock;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes stock_discrepancy rows. Reporting only — there is deliberately no "and then
 * fix it" method on this class, because plan §0 makes that a human decision and a
 * silent auto-correction is indistinguishable from data loss after the fact.
 */
@Component
public class StockDiscrepancyRecorder {

    /**
     * Re-reporting an unresolved discrepancy every night would bury the operator in
     * duplicates of the same problem, so an open row for the same (variant, channel,
     * type) is updated in place instead of appended to.
     */
    private static final String UPSERT_SQL = """
            WITH updated AS (
                UPDATE hub.stock_discrepancy
                SET expected = :expected, actual = :actual, updated_at = now(), version = version + 1
                WHERE organization_id = :org
                  AND variant_id = :variant
                  AND type = :type
                  AND channel_connection_id IS NOT DISTINCT FROM :conn
                  AND resolved = false
                RETURNING id
            )
            INSERT INTO hub.stock_discrepancy
                (id, organization_id, channel_connection_id, variant_id, type, expected, actual, resolved)
            SELECT gen_random_uuid(), :org, :conn, :variant, :type, :expected, :actual, false
            WHERE NOT EXISTS (SELECT 1 FROM updated)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StockDiscrepancyRecorder(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(UUID organizationId, UUID channelConnectionId, UUID variantId,
                        String type, int expected, int actual) {
        jdbcTemplate.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("conn", channelConnectionId)
                .addValue("variant", variantId)
                .addValue("type", type)
                .addValue("expected", expected)
                .addValue("actual", actual));
    }
}
