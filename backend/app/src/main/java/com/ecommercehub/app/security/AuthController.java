package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.AuthenticationFailedException;
import com.ecommercehub.domain.auth.AuthenticationService;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.auth.InvalidTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Plan §10. Thin: every decision lives in {@link AuthenticationService}. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    public record LoginRequest(UUID organizationId, String email, String password) {
    }

    public record TokenRequest(String refreshToken) {
    }

    public record InviteRequest(String email, String fullName, HubRole role) {
    }

    public record AcceptInvitationRequest(String token, String password, String fullName) {
    }

    public record PasswordResetRequest(UUID organizationId, String email) {
    }

    public record PasswordResetConfirmRequest(String token, String newPassword) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return toResponse(authenticationService.login(request.organizationId(), request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody TokenRequest request) {
        return toResponse(authenticationService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody TokenRequest request) {
        authenticationService.logout(request.refreshToken());
        return Map.of("loggedOut", true);
    }

    /**
     * The invitation token is returned in the response rather than emailed. Email
     * delivery is out of scope for v1 (Plan §13 keeps messaging out), and pretending
     * otherwise would mean an invite flow nobody can complete.
     */
    @PostMapping("/invitations")
    public Map<String, Object> invite(@RequestBody InviteRequest request) {
        CurrentUser.requireRole(HubRole.ADMIN);
        var invitation = authenticationService.invite(CurrentUser.organizationId(), CurrentUser.userId(),
                request.email(), request.fullName(), request.role());

        return Map.of("invitationId", invitation.invitationId(),
                "userId", invitation.userId(),
                "token", invitation.token());
    }

    @PostMapping("/invitations/accept")
    public Map<String, Object> acceptInvitation(@RequestBody AcceptInvitationRequest request) {
        return toResponse(authenticationService.acceptInvitation(
                request.token(), request.password(), request.fullName()));
    }

    /**
     * Always reports success, whether or not the account exists — the response must not
     * reveal which email addresses have accounts. The token comes back only when there
     * was an account to reset (again, v1 has no email delivery).
     */
    @PostMapping("/password-reset/request")
    public Map<String, Object> requestPasswordReset(@RequestBody PasswordResetRequest request) {
        return authenticationService.requestPasswordReset(request.organizationId(), request.email())
                .map(token -> Map.<String, Object>of("requested", true, "token", token))
                .orElse(Map.of("requested", true));
    }

    @PostMapping("/password-reset/confirm")
    public Map<String, Object> confirmPasswordReset(@RequestBody PasswordResetConfirmRequest request) {
        authenticationService.confirmPasswordReset(request.token(), request.newPassword());
        return Map.of("passwordChanged", true);
    }

    /** Ends every session of the calling user — "sign out everywhere". */
    @PostMapping("/sessions/revoke-all")
    public Map<String, Object> revokeAllSessions() {
        int revoked = authenticationService.revokeAllSessions(CurrentUser.organizationId(), CurrentUser.userId());
        return Map.of("revokedCount", revoked);
    }

    private Map<String, Object> toResponse(AuthenticationService.TokenPair tokens) {
        return Map.of(
                "accessToken", tokens.accessToken(),
                "refreshToken", tokens.refreshToken(),
                "expiresInSeconds", tokens.accessTokenExpiresInSeconds(),
                "userId", tokens.userId(),
                "organizationId", tokens.organizationId(),
                "roles", tokens.roles());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationFailed(AuthenticationFailedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientRole(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
}
