package com.ecommercehub.domain.tenant;

import java.util.UUID;

/**
 * Thread-local holder for the current tenant (organization) id.
 */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setOrganizationId(UUID organizationId) {
        CURRENT_TENANT.set(organizationId);
    }

    public static UUID getOrganizationId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
