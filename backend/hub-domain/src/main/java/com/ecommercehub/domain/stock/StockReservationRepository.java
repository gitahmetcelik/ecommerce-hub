package com.ecommercehub.domain.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByOrderItemId(UUID orderItemId);

    /** Only still-ticking reservations have a non-null expiresAt (Plan §3: payment clears it). */
    List<StockReservation> findByExpiresAtNotNullAndExpiresAtBefore(Instant cutoff);
}
