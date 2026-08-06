package com.ecommercehub.app.internalscreen;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.customer.CustomerErasureService;
import com.ecommercehub.domain.tenant.TenantContextService;
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
 * plan Faz 2: "çirkin ama çalışan iç ekran" — plain JSON listings, no UI. A real
 * dashboard is Faz 6; this exists so a human can see what's happening without psql.
 */
@RestController
public class InternalScreenController {

    private final TenantContextService tenantContextService;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final CatalogMatchingService catalogMatchingService;
    private final CustomerErasureService customerErasureService;

    public InternalScreenController(TenantContextService tenantContextService, JdbcTemplate jdbcTemplate,
                                     CatalogMatchingService catalogMatchingService,
                                     CustomerErasureService customerErasureService) {
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.catalogMatchingService = catalogMatchingService;
        this.customerErasureService = customerErasureService;
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

    @GetMapping("/internal/operator-queue")
    @Transactional
    public List<Map<String, Object>> operatorQueue() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, type, description, reference_id, status, created_at
                FROM hub.operator_queue ORDER BY created_at DESC LIMIT 200
                """);
    }

    /**
     * work_batch is org-scoped (RLS), its linked engine task is not (plan §1.1) — joined
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

    /** plan Faz 3: the operator matching screen — every PENDING mapping_candidate awaiting a human decision. */
    @GetMapping("/internal/mapping-candidates")
    @Transactional
    public List<Map<String, Object>> mappingCandidates() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, channel_product_id, channel_variant_id, barcode, title,
                       candidate_variant_ids, status, created_at
                FROM hub.mapping_candidate WHERE status = 'PENDING' ORDER BY created_at LIMIT 200
                """);
    }

    public record ResolveMappingRequest(UUID variantId, UUID userId) {
    }

    @PostMapping("/internal/mapping-candidates/{candidateId}/resolve")
    @Transactional
    public Map<String, Object> resolveMappingCandidate(@PathVariable UUID candidateId,
                                                         @RequestBody ResolveMappingRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        catalogMatchingService.resolveManually(candidateId, request.variantId(), request.userId());
        return Map.of("resolved", true);
    }

    /**
     * plan Faz 4: the push queue. A row sitting at PENDING with a high generation means
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

    /** plan §11: what reconcile found and deliberately did not fix. */
    @GetMapping("/internal/stock-discrepancies")
    @Transactional
    public List<Map<String, Object>> stockDiscrepancies() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, variant_id, type, expected, actual, resolved, updated_at
                FROM hub.stock_discrepancy WHERE resolved = false ORDER BY updated_at DESC LIMIT 200
                """);
    }

    /** plan §3: sales we could not back with stock. */
    @GetMapping("/internal/oversells")
    @Transactional
    public List<Map<String, Object>> oversells() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, variant_id, requested, available, created_at
                FROM hub.oversell_event ORDER BY created_at DESC LIMIT 200
                """);
    }

    /** plan Faz 4: connection health — circuit state and why it was last tripped. */
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
     * plan Faz 7: an erasure request. ADMIN-only and irreversible — the role check lives
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

    /** motor.olu_mektup_kutusu has no organization_id (plan §1.1) — this is a system-wide view, not org-scoped. */
    @GetMapping("/internal/dlq")
    public List<Map<String, Object>> deadLetterQueue() {
        return jdbcTemplate.queryForList("""
                SELECT id, gorev_id, son_hata, giris_zamani, yeniden_gonderildi_mi
                FROM motor.olu_mektup_kutusu ORDER BY giris_zamani DESC LIMIT 200
                """);
    }
}
