package com.ecommercehub.app.internalscreen;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.customer.CustomerErasureService;
import com.ecommercehub.domain.queue.OperatorQueueService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan Phase 2: "an ugly but working internal screen" — plain JSON listings, no UI. A real
 * dashboard is Phase 6; this exists so a human can see what's happening without psql.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("api")} — dashboard traffic belongs in the
 * REST-serving process, not the worker.
 */
@RestController
@Profile("api")
public class InternalScreenController {

    private final TenantContextService tenantContextService;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final CatalogMatchingService catalogMatchingService;
    private final CustomerErasureService customerErasureService;
    private final OperatorQueueService operatorQueueService;

    public InternalScreenController(TenantContextService tenantContextService, JdbcTemplate jdbcTemplate,
                                     CatalogMatchingService catalogMatchingService,
                                     CustomerErasureService customerErasureService,
                                     OperatorQueueService operatorQueueService) {
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.catalogMatchingService = catalogMatchingService;
        this.customerErasureService = customerErasureService;
        this.operatorQueueService = operatorQueueService;
    }

    @GetMapping("/internal/orders")
    @Transactional
    public List<Map<String, Object>> orders() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_order_number, derived_status, total, currency, created_at, updated_at
                FROM hub.sales_order ORDER BY created_at DESC LIMIT 200
                """);
    }

    @GetMapping("/internal/order-items")
    @Transactional
    public List<Map<String, Object>> orderItems(@RequestParam UUID salesOrderId) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return namedJdbcTemplate.queryForList("""
                SELECT id, variant_id, quantity, status, updated_at
                FROM hub.order_item WHERE sales_order_id = :salesOrderId ORDER BY created_at
                """, new MapSqlParameterSource("salesOrderId", salesOrderId));
    }

    @GetMapping("/internal/raw-events")
    @Transactional
    public List<Map<String, Object>> rawEvents() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, channel_event_id, received_at, trace_id
                FROM hub.raw_event ORDER BY received_at DESC LIMIT 200
                """);
    }

    @GetMapping("/internal/intents")
    @Transactional
    public List<Map<String, Object>> intents() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, type, target_reference, status, created_at, updated_at
                FROM hub.channel_call_intent ORDER BY created_at DESC LIMIT 200
                """);
    }

    /**
     * Only RETURN_APPROVAL carries a real deadline (return_request.timeout_at, Plan §7's
     * 48-hour escalation clock) — everything else has no domain-level "due by", so
     * deadline_at is null and the row is ranked by age instead. Sorting nearest-deadline
     * first, then oldest-first, matches ui-plani.md §4.1: "a return four hours from its
     * 48-hour timeout outranks a two-day-old mapping candidate."
     */
    @GetMapping("/internal/operator-queue")
    @Transactional
    public List<Map<String, Object>> operatorQueue() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT oq.id, oq.type, oq.description, oq.reference_id, oq.status, oq.created_at,
                       rr.timeout_at AS deadline_at
                FROM hub.operator_queue oq
                LEFT JOIN hub.return_request rr
                       ON oq.type = 'RETURN_APPROVAL' AND rr.id = oq.reference_id
                WHERE oq.status = 'PENDING'
                ORDER BY (rr.timeout_at IS NULL), rr.timeout_at ASC, oq.created_at ASC
                LIMIT 200
                """);
    }

    public record DismissQueueItemRequest(String reason) {
    }

    /** Plan §3's "gürültülü eskalasyon, sessiz kayıp yok" cuts the other way here too: dismissing requires a reason on the record, not a silent disappearance. */
    @PostMapping("/internal/operator-queue/{id}/dismiss")
    @Transactional
    public Map<String, Object> dismissOperatorQueueItem(@PathVariable UUID id, @RequestBody DismissQueueItemRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        operatorQueueService.dismiss(CurrentUser.require(), id, request.reason());
        return Map.of("dismissed", true);
    }

    /**
     * work_batch is org-scoped (RLS), its linked engine task is not (Plan §1.1) — joined
     * here so the screen can show task status without a cross-org bypass connection.
     */
    @GetMapping("/internal/tasks")
    @Transactional
    public List<Map<String, Object>> tasks() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT wb.id AS work_batch_id, wb.task_type, wb.status AS work_batch_status,
                       wb.trace_id, g.id AS task_id, g.durum AS task_status, g.deneme_sayisi, g.hata
                FROM hub.work_batch wb
                LEFT JOIN motor.gorevler g ON g.id = wb.task_id
                ORDER BY wb.created_at DESC LIMIT 200
                """);
    }

    /** Plan Phase 3: the operator matching screen — see CatalogMatchingService.pendingCandidatesWithDetails. */
    @GetMapping("/internal/mapping-candidates")
    @Transactional
    public List<Map<String, Object>> mappingCandidates() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return catalogMatchingService.pendingCandidatesWithDetails();
    }

    /**
     * Plan v5 §2.4, H5: userId used to come from the request body — a client could
     * resolve a mapping and have the audit trail attribute it to anyone. Jackson
     * ignores unknown fields by default, so a client still sending userId in the body
     * is silently no-op rather than a breaking change.
     */
    public record ResolveMappingRequest(UUID variantId) {
    }

    @PostMapping("/internal/mapping-candidates/{candidateId}/resolve")
    @Transactional
    public Map<String, Object> resolveMappingCandidate(@PathVariable UUID candidateId,
                                                         @RequestBody ResolveMappingRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        catalogMatchingService.resolveManually(CurrentUser.require(), candidateId, request.variantId());
        return Map.of("resolved", true);
    }

    /** Plan §3 eslesme_adayi.durum = YOKSAYILDI: this channel item is not going to be matched (discontinued, test listing, ...). */
    @PostMapping("/internal/mapping-candidates/{candidateId}/ignore")
    @Transactional
    public Map<String, Object> ignoreMappingCandidate(@PathVariable UUID candidateId) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        catalogMatchingService.ignore(CurrentUser.require(), candidateId);
        return Map.of("ignored", true);
    }

    /**
     * Plan Phase 4: the push queue. A row sitting at PENDING with a high generation means
     * a channel is being told a number repeatedly and never confirming it — the shape a
     * broken push takes, and invisible from anywhere else.
     */
    @GetMapping("/internal/channel-pushes")
    @Transactional
    public List<Map<String, Object>> channelPushes() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, variant_id, type, target_value, generation, status,
                       last_attempt_at, updated_at
                FROM hub.channel_push ORDER BY updated_at DESC LIMIT 200
                """);
    }

    /** Plan §11: what reconcile found and deliberately did not fix. */
    @GetMapping("/internal/stock-discrepancies")
    @Transactional
    public List<Map<String, Object>> stockDiscrepancies() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, variant_id, type, expected, actual, resolved, updated_at
                FROM hub.stock_discrepancy WHERE resolved = false ORDER BY updated_at DESC LIMIT 200
                """);
    }

    /** Plan §3: sales we could not back with stock. */
    @GetMapping("/internal/oversells")
    @Transactional
    public List<Map<String, Object>> oversells() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, variant_id, requested, available, created_at
                FROM hub.oversell_event ORDER BY created_at DESC LIMIT 200
                """);
    }

    /** Plan Phase 4: connection health — circuit state and why it was last tripped. */
    @GetMapping("/internal/channel-connections")
    @Transactional
    public List<Map<String, Object>> channelConnections() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_type, status, consecutive_failures, circuit_open_until, last_failure_reason,
                       reconcile_interval_minutes, next_reconcile_at, last_order_sync_at, allocation_priority
                FROM hub.channel_connection ORDER BY created_at
                """);
    }

    /**
     * Plan Phase 7: an erasure request. ADMIN-only and irreversible — the role check lives
     * in the service, like every other one, so it holds for callers that are not this
     * endpoint.
     */
    @PostMapping("/customers/{customerId}/erase")
    public Map<String, Object> eraseCustomer(@PathVariable UUID customerId) {
        var result = customerErasureService.erase(CurrentUser.require(), customerId);
        return Map.of("customerId", result.customerId(),
                "maskedRawEvents", result.maskedRawEvents(),
                "alreadyErased", result.alreadyErased());
    }

    /**
     * Plan v5 §2.3, H4: motor.olu_mektup_kutusu has no organization_id (Plan §1.1) and no
     * RLS of its own — querying it directly, as this used to, showed every organization's
     * DLQ rows to whichever organization happened to be asking. Joined through
     * hub.work_batch instead, the same pattern /internal/tasks already uses: work_batch
     * IS org-scoped (RLS enforces it), so the join is what makes this filtered rather
     * than the raw table.
     *
     * <p>ADMIN-only: task failure messages are diagnostic detail (stack traces, payload
     * fragments), not something every OBSERVER in the org needs to see either.
     */
    @GetMapping("/internal/dlq")
    @Transactional
    public List<Map<String, Object>> deadLetterQueue() {
        CurrentUser.requireRole(HubRole.ADMIN);
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT d.id, d.gorev_id, d.son_hata, d.giris_zamani, d.yeniden_gonderildi_mi,
                       wb.task_type, wb.trace_id
                FROM motor.olu_mektup_kutusu d
                JOIN hub.work_batch wb ON wb.task_id = d.gorev_id
                ORDER BY d.giris_zamani DESC LIMIT 200
                """);
    }
}
