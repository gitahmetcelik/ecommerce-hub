package com.ecommercehub.app.channel;

import com.ecommercehub.app.security.CurrentUser;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Plan §8.2/§8.3: the channel-connect wizard's and channel-detail screen's API. The
 * list ({@code GET /internal/channel-connections}, no id) predates this phase and
 * stays in {@code InternalScreenController} — this controller only adds the id-scoped
 * and mutating routes onboarding needs.
 *
 * <p>No role checks here, same as every other internal controller: {@link
 * ChannelConnectionService} enforces ADMIN on every write, so a future caller that
 * isn't this endpoint (a task handler, a script) hits the same wall.
 */
@RestController
@Profile("api")
@RequestMapping("/internal/channel-connections")
public class ChannelConnectionController {

    private final ChannelConnectionService channelConnectionService;
    private final TenantContextService tenantContextService;

    public ChannelConnectionController(ChannelConnectionService channelConnectionService,
                                        TenantContextService tenantContextService) {
        this.channelConnectionService = channelConnectionService;
        this.tenantContextService = tenantContextService;
    }

    public record CreateRequest(String channelType, Object credentials) {
    }

    /** Plan §8.5: an invalid credential never reaches storage — {@code checkCredentials} runs before the insert, not after. */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        UUID id = channelConnectionService.create(CurrentUser.require(), request.channelType(), request.credentials());
        return Map.of("id", id.toString());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        return channelConnectionService.detail(CurrentUser.organizationId(), id);
    }

    public record CredentialsRequest(Object credentials) {
    }

    @PutMapping("/{id}/credentials")
    public Map<String, Object> rotateCredentials(@PathVariable UUID id, @RequestBody CredentialsRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        channelConnectionService.rotateCredentials(CurrentUser.require(), id, request.credentials());
        return Map.of("rotated", true);
    }

    @PostMapping("/{id}/backfill")
    public Map<String, Object> triggerBackfill(@PathVariable UUID id) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        channelConnectionService.triggerBackfill(CurrentUser.require(), id);
        return Map.of("triggered", true);
    }

    public record SettingsRequest(Integer reconcileIntervalMinutes, Integer allocationPriority) {
    }

    @PutMapping("/{id}/settings")
    public Map<String, Object> updateSettings(@PathVariable UUID id, @RequestBody SettingsRequest request) {
        tenantContextService.setTransactionTenantContext(CurrentUser.organizationId());
        channelConnectionService.updateSettings(CurrentUser.require(), id, request.reconcileIntervalMinutes(),
                request.allocationPriority());
        return Map.of("updated", true);
    }

    @ExceptionHandler(ChannelConnectionService.NoChannelConnectionException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ChannelConnectionService.NoChannelConnectionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ChannelConnectionService.InvalidChannelCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(ChannelConnectionService.InvalidChannelCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientRole(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
