package com.ecommercehub.domain.returns;

import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plan §0/§7: "manual approval timeout: a reminder at 24h, TIMED_OUT plus escalation at 48h".
 *
 * <p><b>Nothing here decides anything.</b> The 48-hour deadline escalates the return to
 * a human; it does not reject it. An automatic rejection would be a customer-visible
 * decision that no person made and that nobody could later defend — and unlike a delay,
 * it is not recoverable once the customer has been told no.
 */
@Service
public class ReturnApprovalTimerService {

    private static final Logger log = LoggerFactory.getLogger(ReturnApprovalTimerService.class);

    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnService returnService;
    private final TenantContextService tenantContextService;

    public ReturnApprovalTimerService(ReturnRequestRepository returnRequestRepository, ReturnService returnService,
                                       TenantContextService tenantContextService) {
        this.returnRequestRepository = returnRequestRepository;
        this.returnService = returnService;
        this.tenantContextService = tenantContextService;
    }

    /** @return how many reminders were raised */
    @Transactional
    public int sendReminders(UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        List<ReturnRequest> due = returnRequestRepository
                .findByStatusAndReminderAtBeforeAndRemindedAtIsNull(ReturnStatus.AWAITING_APPROVAL, Instant.now());

        for (ReturnRequest request : due) {
            // reminded_at is what stops this repeating every sweep — the reminder is a
            // nudge, and a nudge every 30 seconds is noise an operator learns to ignore.
            request.markReminded(Instant.now());
            returnService.enqueueOperatorItem(organizationId, "RETURN_APPROVAL_REMINDER", request.getId(),
                    "Return " + request.getId() + " has been awaiting a decision for 24 hours");
            log.info("Reminder raised for return {}", request.getId());
        }
        return due.size();
    }

    /** @return how many returns timed out and were escalated */
    @Transactional
    public int escalateTimeouts(UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        List<ReturnRequest> overdue = returnRequestRepository
                .findByStatusAndTimeoutAtBefore(ReturnStatus.AWAITING_APPROVAL, Instant.now());

        for (ReturnRequest request : overdue) {
            // TIMED_OUT is still an open state: isAwaitingDecision() covers it, so an
            // operator arriving late can still approve or reject normally.
            request.moveTo(ReturnStatus.TIMED_OUT);
            returnService.enqueueOperatorItem(organizationId, "RETURN_APPROVAL_TIMEOUT", request.getId(),
                    "Return " + request.getId() + " passed its 48-hour approval deadline with no decision");
            log.warn("Return {} timed out awaiting approval and was escalated — NOT rejected", request.getId());
        }
        return overdue.size();
    }
}
