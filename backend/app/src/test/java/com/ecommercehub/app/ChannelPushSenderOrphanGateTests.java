package com.ecommercehub.app;

import com.ecommercehub.app.push.ChannelPushSender;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug found running Faz 8's real-Shopify verification: {@code
 * ChannelPushSender.callAndClose} used to build its {@code channelVariantId} lookup
 * map <em>before</em> its try block. {@code claim()} had already committed those rows
 * as SENDING in a separate transaction (Plan §3/Phase 4's three-transaction design);
 * a single row with corrupt or incomplete {@code target_value} JSON then threw
 * straight out of {@code callAndClose} with no path back to {@code releaseToPending}.
 * {@code ChannelPushStore.claimPending} only ever selects {@code status = 'PENDING'},
 * so those rows stayed SENDING forever — silently and permanently lost, invisible to
 * every future window. The fix moves that map-building into the existing try block,
 * so the same {@code catch (RuntimeException e)} that already releases the window on
 * a connector failure also covers a malformed row.
 */
@SpringBootTest
public class ChannelPushSenderOrphanGateTests extends AbstractTestcontainersTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChannelPushSender pushSender;
    @Autowired private CredentialEncryptionService credentialEncryptionService;

    private UUID orgId;
    private UUID channelConnectionId;
    private UUID variantId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        channelConnectionId = UUID.randomUUID();
        // claim() decrypts credentials before the malformed row is ever read, so this
        // needs a real ciphertext (the mock connector is never actually called — the
        // failure happens earlier, while building the channelVariantId lookup map).
        EncryptedCredential encrypted = credentialEncryptionService.encrypt("http://localhost:9999");
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, 'MOCK', ?, ?)
                """, channelConnectionId, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion());

        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)", productId, orgId, "Product");
        variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, "SKU-ORPHAN-TEST");
    }

    @Test
    @DisplayName("Bugfix gate: a row with malformed target_value is returned to PENDING, never orphaned in SENDING")
    void malformedRowIsReleasedNotOrphaned() {
        // Missing channelVariantId — exactly what claim()'s already-committed SENDING
        // status used to leave permanently stuck once this was read outside the try.
        jdbcTemplate.update("""
                INSERT INTO hub.channel_push (id, organization_id, channel_connection_id, variant_id, type, target_value)
                VALUES (gen_random_uuid(), ?, ?, ?, 'STOCK', '{"quantity": 5}'::jsonb)
                """, orgId, channelConnectionId, variantId);

        pushSender.sendWindow(orgId, channelConnectionId, "STOCK");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.channel_push WHERE variant_id = ?", String.class, variantId);
        assertThat(status)
                .withFailMessage("A malformed row must be released back to PENDING, not orphaned in SENDING forever")
                .isEqualTo("PENDING");
    }
}
