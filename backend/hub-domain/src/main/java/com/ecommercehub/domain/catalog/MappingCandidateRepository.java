package com.ecommercehub.domain.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MappingCandidateRepository extends JpaRepository<MappingCandidate, UUID> {

    Optional<MappingCandidate> findByOrganizationIdAndChannelConnectionIdAndChannelVariantId(
            UUID organizationId, UUID channelConnectionId, String channelVariantId);

    List<MappingCandidate> findByOrganizationIdAndStatus(UUID organizationId, String status);
}
