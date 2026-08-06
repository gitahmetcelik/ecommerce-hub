package com.ecommercehub.app.returns;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.returns.ReturnPayment;
import com.ecommercehub.domain.returns.ReturnRequest;
import com.ecommercehub.domain.returns.ReturnService;
import com.ecommercehub.domain.returns.Shipment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * plan §7's operator surface, on the internal screen until the Faz 6 dashboard exists.
 *
 * <p>Note what is <em>not</em> here: no role checks and no organization parameter. The
 * roles are enforced in the services (so every caller hits them, not just this one) and
 * the tenant comes from the access token.
 */
@RestController
@RequestMapping("/internal/returns")
public class ReturnController {

    private final ReturnService returnService;
    private final ReturnFulfilmentService fulfilmentService;

    public ReturnController(ReturnService returnService, ReturnFulfilmentService fulfilmentService) {
        this.returnService = returnService;
        this.fulfilmentService = fulfilmentService;
    }

    public record RejectRequest(String reason) {
    }

    public record DispositionRequest(Map<UUID, ReturnService.Disposition> byReturnItemId) {
    }

    @GetMapping("/{returnRequestId}")
    public Map<String, Object> get(@PathVariable UUID returnRequestId) {
        return toResponse(returnService.get(CurrentUser.organizationId(), returnRequestId));
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
