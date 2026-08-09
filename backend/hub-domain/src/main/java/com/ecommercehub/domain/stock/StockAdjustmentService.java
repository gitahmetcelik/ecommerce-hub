package com.ecommercehub.domain.stock;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Plan v5 §7.2 point 3 / §U5: the operator-facing surface over {@link StockLedgerService
 * #recordManualAdjustment} — role check, audit trail, and the traceable reference every
 * other write path in this codebase already carries. {@link StockLedgerService} itself
 * stays actor-agnostic (every other caller is system-driven), so authorization and audit
 * live here, one layer up, the same split {@code PriceService} uses over the push layer.
 */
@Service
public class StockAdjustmentService {

    private final StockLedgerService stockLedgerService;
    private final AuditLogService auditLogService;

    public StockAdjustmentService(StockLedgerService stockLedgerService, AuditLogService auditLogService) {
        this.stockLedgerService = stockLedgerService;
        this.auditLogService = auditLogService;
    }

    /**
     * @throws StockAdjustmentConflictException when {@code expectedOnHand} no longer
     *         matches the row — Plan §7.5's "two concurrent corrections, the second sees
     *         the conflict flow, never silently overwrites"
     */
    @Transactional
    public void adjust(AuthenticatedUser actor, UUID variantId, int expectedOnHand, int newOnHand,
                        StockAdjustmentReason reason, String note) {
        requireOperator(actor, "adjust stock");

        // Plan §U5 / §7.5: "sebepsiz düzeltme reddedilir" — enum required, free note optional.
        if (reason == null) {
            throw new IllegalArgumentException("A stock adjustment must carry a reason");
        }

        UUID referenceId = UUID.randomUUID();
        stockLedgerService.recordManualAdjustment(actor.organizationId(), variantId, expectedOnHand, newOnHand,
                reason, note, actor.userId(), referenceId);

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.STOCK_MANUALLY_ADJUSTED,
                Map.of("variantId", variantId.toString(), "from", expectedOnHand, "to", newOnHand,
                        "reason", reason.name(), "referenceId", referenceId.toString()));
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
}
