package com.ecommercehub.domain.vo;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskKeyTests {

    @Test
    void asTextJoinsTheThreeSegmentsWithAColon() {
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TaskKey key = new TaskKey(orgId, "push-gonder", "channel-conn-42:2026-08-05T10:00:00Z");

        assertThat(key.asText()).isEqualTo(orgId + ":push-gonder:channel-conn-42:2026-08-05T10:00:00Z");
    }

    @Test
    void rejectsANullOrganizationId() {
        assertThatThrownBy(() -> new TaskKey(null, "type", "key"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullTaskType() {
        assertThatThrownBy(() -> new TaskKey(UUID.randomUUID(), null, "key"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullBusinessKey() {
        assertThatThrownBy(() -> new TaskKey(UUID.randomUUID(), "type", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void twoDifferentOrganizationsWithTheSameTypeAndBusinessKeyNeverCollide() {
        String businessKey = "same-business-key";
        TaskKey orgAKey = new TaskKey(UUID.randomUUID(), "olay-isle", businessKey);
        TaskKey orgBKey = new TaskKey(UUID.randomUUID(), "olay-isle", businessKey);

        assertThat(orgAKey.asText())
                .withFailMessage("The org segment must namespace the key — the engine's own idempotency_anahtari has no tenant column")
                .isNotEqualTo(orgBKey.asText());
    }
}
