package com.ecommercehub.domain.auth;

import java.util.List;
import java.util.UUID;

/**
 * Who is making the current request, as established by the access token.
 *
 * <p>{@code organizationId} comes from a signed claim, never from the request — see
 * {@link JwtService}. Any code that needs the tenant asks this, which is what makes
 * "read another organization's data by editing the URL" structurally impossible
 * rather than merely unlikely.
 */
public record AuthenticatedUser(UUID userId, UUID organizationId, String email, List<HubRole> roles) {

    /** The highest role held, or OBSERVER when none is granted — never an implicit escalation. */
    public HubRole effectiveRole() {
        return roles.stream().max(java.util.Comparator.comparingInt(Enum::ordinal)).orElse(HubRole.OBSERVER);
    }

    public boolean hasAtLeast(HubRole required) {
        return effectiveRole().atLeast(required);
    }
}
