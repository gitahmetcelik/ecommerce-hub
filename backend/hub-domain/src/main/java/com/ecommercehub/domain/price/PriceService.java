package com.ecommercehub.domain.price;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.push.ChannelPushCapabilityChecker;
import com.ecommercehub.domain.push.ChannelPushService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan §6: the center is authoritative for price (v4 §3's authority matrix). A channel
 * never gets to set its own price here — {@link #setChannelPrice} is the center
 * deciding to carve out an exception for one channel, not the channel pushing a value
 * up. Every write fans out a (possibly unchanged, and therefore no-op'd by {@link
 * com.ecommercehub.domain.push.ChannelPushStore#upsert}) push to every channel that
 * sells the variant, exactly the pattern {@link com.ecommercehub.domain.stock.StockLedgerService}
 * established for stock.
 */
@Service
public class PriceService {

    private static final String MAPPED_CHANNELS_SQL = """
            SELECT m.channel_connection_id, m.channel_variant_id, c.channel_type, v.sku, v.barcode
            FROM hub.channel_product_mapping m
            JOIN hub.variant v ON v.id = m.variant_id
            JOIN hub.channel_connection c ON c.id = m.channel_connection_id
            WHERE m.organization_id = :org AND m.variant_id = :variant
            """;

    private final PriceRepository priceRepository;
    private final ChannelPriceRepository channelPriceRepository;
    private final ChannelPushService channelPushService;
    private final ChannelPushCapabilityChecker capabilityChecker;
    private final AuditLogService auditLogService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PriceService(PriceRepository priceRepository, ChannelPriceRepository channelPriceRepository,
                         ChannelPushService channelPushService, ChannelPushCapabilityChecker capabilityChecker,
                         AuditLogService auditLogService, NamedParameterJdbcTemplate jdbcTemplate) {
        this.priceRepository = priceRepository;
        this.channelPriceRepository = channelPriceRepository;
        this.channelPushService = channelPushService;
        this.capabilityChecker = capabilityChecker;
        this.auditLogService = auditLogService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void setListPrice(AuthenticatedUser actor, UUID variantId, BigDecimal amount, String currency,
                              BigDecimal vatRate) {
        requireOperator(actor, "set list price");

        Price price = priceRepository.findByOrganizationIdAndVariantIdForUpdate(actor.organizationId(), variantId)
                .orElseGet(() -> new Price(UUID.randomUUID(), actor.organizationId(), variantId, amount, currency, vatRate));
        price.apply(amount, currency, vatRate);
        priceRepository.save(price);

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PRICE_LIST_SET,
                Map.of("variantId", variantId.toString(), "amount", amount.toPlainString(), "currency", currency));

        pushEffectivePrices(actor.organizationId(), variantId);
    }

    @Transactional
    public void setChannelPrice(AuthenticatedUser actor, UUID channelConnectionId, UUID variantId,
                                 BigDecimal amount, BigDecimal discountedPrice) {
        requireOperator(actor, "set channel price");

        ChannelPrice channelPrice = channelPriceRepository
                .findByOrganizationIdAndChannelConnectionIdAndVariantIdForUpdate(actor.organizationId(), channelConnectionId, variantId)
                .orElseGet(() -> new ChannelPrice(UUID.randomUUID(), actor.organizationId(), channelConnectionId,
                        variantId, amount, discountedPrice));
        channelPrice.apply(amount, discountedPrice);
        channelPriceRepository.save(channelPrice);

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PRICE_CHANNEL_SET,
                Map.of("channelConnectionId", channelConnectionId.toString(), "variantId", variantId.toString(),
                        "amount", amount.toPlainString()));

        pushEffectivePrices(actor.organizationId(), variantId);
    }

    /** Plan §6.4 gate: deleting the channel override reverts that channel to the list price. */
    @Transactional
    public void clearChannelPrice(AuthenticatedUser actor, UUID channelConnectionId, UUID variantId) {
        requireOperator(actor, "clear channel price");

        channelPriceRepository
                .findByOrganizationIdAndChannelConnectionIdAndVariantId(actor.organizationId(), channelConnectionId, variantId)
                .ifPresent(channelPriceRepository::delete);

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PRICE_CHANNEL_CLEARED,
                Map.of("channelConnectionId", channelConnectionId.toString(), "variantId", variantId.toString()));

        pushEffectivePrices(actor.organizationId(), variantId);
    }

    /** Channel price if one exists and is active, otherwise the list price. Null if neither is set. */
    @Transactional(readOnly = true)
    public Optional<EffectivePrice> effectivePriceFor(UUID organizationId, UUID channelConnectionId, UUID variantId) {
        Optional<ChannelPrice> override = channelPriceRepository
                .findByOrganizationIdAndChannelConnectionIdAndVariantId(organizationId, channelConnectionId, variantId)
                .filter(ChannelPrice::isActive);
        if (override.isPresent()) {
            ChannelPrice cp = override.get();
            return Optional.of(new EffectivePrice(cp.getPrice(), cp.getDiscountedPrice(), null));
        }
        return priceRepository.findByOrganizationIdAndVariantId(organizationId, variantId)
                .map(p -> new EffectivePrice(p.getListPrice(), null, p.getCurrency()));
    }

    /**
     * Recomputes and (re-)enqueues the effective price for every channel that sells
     * this variant. Plan §6.2 point 7: a channel whose connector has no PRICE_PUSH
     * capability never gets a row at all — nothing would ever consume it.
     */
    private void pushEffectivePrices(UUID organizationId, UUID variantId) {
        List<MappedChannel> channels = jdbcTemplate.query(MAPPED_CHANNELS_SQL,
                new MapSqlParameterSource().addValue("org", organizationId).addValue("variant", variantId),
                (rs, rowNum) -> new MappedChannel(
                        (UUID) rs.getObject("channel_connection_id"),
                        rs.getString("channel_variant_id"),
                        rs.getString("channel_type"),
                        rs.getString("sku"),
                        rs.getString("barcode")));

        for (MappedChannel channel : channels) {
            if (!capabilityChecker.supports(channel.channelType(), ChannelPushService.TYPE_PRICE)) {
                continue;
            }
            effectivePriceFor(organizationId, channel.channelConnectionId(), variantId)
                    .ifPresent(effective -> channelPushService.enqueuePricePush(organizationId,
                            channel.channelConnectionId(), variantId, channel.channelVariantId(),
                            channel.sku(), channel.barcode(), effective.price(), effective.discountedPrice()));
        }
    }

    /**
     * Plan v5 §2.5, H6 pattern: the real per-action decision lives here, not just in
     * SecurityConfig, so every caller hits it — and a denied attempt is audited too.
     */
    private void requireOperator(AuthenticatedUser actor, String action) {
        if (!actor.hasAtLeast(HubRole.OPERATOR)) {
            auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", action, "role", actor.effectiveRole().name(), "required", "OPERATOR"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.OPERATOR);
        }
    }

    private record MappedChannel(UUID channelConnectionId, String channelVariantId, String channelType,
                                  String sku, String barcode) {
    }

    /** @param currency null when this came from a channel override, which carries no currency of its own. */
    public record EffectivePrice(BigDecimal price, BigDecimal discountedPrice, String currency) {
    }
}
