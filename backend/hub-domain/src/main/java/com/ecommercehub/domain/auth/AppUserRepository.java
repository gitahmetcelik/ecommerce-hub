package com.ecommercehub.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Scoped by organization because the uniqueness constraint is (organization_id,
     * email) — the same person working for two tenants is two accounts, so an email
     * on its own does not identify a user.
     */
    Optional<AppUser> findByOrganizationIdAndEmailIgnoreCase(UUID organizationId, String email);
}
