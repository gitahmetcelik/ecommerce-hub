package com.ecommercehub.domain.intent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelCallIntentRepository extends JpaRepository<ChannelCallIntent, UUID> {

    Optional<ChannelCallIntent> findByOrganizationIdAndTypeAndTargetReference(
            UUID organizationId, String type, UUID targetReference);

    List<ChannelCallIntent> findByStatusAndUpdatedAtBefore(IntentStatus status, Instant cutoff);
}
