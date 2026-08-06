package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.AuthenticationService;
import com.ecommercehub.domain.auth.HubRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the first administrator of an organization.
 *
 * <p><b>Why this has to exist.</b> Plan §10 has an organization admin invite users, and
 * §13 puts self-service onboarding out of scope for v1. Between the two there was no way
 * to get the <em>first</em> admin: inviting requires an ADMIN, and there was none. A
 * freshly deployed hub could not be logged into at all.
 *
 * <p>Configured, idempotent, and quiet by default: it does nothing unless
 * {@code hub.bootstrap.admin-email} is set, and nothing at all if the organization
 * already has a user. The invitation token is logged rather than emailed (v1 sends no
 * email) — which is exactly why this belongs in an operator's hands and not on a public
 * endpoint. It creates an INVITED account, so the password is still chosen by the person
 * who accepts, never by whoever ran the deploy.
 */
@Component
public class BootstrapAdminProvisioner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminProvisioner.class);

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final AuthenticationService authenticationService;
    private final String organizationName;
    private final String adminEmail;

    public BootstrapAdminProvisioner(@Qualifier("systemJdbcTemplate") NamedParameterJdbcTemplate systemJdbcTemplate,
                                      AuthenticationService authenticationService,
                                      @Value("${hub.bootstrap.organization-name:}") String organizationName,
                                      @Value("${hub.bootstrap.admin-email:}") String adminEmail) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.authenticationService = authenticationService;
        this.organizationName = organizationName;
        this.adminEmail = adminEmail;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }

        UUID organizationId = findOrCreateOrganization();
        if (hasAnyUser(organizationId)) {
            log.info("Bootstrap skipped — organization {} already has users", organizationId);
            return;
        }

        var invitation = authenticationService.invite(organizationId, null, adminEmail, adminEmail, HubRole.ADMIN);

        log.warn("""

                =========================== HUB BOOTSTRAP ===========================
                Organization : {}
                Admin email  : {}
                Invitation   : {}
                Accept it with:
                  POST /auth/invitations/accept {{"token":"{}","password":"...","fullName":"..."}}
                =====================================================================
                """, organizationId, adminEmail, invitation.token(), invitation.token());
    }

    private UUID findOrCreateOrganization() {
        String name = organizationName == null || organizationName.isBlank() ? "Default" : organizationName;

        List<UUID> existing = systemJdbcTemplate.queryForList(
                "SELECT id FROM hub.organization WHERE name = :name", Map.of("name", name), UUID.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        UUID id = UUID.randomUUID();
        systemJdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (:id, :name)",
                new MapSqlParameterSource().addValue("id", id).addValue("name", name));
        log.info("Bootstrap created organization {} ({})", name, id);
        return id;
    }

    private boolean hasAnyUser(UUID organizationId) {
        Integer count = systemJdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.app_user WHERE organization_id = :org",
                Map.of("org", organizationId), Integer.class);
        return count != null && count > 0;
    }
}
