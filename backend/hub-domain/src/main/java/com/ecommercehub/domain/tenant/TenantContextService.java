package com.ecommercehub.domain.tenant;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TenantContextService {

    private final EntityManager entityManager;

    public TenantContextService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Sets the Postgres RLS session variable transaction-locally (SET LOCAL).
     * The third argument {@code true} makes it drop automatically on commit/rollback,
     * so it can never leak into the next request on a pooled connection.
     */
    @Transactional
    public void setTransactionTenantContext(UUID organizationId) {
        TenantContext.setOrganizationId(organizationId);
        entityManager.createNativeQuery("SELECT set_config('hub.org_id', :orgId, true)")
                .setParameter("orgId", organizationId.toString())
                .getSingleResult();
    }
}
