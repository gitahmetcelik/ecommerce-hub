package com.ecommercehub.app.security;

import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The single place that answers "who is this request, and which tenant are they in".
 *
 * <p>Endpoints call {@link #organizationId()} instead of accepting an organizationId
 * parameter. Anything that takes the tenant from the caller is trusting the caller
 * with the one value the whole isolation model rests on.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException(
                    "No authenticated user on this request — the endpoint should not have been reachable unauthenticated");
        }
        return user;
    }

    public static UUID organizationId() {
        return require().organizationId();
    }

    public static UUID userId() {
        return require().userId();
    }

    /**
     * @throws InsufficientRoleException when the caller outranks nothing — thrown rather
     *         than returned as a boolean so a forgotten check cannot read as "allowed".
     */
    public static void requireRole(HubRole required) {
        AuthenticatedUser user = require();
        if (!user.hasAtLeast(required)) {
            throw new InsufficientRoleException(user.effectiveRole(), required);
        }
    }
}
