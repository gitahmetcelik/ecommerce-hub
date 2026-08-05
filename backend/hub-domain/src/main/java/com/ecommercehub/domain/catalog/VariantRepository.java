package com.ecommercehub.domain.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VariantRepository extends JpaRepository<Variant, UUID> {
    Optional<Variant> findByOrganizationIdAndSku(UUID organizationId, String sku);
}
