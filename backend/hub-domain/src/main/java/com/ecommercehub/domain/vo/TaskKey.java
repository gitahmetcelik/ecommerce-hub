package com.ecommercehub.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * The task engine is not multi-tenant (gorevler.idempotency_anahtari is a global
 * UNIQUE column), so every task key must be produced through this single value
 * object. Format: {organizationId}:{taskType}:{businessKey}
 */
public record TaskKey(UUID organizationId, String taskType, String businessKey) {
    public TaskKey {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
    }

    public String asText() {
        return organizationId + ":" + taskType + ":" + businessKey;
    }
}
