package com.ecommercehub.app;

import com.ecommercehub.domain.intent.ChannelCallIntent;
import com.ecommercehub.domain.intent.ChannelCallIntentService;
import com.ecommercehub.domain.intent.DuplicateIntentException;
import com.ecommercehub.domain.intent.IntentStatus;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the crash-recovery contract from plan §3/§4.3 without a real connector
 * (Faz 1) by standing in a fake {@link com.ecommercehub.domain.intent.IntentStatusResolver}
 * for "ask the channel what happened."
 */
@SpringBootTest
public class ChannelCallIntentGateTests extends AbstractTestcontainersTest {

    @Autowired
    private ChannelCallIntentService intentService;

    @Autowired
    private TenantContextService tenantContextService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials)
                VALUES (?, ?, 'MOCK', 'n/a')
                """, channelConnectionId, orgId);
        tenantContextService.setTransactionTenantContext(orgId);
    }

    @Test
    @DisplayName("prepare() commits a PREPARED intent whose own id is the channel idempotency key")
    void prepareCommitsAPreparedIntent() {
        UUID targetReference = UUID.randomUUID();
        ChannelCallIntent intent = intentService.prepare(orgId, channelConnectionId, "KARGO_OLUSTUR", targetReference, "{}");

        assertThat(intent.getStatus()).isEqualTo(IntentStatus.PREPARED);
        assertThat(intent.getChannelIdempotencyKey()).isEqualTo(intent.getId().toString());
    }

    @Test
    @DisplayName("A second intent for the same (type, targetReference) is rejected — a second intent for a different one is not")
    void duplicateTargetReferenceIsRejectedDifferentOneIsNot() {
        UUID shipmentId = UUID.randomUUID();
        intentService.prepare(orgId, channelConnectionId, "KARGO_OLUSTUR", shipmentId, "{}");

        assertThatThrownBy(() -> intentService.prepare(orgId, channelConnectionId, "KARGO_OLUSTUR", shipmentId, "{}"))
                .isInstanceOfAny(DataIntegrityViolationException.class, DuplicateIntentException.class);

        UUID secondShipmentId = UUID.randomUUID();
        assertThat(intentService.prepare(orgId, channelConnectionId, "KARGO_OLUSTUR", secondShipmentId, "{}"))
                .withFailMessage("A different kargo.id (partial re-shipment) must be representable, not blocked")
                .isNotNull();
    }

    @Test
    @DisplayName("The normal flow: PREPARED -> SENT -> RESULT_RECEIVED")
    void normalFlowReachesResultReceived() {
        ChannelCallIntent intent = intentService.prepare(orgId, channelConnectionId, "IADE_KARARI", UUID.randomUUID(), "{}");
        intentService.markSent(intent.getId());
        intentService.recordResult(intent.getId(), "{\"result\":\"ok\"}");

        ChannelCallIntent reloaded = reload(intent.getId());
        assertThat(reloaded.getStatus()).isEqualTo(IntentStatus.RESULT_RECEIVED);
        assertThat(reloaded.getChannelResponse()).contains("ok");
    }

    @Test
    @DisplayName("Crash recovery: a SENT intent whose durumSorgula resolves is closed without a second call")
    void stuckIntentResolvedByStatusQueryReachesResultReceived() {
        ChannelCallIntent intent = intentService.prepare(orgId, channelConnectionId, "PARA_IADESI", UUID.randomUUID(), "{}");
        intentService.markSent(intent.getId());
        // Process "crashes" here — no recordResult call. Recovery must query, not re-send.

        int resolved = intentService.recoverStuckIntents(
                probed -> Optional.of("{\"resolvedVia\":\"durumSorgula\"}"), Duration.ZERO);

        assertThat(resolved).isEqualTo(1);
        assertThat(reload(intent.getId()).getStatus()).isEqualTo(IntentStatus.RESULT_RECEIVED);
    }

    @Test
    @DisplayName("Crash recovery: a SENT intent the channel can't resolve either becomes AMBIGUOUS and reaches the operator queue")
    void stuckIntentUnresolvedByStatusQueryEscalates() {
        ChannelCallIntent intent = intentService.prepare(orgId, channelConnectionId, "PARA_IADESI", UUID.randomUUID(), "{}");
        intentService.markSent(intent.getId());

        int resolved = intentService.recoverStuckIntents(probed -> Optional.empty(), Duration.ZERO);

        assertThat(resolved).isEqualTo(0);
        assertThat(reload(intent.getId()).getStatus()).isEqualTo(IntentStatus.AMBIGUOUS);

        Integer operatorQueueCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.operator_queue WHERE reference_id = ? AND type = 'INTENT_AMBIGUOUS'",
                Integer.class, intent.getId());
        assertThat(operatorQueueCount).isEqualTo(1);
    }

    @Test
    @DisplayName("A PREPARED intent that was never sent is left alone by recovery — it never reached the channel")
    void neverSentIntentIsIgnoredByRecovery() {
        ChannelCallIntent intent = intentService.prepare(orgId, channelConnectionId, "KARGO_OLUSTUR", UUID.randomUUID(), "{}");

        int resolved = intentService.recoverStuckIntents(probed -> Optional.of("{}"), Duration.ZERO);

        assertThat(resolved).isEqualTo(0);
        assertThat(reload(intent.getId()).getStatus()).isEqualTo(IntentStatus.PREPARED);
    }

    private ChannelCallIntent reload(UUID intentId) {
        return intentService.findById(intentId)
                .orElseThrow(() -> new AssertionError("Intent " + intentId + " vanished"));
    }
}
