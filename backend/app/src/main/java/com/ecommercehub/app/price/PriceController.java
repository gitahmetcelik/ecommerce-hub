package com.ecommercehub.app.price;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.price.PriceService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan v5 Faz 6 §6.2/§6.3: the backend surface for center-owned pricing. No UI yet — the
 * plan explicitly defers that to Faz 7 ("bu faz API'yi teslim eder").
 *
 * <p>No role checks here, same as {@code ReturnController}: the roles are enforced in
 * {@link PriceService} so every caller hits them, not just this endpoint.
 */
@RestController
@Profile("api")
@RequestMapping("/internal/prices")
public class PriceController {

    private final PriceService priceService;
    private final TenantContextService tenantContextService;
    private final JdbcTemplate jdbcTemplate;

    public PriceController(PriceService priceService, TenantContextService tenantContextService, JdbcTemplate jdbcTemplate) {
        this.priceService = priceService;
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SetListPriceRequest(BigDecimal amount, String currency, BigDecimal vatRate) {
    }

    @PostMapping("/{variantId}/list-price")
    public Map<String, Object> setListPrice(@PathVariable UUID variantId, @RequestBody SetListPriceRequest request) {
        priceService.setListPrice(CurrentUser.require(), variantId, request.amount(), request.currency(), request.vatRate());
        return Map.of("variantId", variantId, "listPrice", request.amount());
    }

    public record SetChannelPriceRequest(BigDecimal amount, BigDecimal discountedPrice) {
    }

    @PostMapping("/{variantId}/channel-price/{channelConnectionId}")
    public Map<String, Object> setChannelPrice(@PathVariable UUID variantId, @PathVariable UUID channelConnectionId,
                                                @RequestBody SetChannelPriceRequest request) {
        priceService.setChannelPrice(CurrentUser.require(), channelConnectionId, variantId,
                request.amount(), request.discountedPrice());
        return Map.of("variantId", variantId, "channelConnectionId", channelConnectionId, "price", request.amount());
    }

    /** Plan §6.4 gate: deleting the channel override reverts that channel to the list price. */
    @DeleteMapping("/{variantId}/channel-price/{channelConnectionId}")
    public Map<String, Object> clearChannelPrice(@PathVariable UUID variantId, @PathVariable UUID channelConnectionId) {
        priceService.clearChannelPrice(CurrentUser.require(), channelConnectionId, variantId);
        return Map.of("cleared", true);
    }

    /** The list price plus every channel override for one variant — enough to see what each channel will be told. */
    @GetMapping
    @Transactional
    public Map<String, Object> pricesFor(@RequestParam UUID variantId) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        List<Map<String, Object>> listPrice = jdbcTemplate.queryForList("""
                SELECT list_price, currency, vat_rate, effective_from
                FROM hub.price WHERE variant_id = ?
                """, variantId);
        List<Map<String, Object>> channelPrices = jdbcTemplate.queryForList("""
                SELECT channel_connection_id, price, discounted_price, is_active, updated_at
                FROM hub.channel_price WHERE variant_id = ? ORDER BY channel_connection_id
                """, variantId);
        return Map.of("listPrice", listPrice.isEmpty() ? null : listPrice.get(0), "channelPrices", channelPrices);
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientRole(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
}
