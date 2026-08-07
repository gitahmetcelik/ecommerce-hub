package com.ecommercehub.domain.queue;

import com.ecommercehub.domain.audit.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * hub.operator_queue rows are written by five different domain services (return
 * timeout escalation, unresolvable-return reconcile, catalog matching, credential
 * failure, dispatch orphan sweep, ambiguous call intents), each with its own type and
 * its own idea of when a row is done. Most of those types have no domain event that
 * ever closes them — CHANNEL_CREDENTIALS_INVALID, RETURN_UNRESOLVABLE, DISPATCH_TIMEOUT,
 * INTENT_AMBIGUOUS and ORDER_ITEM_ESCALATION all land here specifically because nothing
 * in the system can resolve them automatically (the human has to go look at a
 * marketplace dashboard, a carrier, a stuck task — outside anything this app can call).
 *
 * <p>This is the operator's acknowledgement that they did that outside work: not a new
 * domain decision, just "I saw this and handled it" with a reason on the record. It is
 * deliberately generic rather than per-type, because none of those five types has a
 * more specific action to offer instead.
 */
@Service
public class OperatorQueueService {

    public static final String OPERATOR_QUEUE_DISMISSED = "OPERATOR_QUEUE_DISMISSED";

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public OperatorQueueService(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void dismiss(UUID organizationId, UUID operatorQueueId, UUID userId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to dismiss an operator queue item");
        }

        int updated = jdbcTemplate.update("""
                UPDATE hub.operator_queue SET status = 'RESOLVED', updated_at = now(), version = version + 1
                WHERE id = ? AND organization_id = ? AND status = 'PENDING'
                """, operatorQueueId, organizationId);
        if (updated == 0) {
            throw new IllegalArgumentException("No pending operator_queue item " + operatorQueueId);
        }

        auditLogService.record(organizationId, userId, OPERATOR_QUEUE_DISMISSED,
                Map.of("operatorQueueId", operatorQueueId.toString(), "reason", reason));
    }
}
