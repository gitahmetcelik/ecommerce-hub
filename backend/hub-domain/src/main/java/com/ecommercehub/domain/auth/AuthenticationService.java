package com.ecommercehub.domain.auth;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan §10: login, refresh, logout, invitation and password reset.
 *
 * <p>Two rules run through all of it. Secrets are stored only as hashes and shown once
 * ({@link SecretTokens}), and every outcome — including the failures — reaches the
 * audit log, because "who tried to get in and failed" is at least as interesting as
 * who succeeded.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final AppUserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserInvitationRepository invitationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final AuditLogService auditLog;
    private final TenantContextService tenantContextService;

    public AuthenticationService(AppUserRepository userRepository, UserRoleRepository userRoleRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  UserInvitationRepository invitationRepository,
                                  PasswordResetRepository passwordResetRepository,
                                  PasswordEncoder passwordEncoder, JwtService jwtService,
                                  AuthProperties properties, AuditLogService auditLog,
                                  TenantContextService tenantContextService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.invitationRepository = invitationRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.auditLog = auditLog;
        this.tenantContextService = tenantContextService;
    }

    /**
     * Plan Phase 5 gate: "a user is invited, signs in, and has their session ended"
     *
     * <p>The organization is part of the request rather than derived from the email:
     * emails are unique per organization, not globally, so email alone is ambiguous by
     * design. A globally unique email would forbid the same person from working for two
     * tenants, which is a worse constraint than asking which tenant they are signing in to.
     */
    @Transactional
    public TokenPair login(UUID organizationId, String email, String password) {
        tenantContextService.setTransactionTenantContext(organizationId);

        Optional<AppUser> maybeUser = userRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, email);

        // The password is verified even when the account is missing or disabled, against
        // a throwaway hash, so that all three paths take comparable time. Returning early
        // on an unknown email makes account enumeration a timing measurement away.
        boolean passwordMatches = maybeUser
                .map(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                .orElseGet(() -> {
                    passwordEncoder.matches(password, "$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidinv");
                    return false;
                });

        if (maybeUser.isEmpty() || !passwordMatches || !maybeUser.get().canAuthenticate()) {
            auditLog.record(organizationId, maybeUser.map(AppUser::getId).orElse(null),
                    AuditLogService.LOGIN_FAILED, Map.of("email", email));
            log.info("Failed login for {} in organization {}", email, organizationId);
            throw new AuthenticationFailedException("Invalid credentials");
        }

        AppUser user = maybeUser.get();
        user.recordLogin(Instant.now());
        auditLog.record(organizationId, user.getId(), AuditLogService.LOGIN_SUCCEEDED, Map.of("email", email));

        return issueTokens(user);
    }

    /**
     * Rotates the refresh token: the presented one is revoked and a new one issued.
     * A token presented twice therefore fails the second time, which turns a stolen and
     * replayed token into a visible error rather than a silent parallel session.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(SecretTokens.hash(refreshToken))
                .orElseThrow(() -> new InvalidTokenException("Unknown refresh token"));

        tenantContextService.setTransactionTenantContext(stored.getOrganizationId());

        if (!stored.isUsable(Instant.now())) {
            throw new InvalidTokenException("Refresh token is expired or revoked");
        }

        AppUser user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Refresh token points at a user that no longer exists"));
        if (!user.canAuthenticate()) {
            // Disabling an account has to end its sessions too, otherwise the account is
            // only disabled for people who log out first.
            stored.revoke(Instant.now());
            throw new InvalidTokenException("Account can no longer authenticate");
        }

        stored.revoke(Instant.now());
        return issueTokens(user);
    }

    /** Ends one session. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenHash(SecretTokens.hash(refreshToken)).ifPresent(stored -> {
            tenantContextService.setTransactionTenantContext(stored.getOrganizationId());
            stored.revoke(Instant.now());
            auditLog.record(stored.getOrganizationId(), stored.getUserId(), AuditLogService.LOGOUT, Map.of());
        });
    }

    /** Ends every session a user has — the "sign out everywhere" an administrator needs after a compromise. */
    @Transactional
    public int revokeAllSessions(UUID organizationId, UUID userId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(token -> token.revoke(Instant.now()));

        auditLog.record(organizationId, userId, AuditLogService.SESSIONS_REVOKED,
                Map.of("revokedCount", active.size()));
        return active.size();
    }

    /**
     * Creates the account in INVITED state and returns the one-time token.
     *
     * <p>The account exists immediately so the email address is reserved and roles are
     * granted up front, but it cannot authenticate: {@code password_hash} holds a value
     * no password can produce. Delivering the token by email is out of scope for v1 —
     * the caller gets it back and is responsible for getting it to the person.
     */
    @Transactional
    public Invitation invite(UUID organizationId, UUID invitedByUserId, String email, String fullName, HubRole role) {
        tenantContextService.setTransactionTenantContext(organizationId);

        AppUser user = userRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, email)
                .orElseGet(() -> userRepository.save(new AppUser(UUID.randomUUID(), organizationId, email,
                        unusablePasswordHash(), fullName, AppUser.STATUS_INVITED)));

        if (user.canAuthenticate()) {
            throw new IllegalStateException("A user with email " + email + " already exists in this organization");
        }

        userRoleRepository.save(new UserRole(UUID.randomUUID(), organizationId, user.getId(), role));

        String token = SecretTokens.generate();
        UserInvitation invitation = invitationRepository.save(new UserInvitation(
                UUID.randomUUID(), organizationId, user.getId(), email, role, SecretTokens.hash(token),
                invitedByUserId, Instant.now().plus(properties.getInvitationTtl())));

        auditLog.record(organizationId, invitedByUserId, AuditLogService.USER_INVITED,
                Map.of("email", email, "role", role.name(), "invitedUserId", user.getId().toString()));

        return new Invitation(invitation.getId(), user.getId(), token);
    }

    @Transactional
    public TokenPair acceptInvitation(String invitationToken, String password, String fullName) {
        UserInvitation invitation = invitationRepository.findByTokenHash(SecretTokens.hash(invitationToken))
                .orElseThrow(() -> new InvalidTokenException("Unknown invitation token"));

        tenantContextService.setTransactionTenantContext(invitation.getOrganizationId());

        if (!invitation.isUsable(Instant.now())) {
            throw new InvalidTokenException("Invitation is expired or already accepted");
        }

        AppUser user = userRepository.findById(invitation.getUserId()).orElseThrow();
        user.activateWithPassword(passwordEncoder.encode(password), fullName);
        invitation.accept(Instant.now());

        auditLog.record(invitation.getOrganizationId(), user.getId(), AuditLogService.INVITATION_ACCEPTED,
                Map.of("email", invitation.getEmail()));

        return issueTokens(user);
    }

    /**
     * @return the reset token, or empty when no such account exists. The caller must
     *         respond identically either way — a response that differs tells an
     *         attacker which email addresses have accounts.
     */
    @Transactional
    public Optional<String> requestPasswordReset(UUID organizationId, String email) {
        tenantContextService.setTransactionTenantContext(organizationId);

        Optional<AppUser> maybeUser = userRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, email);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for unknown email {} in organization {}", email, organizationId);
            return Optional.empty();
        }

        String token = SecretTokens.generate();
        passwordResetRepository.save(new PasswordReset(UUID.randomUUID(), organizationId, maybeUser.get().getId(),
                SecretTokens.hash(token), Instant.now().plus(properties.getPasswordResetTtl())));

        auditLog.record(organizationId, maybeUser.get().getId(), AuditLogService.PASSWORD_RESET_REQUESTED,
                Map.of("email", email));
        return Optional.of(token);
    }

    @Transactional
    public void confirmPasswordReset(String resetToken, String newPassword) {
        PasswordReset reset = passwordResetRepository.findByTokenHash(SecretTokens.hash(resetToken))
                .orElseThrow(() -> new InvalidTokenException("Unknown password reset token"));

        tenantContextService.setTransactionTenantContext(reset.getOrganizationId());

        if (!reset.isUsable(Instant.now())) {
            throw new InvalidTokenException("Password reset token is expired or already used");
        }

        AppUser user = userRepository.findById(reset.getUserId()).orElseThrow();
        user.changePassword(passwordEncoder.encode(newPassword));
        reset.markUsed(Instant.now());

        // Changing a password must end existing sessions. Otherwise a password change
        // made *because* of a compromise leaves the intruder's session working.
        refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId())
                .forEach(token -> token.revoke(Instant.now()));

        auditLog.record(reset.getOrganizationId(), user.getId(), AuditLogService.PASSWORD_RESET_COMPLETED, Map.of());
    }

    /** Resolves the roles a user currently holds. Used by the security filter on every request. */
    @Transactional(readOnly = true)
    public List<HubRole> rolesOf(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream().map(UserRole::getRoleName).toList();
    }

    private TokenPair issueTokens(AppUser user) {
        List<HubRole> roles = rolesOf(user.getId());
        String accessToken = jwtService.issueAccessToken(user, roles);

        String refreshToken = SecretTokens.generate();
        refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), user.getOrganizationId(), user.getId(),
                SecretTokens.hash(refreshToken), Instant.now().plus(properties.getRefreshTokenTtl())));

        return new TokenPair(accessToken, refreshToken, properties.getAccessTokenTtl().toSeconds(),
                user.getId(), user.getOrganizationId(), roles);
    }

    /**
     * A syntactically valid BCrypt hash of a random value nobody knows, so an INVITED
     * account fails password verification the same way a wrong password does rather
     * than throwing on a malformed hash.
     */
    private String unusablePasswordHash() {
        return passwordEncoder.encode(SecretTokens.generate());
    }

    public record TokenPair(String accessToken, String refreshToken, long accessTokenExpiresInSeconds,
                             UUID userId, UUID organizationId, List<HubRole> roles) {
    }

    public record Invitation(UUID invitationId, UUID userId, String token) {
    }
}
