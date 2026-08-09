package com.ecommercehub.domain.stock;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.push.ChannelPushService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Plan v5 §7.2 point 4: the per-channel buffer {@link com.ecommercehub.domain.stock.StockAvailabilityService}
 * already reads (Plan §3's allocation formula). Setting it changes the computed
 * availability for that one channel without touching the ledger at all — no stock_movement
 * row, because nothing about the physical count changed, only how much of it this channel
 * is shown.
 */
@Service
public class StockBufferService {

    private static final String UPSERT_SQL = """
            INSERT INTO hub.stock_buffer (id, organization_id, channel_connection_id, variant_id, buffer)
            VALUES (gen_random_uuid(), :org, :conn, :variant, :buffer)
            ON CONFLICT (organization_id, channel_connection_id, variant_id) DO UPDATE
            SET buffer = EXCLUDED.buffer, updated_at = now(), version = hub.stock_buffer.version + 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ChannelPushService channelPushService;
    private final AuditLogService auditLogService;

    public StockBufferService(NamedParameterJdbcTemplate jdbcTemplate, ChannelPushService channelPushService,
                               AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.channelPushService = channelPushService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void setBuffer(AuthenticatedUser actor, UUID channelConnectionId, UUID variantId, int buffer) {
        requireOperator(actor, "set stock buffer");
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer cannot be negative");
        }

        jdbcTemplate.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("org", actor.organizationId())
                .addValue("conn", channelConnectionId)
                .addValue("variant", variantId)
                .addValue("buffer", buffer));

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.STOCK_BUFFER_SET,
                Map.of("channelConnectionId", channelConnectionId.toString(), "variantId", variantId.toString(), "buffer", buffer));

        // The buffer only changes what one channel is shown, not the physical count — the
        // fan-out (ChannelPushService.enqueueStockPush's no-arg-counters overload) reads
        // the current stock row itself and recomputes availability for every mapped
        // channel, this one included.
        channelPushService.enqueueStockPush(actor.organizationId(), variantId);
    }

    private void requireOperator(AuthenticatedUser actor, String action) {
        if (!actor.hasAtLeast(HubRole.OPERATOR)) {
            auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", action, "role", actor.effectiveRole().name(), "required", "OPERATOR"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.OPERATOR);
        }
    }
}
