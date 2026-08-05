package com.ecommercehub.app.internalscreen;

import com.ecommercehub.domain.catalog.CatalogMatchingService;
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

    public InternalScreenController(TenantContextService tenantContextService, JdbcTemplate jdbcTemplate,
                                     CatalogMatchingService catalogMatchingService) {
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.catalogMatchingService = catalogMatchingService;
    }

    @GetMapping("/internal/orders")
    @Transactional
    public List<Map<String, Object>> orders(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        return jdbcTemplate.queryForList("""
                SELECT id, channel_order_number, derived_status, total, currency, created_at, updated_at
                FROM hub.sales_order ORDER BY created_at DESC LIMIT 200
                """);
    }

    @GetMapping("/internal/order-items")
    @Transactional
    public List<Map<String, Object>> orderItems(@RequestParam UUID organizationId, @RequestParam UUID salesOrderId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        return namedJdbcTemplate.queryForList("""
                SELECT id, variant_id, quantity, status, updated_at
                FROM hub.order_item WHERE sales_order_id = :salesOrderId ORDER BY created_at
                """, new MapSqlParameterSource("salesOrderId", salesOrderId));
    }

    @GetMapping("/internal/raw-events")
    @Transactional
    public List<Map<String, Object>> rawEvents(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        return jdbcTemplate.queryForList("""
                SELECT id, channel_connection_id, channel_event_id, received_at, trace_id
                FROM hub.raw_event ORDER BY received_at DESC LIMIT 200
                """);
    }

    @GetMapping("/internal/intents")
    @Transactional
    public List<Map<String, Object>> intents(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        return jdbcTemplate.queryForList("""
                SELECT id, type, target_reference, status, created_at, updated_at
                FROM hub.channel_call_intent ORDER BY created_at DESC LIMIT 200
                """);
    }

    @GetMapping("/internal/operator-queue")
    @Transactional
    public List<Map<String, Object>> operatorQueue(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
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
    public List<Map<String, Object>> tasks(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
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
    public List<Map<String, Object>> mappingCandidates(@RequestParam UUID organizationId) {
        tenantContextService.setTransactionTenantContext(organizationId);
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
                                                         @RequestParam UUID organizationId,
                                                         @RequestBody ResolveMappingRequest request) {
        tenantContextService.setTransactionTenantContext(organizationId);
        catalogMatchingService.resolveManually(candidateId, request.variantId(), request.userId());
        return Map.of("resolved", true);
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
