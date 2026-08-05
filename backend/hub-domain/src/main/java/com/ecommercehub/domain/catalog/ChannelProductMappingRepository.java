package com.ecommercehub.domain.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChannelProductMappingRepository extends JpaRepository<ChannelProductMapping, UUID> {
    Optional<ChannelProductMapping> findByOrganizationIdAndChannelConnectionIdAndChannelVariantId(
            UUID organizationId, UUID channelConnectionId, String channelVariantId);
}
