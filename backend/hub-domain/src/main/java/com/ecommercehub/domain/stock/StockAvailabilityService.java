package com.ecommercehub.domain.stock;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan §3: {@code satilabilir(kanal) = fiziksel − rezerve − tampon(kanal)}, plus the
 * last-unit allocation policy that formula alone cannot express.
 *
 * <p><b>Why allocation exists.</b> The plain formula shows every channel the same
 * last unit. The buffer protects a fast seller but explicitly does not protect the
 * final unit — with one unit left and three channels each told "1 available", a
 * simultaneous sale on two of them is not a risk, it is arithmetic. So below the
 * organization's {@code low_stock_threshold} the remaining units are handed to a
 * single channel and every other channel is pushed 0.
 */
@Service
public class StockAvailabilityService {

    /**
     * One row per channel that actually sells this variant. Ordering is by
     * allocation_priority then id so the allocation winner is deterministic —
     * the same input state must always pick the same channel, otherwise two
     * consecutive pushes would flap the unit between channels.
     */
    private static final String MAPPED_CHANNELS_SQL = """
            SELECT m.channel_connection_id,
                   m.channel_variant_id,
                   v.sku,
                   v.barcode,
                   c.allocation_priority,
                   COALESCE(b.buffer, 0) AS buffer
            FROM hub.channel_product_mapping m
            JOIN hub.variant v ON v.id = m.variant_id
            JOIN hub.channel_connection c ON c.id = m.channel_connection_id
            LEFT JOIN hub.stock_buffer b
                   ON b.variant_id = m.variant_id
                  AND b.channel_connection_id = m.channel_connection_id
            WHERE m.organization_id = :org
              AND m.variant_id = :variant
            ORDER BY c.allocation_priority DESC, m.channel_connection_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final StockRepository stockRepository;

    public StockAvailabilityService(NamedParameterJdbcTemplate jdbcTemplate, StockRepository stockRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockRepository = stockRepository;
    }

    /** Reads the current stock counters itself. Use the overload below when the caller already holds them. */
    @Transactional(readOnly = true)
    public List<ChannelAvailability> computeFor(UUID organizationId, UUID variantId) {
        return stockRepository.findByOrganizationIdAndVariantId(organizationId, variantId)
                .map(stock -> computeFor(organizationId, variantId, stock.getOnHand(), stock.getReserved()))
                .orElseGet(() -> computeFor(organizationId, variantId, 0, 0));
    }

    /**
     * Takes the counters as arguments so a caller that just adjusted them in the same
     * transaction gets the post-adjustment answer without depending on when Hibernate
     * happens to flush — the enqueue path (StockLedgerService) is exactly that caller.
     */
    @Transactional(readOnly = true)
    public List<ChannelAvailability> computeFor(UUID organizationId, UUID variantId, int onHand, int reserved) {
        List<MappedChannel> channels = jdbcTemplate.query(MAPPED_CHANNELS_SQL,
                new MapSqlParameterSource().addValue("org", organizationId).addValue("variant", variantId),
                (rs, rowNum) -> new MappedChannel(
                        (UUID) rs.getObject("channel_connection_id"),
                        rs.getString("channel_variant_id"),
                        rs.getString("sku"),
                        rs.getString("barcode"),
                        rs.getInt("allocation_priority"),
                        rs.getInt("buffer")));

        if (channels.isEmpty()) {
            return List.of();
        }

        int base = Math.max(0, onHand - reserved);
        int threshold = lowStockThreshold(organizationId);

        return base > 0 && base <= threshold
                ? allocateToSingleChannel(channels, base)
                : shareAcrossChannels(channels, base);
    }

    /** The ordinary case: every channel sees the same pool, minus its own buffer. */
    private List<ChannelAvailability> shareAcrossChannels(List<MappedChannel> channels, int base) {
        List<ChannelAvailability> result = new ArrayList<>(channels.size());
        for (MappedChannel channel : channels) {
            result.add(channel.toAvailability(Math.max(0, base - channel.buffer())));
        }
        return result;
    }

    /**
     * Low-stock case: one channel gets the whole remainder, everyone else gets 0. The
     * buffer is deliberately ignored here — subtracting it would hide the last unit
     * from every channel, which is worse than the oversell risk it exists to reduce.
     */
    private List<ChannelAvailability> allocateToSingleChannel(List<MappedChannel> channels, int base) {
        // MAPPED_CHANNELS_SQL already sorts by (allocation_priority DESC, id ASC) —
        // that ordering *is* the tie-break policy, so the winner is simply the first row.
        MappedChannel winner = channels.get(0);

        List<ChannelAvailability> result = new ArrayList<>(channels.size());
        for (MappedChannel channel : channels) {
            result.add(channel.toAvailability(channel.channelConnectionId().equals(winner.channelConnectionId()) ? base : 0));
        }
        return result;
    }

    private int lowStockThreshold(UUID organizationId) {
        Integer threshold = jdbcTemplate.queryForObject(
                "SELECT low_stock_threshold FROM hub.organization WHERE id = :org",
                Map.of("org", organizationId), Integer.class);
        return threshold == null ? 1 : threshold;
    }

    private record MappedChannel(UUID channelConnectionId, String channelVariantId, String sku, String barcode,
                                  int allocationPriority, int buffer) {
        ChannelAvailability toAvailability(int quantity) {
            return new ChannelAvailability(channelConnectionId, channelVariantId, sku, barcode, quantity);
        }
    }
}
