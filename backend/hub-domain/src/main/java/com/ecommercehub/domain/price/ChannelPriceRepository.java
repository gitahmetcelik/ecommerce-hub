package com.ecommercehub.domain.price;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ChannelPriceRepository extends JpaRepository<ChannelPrice, UUID> {

    Optional<ChannelPrice> findByOrganizationIdAndChannelConnectionIdAndVariantId(
            UUID organizationId, UUID channelConnectionId, UUID variantId);

    /** Pessimistic lock — concurrent writes to the same channel override must serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cp from ChannelPrice cp where cp.organizationId = ?1 and cp.channelConnectionId = ?2 and cp.variantId = ?3")
    Optional<ChannelPrice> findByOrganizationIdAndChannelConnectionIdAndVariantIdForUpdate(
            UUID organizationId, UUID channelConnectionId, UUID variantId);
}
