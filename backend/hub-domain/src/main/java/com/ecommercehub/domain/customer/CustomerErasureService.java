package com.ecommercehub.domain.customer;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan §12 Phase 7 / §14: one person's erasure request.
 *
 * <p><b>Why the partitions are not enough.</b> Plan §3 keeps raw_event partitioned so
 * that expiry is a DROP rather than a scan. That answers "everything older than 90
 * days"; it does not answer "this person, today" — and a request from a real customer
 * is always the second kind. Their name and address sit verbatim inside raw_event
 * bodies that may be 89 days from expiry.
 *
 * <p><b>What this costs, stated plainly.</b> A masked raw body no longer matches the
 * signature the channel sent with it, so it can never be re-verified. That is not a
 * bug to fix later: the body is kept for signature verification at ingest, which has
 * already happened, and refusing to redact it in order to preserve a signature nobody
 * will check again would be keeping personal data for the benefit of an audit that
 * cannot occur.
 */
@Service
public class CustomerErasureService {

    private static final Logger log = LoggerFactory.getLogger(CustomerErasureService.class);
    private static final String REDACTION = "[REDACTED]";

    private final CustomerRepository customerRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantContextService tenantContextService;
    private final AuditLogService auditLog;

    public CustomerErasureService(CustomerRepository customerRepository, NamedParameterJdbcTemplate jdbcTemplate,
                                   TenantContextService tenantContextService, AuditLogService auditLog) {
        this.customerRepository = customerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContextService = tenantContextService;
        this.auditLog = auditLog;
    }

    /**
     * @return how many raw_event rows had a value redacted
     * @throws InsufficientRoleException unless the caller is an ADMIN — erasure is
     *         irreversible, so it sits at the same level as moving money
     */
    @Transactional
    public ErasureResult erase(AuthenticatedUser actor, UUID customerId) {
        if (!actor.hasAtLeast(HubRole.ADMIN)) {
            auditLog.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", "erase a customer", "role", actor.effectiveRole().name(), "required", "ADMIN"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.ADMIN);
        }

        UUID organizationId = actor.organizationId();
        tenantContextService.setTransactionTenantContext(organizationId);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("No customer " + customerId));

        if (customer.isErased()) {
            // Idempotent: a repeated request is a no-op, not a second redaction pass over
            // bodies whose values are already gone.
            return new ErasureResult(customerId, 0, true);
        }

        // Collected BEFORE the row is overwritten — these values are the only handle on
        // where the same data was copied into event bodies.
        List<String> identifiers = identifiersOf(customer);
        int maskedEvents = maskRawEvents(organizationId, identifiers);

        customer.erase(Instant.now());

        auditLog.record(organizationId, actor.userId(), "CUSTOMER_ERASED",
                Map.of("customerId", customerId.toString(), "maskedRawEvents", maskedEvents));
        // Note what this log line does NOT contain: any of the values just erased.
        log.info("Customer {} erased — {} raw event(s) redacted", customerId, maskedEvents);

        return new ErasureResult(customerId, maskedEvents, false);
    }

    private List<String> identifiersOf(Customer customer) {
        List<String> values = new ArrayList<>();
        addIfUsable(values, customer.getEmail());
        addIfUsable(values, customer.getPhone());
        addIfUsable(values, customer.getAddress());
        addIfUsable(values, customer.getFirstName() + " " + customer.getLastName());
        addIfUsable(values, customer.getFirstName());
        addIfUsable(values, customer.getLastName());
        return values;
    }

    /**
     * Values shorter than three characters are skipped. A one-letter surname would match
     * inside half the words in every body and redact the whole event rather than the
     * person — destroying the operational record without improving anyone's privacy.
     */
    private void addIfUsable(List<String> values, String value) {
        if (value != null && value.isBlank() == false && value.trim().length() >= 3) {
            values.add(value.trim());
        }
    }

    /**
     * Replaces each identifying value wherever it appears in a stored event body.
     *
     * <p>Substring replacement rather than a structured edit on purpose: the bodies are
     * whatever shape each channel sends, and there is no schema to walk. Matching on the
     * literal values we hold is the one approach that does not need to understand the
     * document.
     */
    private int maskRawEvents(UUID organizationId, List<String> identifiers) {
        if (identifiers.isEmpty()) {
            return 0;
        }

        StringBuilder expression = new StringBuilder("raw_body");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("org", organizationId);
        StringBuilder matches = new StringBuilder();

        for (int i = 0; i < identifiers.size(); i++) {
            expression = new StringBuilder("replace(" + expression + ", :v" + i + ", :redaction)");
            params.addValue("v" + i, identifiers.get(i));

            if (i > 0) {
                matches.append(" OR ");
            }
            matches.append("position(:v").append(i).append(" in raw_body) > 0");
        }
        params.addValue("redaction", REDACTION);

        return jdbcTemplate.update(
                "UPDATE hub.raw_event SET raw_body = " + expression
                        + " WHERE organization_id = :org AND (" + matches + ")",
                params);
    }

    /** @param alreadyErased true when the request was a repeat and nothing needed doing */
    public record ErasureResult(UUID customerId, int maskedRawEvents, boolean alreadyErased) {
    }
}
