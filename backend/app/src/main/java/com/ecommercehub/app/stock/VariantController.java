package com.ecommercehub.app.stock;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.catalog.VariantScreenService;
import com.ecommercehub.domain.paging.PageRequest;
import com.ecommercehub.domain.paging.PageResponse;
import com.ecommercehub.domain.stock.StockAdjustmentConflictException;
import com.ecommercehub.domain.stock.StockAdjustmentReason;
import com.ecommercehub.domain.stock.StockAdjustmentService;
import com.ecommercehub.domain.stock.StockBufferService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Plan v5 Faz 7 §7.2: the Products / variant-detail screen's API (§U2, §U3) plus the two
 * writes an operator makes from it (§U4's channel-price piece lives in {@code
 * PriceController}; §U5's manual correction and the per-channel buffer live here).
 *
 * <p>No role checks here, same as every other internal controller: the roles are
 * enforced in the services, so every caller hits them, not just this endpoint.
 */
@RestController
@Profile("api")
@RequestMapping("/internal/variants")
public class VariantController {

    private final VariantScreenService variantScreenService;
    private final StockAdjustmentService stockAdjustmentService;
    private final StockBufferService stockBufferService;
    private final TenantContextService tenantContextService;

    public VariantController(VariantScreenService variantScreenService, StockAdjustmentService stockAdjustmentService,
                              StockBufferService stockBufferService, TenantContextService tenantContextService) {
        this.variantScreenService = variantScreenService;
        this.stockAdjustmentService = stockAdjustmentService;
        this.stockBufferService = stockBufferService;
        this.tenantContextService = tenantContextService;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) UUID channelConnectionId,
                                                    @RequestParam(required = false) VariantScreenService.StockStatus stockStatus,
                                                    @RequestParam(required = false) VariantScreenService.MatchStatus matchStatus) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return variantScreenService.list(CurrentUser.organizationId(), PageRequest.of(page, size), q,
                channelConnectionId, stockStatus, matchStatus);
    }

    @GetMapping("/{variantId}")
    public Map<String, Object> get(@PathVariable UUID variantId) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return variantScreenService.detail(CurrentUser.organizationId(), variantId)
                .orElseThrow(() -> new NoVariantException(variantId));
    }

    public record StockAdjustmentRequest(int expectedOnHand, int newOnHand, StockAdjustmentReason reason, String note) {
    }

    /** Plan §U5: irreversible — no undo strip, a second correction is how you undo one. */
    @PostMapping("/{variantId}/stock-adjustment")
    public Map<String, Object> adjustStock(@PathVariable UUID variantId, @RequestBody StockAdjustmentRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        stockAdjustmentService.adjust(CurrentUser.require(), variantId, request.expectedOnHand(), request.newOnHand(),
                request.reason(), request.note());
        return Map.of("adjusted", true);
    }

    public record BufferRequest(int buffer) {
    }

    @PostMapping("/{variantId}/buffer/{channelConnectionId}")
    public Map<String, Object> setBuffer(@PathVariable UUID variantId, @PathVariable UUID channelConnectionId,
                                          @RequestBody BufferRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        stockBufferService.setBuffer(CurrentUser.require(), channelConnectionId, variantId, request.buffer());
        return Map.of("buffer", request.buffer());
    }

    private static final class NoVariantException extends RuntimeException {
        NoVariantException(UUID variantId) {
            super("No variant " + variantId);
        }
    }

    @ExceptionHandler(NoVariantException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoVariantException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientRole(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    /** Plan §7.5's optimistic-lock gate: the second concurrent correction sees this, never a silent overwrite. */
    @ExceptionHandler(StockAdjustmentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(StockAdjustmentConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "actualOnHand", e.getActualOnHand()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
