package com.ecommercehub.domain.price;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PriceRepository extends JpaRepository<Price, UUID> {

    Optional<Price> findByOrganizationIdAndVariantId(UUID organizationId, UUID variantId);

    /** Pessimistic lock — concurrent list-price writes for the same variant must serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Price p where p.organizationId = ?1 and p.variantId = ?2")
    Optional<Price> findByOrganizationIdAndVariantIdForUpdate(UUID organizationId, UUID variantId);
}
