package com.ecommercehub.app;

import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 2 gate: the ingest outbox — <200ms ACK, event-layer idempotency ("same
 * webhook 3x, one effect"), and the trace_id link between raw_event and work_batch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class IngestGateTests extends AbstractTestcontainersTest {

    private static final String SHARED_SIGNING_SECRET = "mock-shared-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        EncryptedCredential encrypted = credentialEncryptionService.encrypt("unused-for-mock");
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, 'MOCK', ?, ?)
                """, channelConnectionId, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion());
    }

    private String webhookBody(String eventId) {
        return """
                {"eventId":"%s","eventType":"order.created","eventAt":"%s","sequence":1,
                 "order":{"channelOrderNumber":"CO-%s","total":19.99,"currency":"USD",
                          "items":[{"sku":"SKU-INGEST","quantity":1,"unitPrice":19.99,"vatRate":0}]}}
                """.formatted(eventId, Instant.now(), eventId);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SHARED_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> postWebhook(String body, String signature) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/webhooks/" + orgId + "/" + channelConnectionId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (signature != null) {
            builder.header("X-Mock-Signature", signature);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Phase 2 gate: a validly-signed webhook is ACKed and produces exactly one raw_event and one work_batch row")
    void validWebhookIsAckedAndWritesOutbox() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId);

        HttpResponse<String> response = postWebhook(body, sign(body));
        assertThat(response.statusCode()).isEqualTo(200);

        Integer rawEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.raw_event WHERE channel_event_id = ?", Integer.class, eventId);
        assertThat(rawEventCount).isEqualTo(1);

        Integer workBatchCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.work_batch WHERE task_key = ? AND task_type = 'process-order-event'",
                Integer.class, eventId);
        assertThat(workBatchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 2 gate: the same webhook delivered 3 times has exactly one effect, not three")
    void sameWebhookThreeTimesHasOneEffect() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId);
        String signature = sign(body);

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = postWebhook(body, signature);
            assertThat(response.statusCode()).isEqualTo(200);
        }

        Integer rawEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.raw_event WHERE channel_event_id = ?", Integer.class, eventId);
        assertThat(rawEventCount).isEqualTo(1);

        Integer workBatchCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.work_batch WHERE task_key = ?", Integer.class, eventId);
        assertThat(workBatchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 2 gate: a badly-signed webhook is rejected and never reaches raw_event")
    void invalidSignatureIsRejected() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId);

        HttpResponse<String> response = postWebhook(body, "not-the-real-signature");
        assertThat(response.statusCode()).isEqualTo(401);

        Integer rawEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.raw_event WHERE channel_event_id = ?", Integer.class, eventId);
        assertThat(rawEventCount).isZero();
    }

    @Test
    @DisplayName("Phase 2 gate: raw_event and its work_batch row share one trace_id end to end")
    void rawEventAndWorkBatchShareOneTraceId() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId);

        postWebhook(body, sign(body));

        String rawEventTrace = jdbcTemplate.queryForObject(
                "SELECT trace_id FROM hub.raw_event WHERE channel_event_id = ?", String.class, eventId);
        String workBatchTrace = jdbcTemplate.queryForObject(
                "SELECT trace_id FROM hub.work_batch WHERE task_key = ?", String.class, eventId);

        assertThat(rawEventTrace).isNotBlank();
        assertThat(workBatchTrace).isEqualTo(rawEventTrace);
    }
}
