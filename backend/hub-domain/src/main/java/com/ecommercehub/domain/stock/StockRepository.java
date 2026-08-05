package com.ecommercehub.domain.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StockRepository extends JpaRepository<Stock, UUID> {

    Optional<Stock> findByOrganizationIdAndVariantId(UUID organizationId, UUID variantId);

    /** Pessimistic lock — every counter adjustment must serialize on this row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.organizationId = ?1 and s.variantId = ?2")
    Optional<Stock> findByOrganizationIdAndVariantIdForUpdate(UUID organizationId, UUID variantId);
}
