package com.ecommercehub.domain.catalog;

import com.ecommercehub.domain.paging.PageRequest;
import com.ecommercehub.domain.paging.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan v5 Faz 7 §7.2 points 1-2 / §U2-§U3: the Products screen's read-model. Everything
 * a row (and its expansion) needs comes back in one query — Plan §U2's "▾ genişletilmiş"
 * row is a pure UI reveal of data the list call already carries, not a second fetch.
 *
 * <p>{@code skuIsGenerated} flags a SKU Faz 1's {@code mintSkuFromBarcode} invented
 * ({@code "BC-" + barcode}, see {@link CatalogMatchingService}) — Plan §U2: shown as a
 * barcode badge instead of a SKU, or a support call ("I never wrote this SKU") follows.
 */
@Service
public class VariantScreenService {

    private static final String CHANNELS_SUBQUERY = """
            (
                SELECT jsonb_agg(jsonb_build_object(
                    'channelConnectionId', m.channel_connection_id,
                    'channelType', c.channel_type,
                    'channelVariantId', m.channel_variant_id,
                    'status', COALESCE(cp.status, 'UNKNOWN'),
                    'quantity', CASE WHEN cp.target_value -> 'quantity' IS NOT NULL THEN (cp.target_value->>'quantity')::int END,
                    'generation', cp.generation,
                    'updatedAt', cp.updated_at,
                    'hasChannelPriceOverride', (chp.id IS NOT NULL),
                    'consecutiveFailures', cp.consecutive_failures,
                    -- Plan §U3: "the ⚠ error row carries its reason" — the raiseStuckAlert
                    -- text (ChannelPushStore) is the only place that reason survives; a bare
                    -- STUCK status with no text is a diagnosis with the diagnosis missing.
                    'errorReason', (
                        SELECT oq.description FROM hub.operator_queue oq
                        WHERE oq.type = 'CHANNEL_PUSH_STUCK' AND oq.reference_id = cp.id AND oq.status = 'PENDING'
                        ORDER BY oq.created_at DESC LIMIT 1
                    )
                ) ORDER BY c.channel_type)
                FROM hub.channel_product_mapping m
                JOIN hub.channel_connection c ON c.id = m.channel_connection_id
                LEFT JOIN hub.channel_push cp
                       ON cp.channel_connection_id = m.channel_connection_id AND cp.variant_id = v.id AND cp.type = 'STOCK'
                LEFT JOIN hub.channel_price chp
                       ON chp.channel_connection_id = m.channel_connection_id AND chp.variant_id = v.id AND chp.is_active = true
                WHERE m.variant_id = v.id
            )::text AS channels
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VariantScreenService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public enum StockStatus { IN_STOCK, OUT_OF_STOCK }

    public enum MatchStatus { MATCHED, UNMATCHED }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(UUID organizationId, PageRequest pageRequest, String search,
                                                    UUID channelConnectionId, StockStatus stockStatus, MatchStatus matchStatus) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("org", organizationId);
        StringBuilder where = new StringBuilder(" v.organization_id = :org ");

        if (search != null && !search.isBlank()) {
            where.append(" AND (v.sku ILIKE :search OR v.barcode ILIKE :search OR p.title ILIKE :search) ");
            params.addValue("search", "%" + search.trim() + "%");
        }
        if (channelConnectionId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM hub.channel_product_mapping m "
                    + "WHERE m.variant_id = v.id AND m.channel_connection_id = :channelConnectionId) ");
            params.addValue("channelConnectionId", channelConnectionId);
        }
        if (matchStatus == MatchStatus.MATCHED) {
            where.append(" AND EXISTS (SELECT 1 FROM hub.channel_product_mapping m WHERE m.variant_id = v.id) ");
        } else if (matchStatus == MatchStatus.UNMATCHED) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM hub.channel_product_mapping m WHERE m.variant_id = v.id) ");
        }
        if (stockStatus == StockStatus.IN_STOCK) {
            where.append(" AND COALESCE(s.on_hand, 0) - COALESCE(s.reserved, 0) > 0 ");
        } else if (stockStatus == StockStatus.OUT_OF_STOCK) {
            where.append(" AND COALESCE(s.on_hand, 0) - COALESCE(s.reserved, 0) <= 0 ");
        }

        Long total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.variant v
                JOIN hub.product p ON p.id = v.product_id
                LEFT JOIN hub.stock s ON s.variant_id = v.id AND s.organization_id = v.organization_id
                WHERE
                """ + where, params, Long.class);

        params.addValue("limit", pageRequest.size()).addValue("offset", pageRequest.offset());
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT v.id, v.sku, v.barcode, COALESCE(v.sku = 'BC-' || v.barcode, false) AS sku_is_generated, p.title,
                       COALESCE(s.on_hand, 0) AS on_hand, COALESCE(s.reserved, 0) AS reserved,
                       COALESCE(s.on_hand, 0) - COALESCE(s.reserved, 0) AS sellable,
                       pr.list_price, pr.currency, pr.vat_rate,
                       """ + CHANNELS_SUBQUERY + """
                FROM hub.variant v
                JOIN hub.product p ON p.id = v.product_id
                LEFT JOIN hub.stock s ON s.variant_id = v.id AND s.organization_id = v.organization_id
                LEFT JOIN hub.price pr ON pr.variant_id = v.id AND pr.organization_id = v.organization_id
                WHERE
                """ + where + " ORDER BY v.sku LIMIT :limit OFFSET :offset", params).stream()
                .map(this::withParsedChannels)
                .toList();

        return PageResponse.of(pageRequest, total == null ? 0 : total, items);
    }

    /** Plan §U3: mappings, push history, price and per-channel buffer for one variant. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> detail(UUID organizationId, UUID variantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("org", organizationId).addValue("variant", variantId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id, v.sku, v.barcode, COALESCE(v.sku = 'BC-' || v.barcode, false) AS sku_is_generated, p.title,
                       COALESCE(s.on_hand, 0) AS on_hand, COALESCE(s.reserved, 0) AS reserved,
                       COALESCE(s.damaged, 0) AS damaged,
                       COALESCE(s.on_hand, 0) - COALESCE(s.reserved, 0) AS sellable,
                       pr.list_price, pr.currency, pr.vat_rate,
                       """ + CHANNELS_SUBQUERY + """
                FROM hub.variant v
                JOIN hub.product p ON p.id = v.product_id
                LEFT JOIN hub.stock s ON s.variant_id = v.id AND s.organization_id = v.organization_id
                LEFT JOIN hub.price pr ON pr.variant_id = v.id AND pr.organization_id = v.organization_id
                WHERE v.organization_id = :org AND v.id = :variant
                """, params);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> detail = withParsedChannels(rows.get(0));
        detail.put("buffers", jdbcTemplate.queryForList("""
                SELECT channel_connection_id, buffer, updated_at
                FROM hub.stock_buffer WHERE organization_id = :org AND variant_id = :variant
                """, params));
        detail.put("movements", jdbcTemplate.queryForList("""
                SELECT id, quantity, reason, adjustment_reason, note, actor_user_id, reference_id, created_at
                FROM hub.stock_movement WHERE organization_id = :org AND variant_id = :variant
                ORDER BY created_at DESC LIMIT 100
                """, params));
        return Optional.of(detail);
    }

    /**
     * Bug found post-Faz-8: pgjdbc hands a {@code jsonb} column back as a
     * driver-specific wrapper, not a {@code String}, so returning {@code channels}
     * straight from the row would serialize as {@code {"type":"jsonb","value":"...json
     * text..."}} instead of the array the frontend's {@code VariantChannelSummary[]}
     * type expects. This went unnoticed because every browser check of the Products
     * screen so far used a variant with zero channel mappings — {@code jsonb_agg}
     * itself returns SQL {@code NULL} for those, sidestepping the driver entirely.
     * {@code CHANNELS_SUBQUERY} casts to {@code ::text} and this re-parses it, the
     * same fix {@code ChannelConnectionService.detail} applies to {@code backfill_status}.
     */
    private Map<String, Object> withParsedChannels(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put("channels", parseChannelsJson((String) row.get("channels")));
        return copy;
    }

    private List<Map<String, Object>> parseChannelsJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt channels JSON", e);
        }
    }
}
