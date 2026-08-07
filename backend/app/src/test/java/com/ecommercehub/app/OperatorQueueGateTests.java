package com.ecommercehub.app;

import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.queue.OperatorQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate for the arayuz-2 addition: most operator_queue types (CHANNEL_CREDENTIALS_INVALID,
 * RETURN_UNRESOLVABLE, DISPATCH_TIMEOUT, INTENT_AMBIGUOUS, ORDER_ITEM_ESCALATION) have no
 * domain event that ever closes them — the human resolves them outside this system. Dismiss
 * is the acknowledgement of that, and it must always leave a reason on the record.
 */
@SpringBootTest
public class OperatorQueueGateTests extends AbstractTestcontainersTest {

    @Autowired
    private OperatorQueueService operatorQueueService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        userId = UUID.randomUUID();
    }

    private UUID seedQueueItem(String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (?, ?, ?, 'test row', NULL)
                """, id, orgId, type);
        return id;
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(userId, orgId, "actor@test", List.of(role));
    }

    @Test
    @DisplayName("Dismissing a pending row closes it and records why, not just that it was closed")
    void dismissClosesRowAndRecordsReason() {
        UUID itemId = seedQueueItem("CHANNEL_CREDENTIALS_INVALID");

        operatorQueueService.dismiss(actor(HubRole.OPERATOR), itemId, "Re-authorised manually in the Trendyol seller panel");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.operator_queue WHERE id = ?", String.class, itemId);
        assertThat(status).isEqualTo("RESOLVED");

        Map<String, Object> auditRow = jdbcTemplate.queryForMap(
                "SELECT user_id, details FROM hub.audit_log WHERE organization_id = ? AND action = 'OPERATOR_QUEUE_DISMISSED'",
                orgId);
        assertThat(auditRow.get("user_id")).isEqualTo(userId);
        assertThat(auditRow.get("details").toString()).contains("Trendyol seller panel");
    }

    @Test
    @DisplayName("A blank reason is rejected rather than silently accepted")
    void dismissRequiresNonBlankReason() {
        UUID itemId = seedQueueItem("DISPATCH_TIMEOUT");

        assertThatThrownBy(() -> operatorQueueService.dismiss(actor(HubRole.OPERATOR), itemId, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.operator_queue WHERE id = ?", String.class, itemId);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Dismissing an already-resolved row fails instead of writing a second audit entry for nothing")
    void dismissingAlreadyResolvedRowFails() {
        UUID itemId = seedQueueItem("ORDER_ITEM_ESCALATION");
        operatorQueueService.dismiss(actor(HubRole.OPERATOR), itemId, "First dismissal");

        assertThatThrownBy(() -> operatorQueueService.dismiss(actor(HubRole.OPERATOR), itemId, "Second attempt"))
                .isInstanceOf(IllegalArgumentException.class);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'OPERATOR_QUEUE_DISMISSED'",
                Integer.class, orgId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("v5 Faz 2 gate H6: an OBSERVER cannot dismiss a queue item, and the refusal is audited")
    void observerCannotDismiss() {
        UUID itemId = seedQueueItem("CHANNEL_CREDENTIALS_INVALID");

        assertThatThrownBy(() -> operatorQueueService.dismiss(actor(HubRole.OBSERVER), itemId, "trying anyway"))
                .isInstanceOf(InsufficientRoleException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.operator_queue WHERE id = ?", String.class, itemId);
        assertThat(status)
                .withFailMessage("A refused dismiss must leave the row exactly as it was")
                .isEqualTo("PENDING");

        Integer deniedCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.audit_log
                WHERE organization_id = ? AND action = 'PERMISSION_DENIED'
                """, Integer.class, orgId);
        assertThat(deniedCount).isEqualTo(1);
    }
}
