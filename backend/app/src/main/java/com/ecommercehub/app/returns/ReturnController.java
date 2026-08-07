package com.ecommercehub.app.returns;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.returns.ReturnPayment;
import com.ecommercehub.domain.returns.ReturnRequest;
import com.ecommercehub.domain.returns.ReturnService;
import com.ecommercehub.domain.returns.Shipment;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan §7's operator surface, on the internal screen until the Phase 6 dashboard exists.
 *
 * <p>Note what is <em>not</em> here: no role checks and no organization parameter. The
 * roles are enforced in the services (so every caller hits them, not just this one) and
 * the tenant comes from the access token.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("api")} — dashboard traffic belongs in the
 * REST-serving process, not the worker.
 */
@RestController
@Profile("api")
@RequestMapping("/internal/returns")
public class ReturnController {

    private final ReturnService returnService;
    private final ReturnFulfilmentService fulfilmentService;
    private final TenantContextService tenantContextService;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public ReturnController(ReturnService returnService, ReturnFulfilmentService fulfilmentService,
                             TenantContextService tenantContextService, JdbcTemplate jdbcTemplate) {
        this.returnService = returnService;
        this.fulfilmentService = fulfilmentService;
        this.tenantContextService = tenantContextService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public record RejectRequest(String reason) {
    }

    public record DispositionRequest(Map<UUID, ReturnService.Disposition> byReturnItemId) {
    }

    /** The operator's work list: open returns first, because those are the ones needing a decision. */
    @GetMapping
    @Transactional
    public List<Map<String, Object>> list() {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return jdbcTemplate.queryForList("""
                SELECT id, status, sales_order_id, channel_return_id, reason, timeout_at, created_at
                FROM hub.return_request
                ORDER BY (status IN ('AWAITING_APPROVAL', 'TIMED_OUT')) DESC, created_at DESC
                LIMIT 200
                """);
    }

    @GetMapping("/{returnRequestId}")
    public Map<String, Object> get(@PathVariable UUID returnRequestId) {
        return toResponse(returnService.get(CurrentUser.organizationId(), returnRequestId));
    }

    /** The lines that came back — what the receipt form needs in order to split intact from damaged. */
    @GetMapping("/{returnRequestId}/items")
    @Transactional
    public List<Map<String, Object>> items(@PathVariable UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return namedJdbcTemplate.queryForList("""
                SELECT id, order_item_id, quantity, intact_quantity, damaged_quantity
                FROM hub.return_item WHERE return_request_id = :id ORDER BY created_at
                """, new MapSqlParameterSource("id", returnRequestId));
    }

    @PostMapping("/{returnRequestId}/approve")
    public Map<String, Object> approve(@PathVariable UUID returnRequestId) {
        return toResponse(returnService.approve(CurrentUser.require(), returnRequestId));
    }

    @PostMapping("/{returnRequestId}/reject")
    public Map<String, Object> reject(@PathVariable UUID returnRequestId, @RequestBody RejectRequest request) {
        return toResponse(returnService.reject(CurrentUser.require(), returnRequestId, request.reason()));
    }

    @PostMapping("/{returnRequestId}/shipment")
    public Map<String, Object> createShipment(@PathVariable UUID returnRequestId) {
        Shipment shipment = fulfilmentService.createReturnShipment(CurrentUser.organizationId(), returnRequestId);
        return Map.of("shipmentId", shipment.getId(),
                "source", shipment.getSource(),
                "trackingNumber", String.valueOf(shipment.getTrackingNumber()));
    }

    @PostMapping("/{returnRequestId}/receipt")
    public Map<String, Object> recordReceipt(@PathVariable UUID returnRequestId,
                                              @RequestBody DispositionRequest request) {
        return toResponse(returnService.recordReceipt(CurrentUser.organizationId(), returnRequestId,
                request.byReturnItemId()));
    }

    @PostMapping("/{returnRequestId}/refund")
    public Map<String, Object> refund(@PathVariable UUID returnRequestId) {
        returnService.calculateRefund(CurrentUser.organizationId(), returnRequestId);
        ReturnPayment payment = fulfilmentService.issueRefund(CurrentUser.require(), returnRequestId);

        return Map.of("returnPaymentId", payment.getId(),
                "amount", payment.getAmount(),
                "currency", payment.getCurrency(),
                "status", payment.getStatus());
    }

    private Map<String, Object> toResponse(ReturnRequest request) {
        return Map.of("id", request.getId(),
                "status", request.getStatus(),
                "salesOrderId", request.getSalesOrderId(),
                "channelReturnId", String.valueOf(request.getChannelReturnId()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientRole(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
