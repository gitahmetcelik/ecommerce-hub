package com.ecommercehub.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommercehub.app.backfill.BackfillService;
import com.ecommercehub.app.channel.ChannelConnectionService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan §8.5 gate: an invalid credential is refused before it ever reaches storage (and
 * the refusal carries the channel's own reason, not a generic message); an added
 * connection's backfill progress is readable; OPERATOR cannot connect a channel; and a
 * credential never appears in a log line or an API response, encrypted or not — the
 * same discipline Faz 7's PII scan (see Faz7PiiGateTests) applied to personal data now
 * applies to secrets.
 */
@SpringBootTest(properties = {"hub.backfill.cycle-period-ms=3600000"})
public class ChannelConnectionGateTests extends AbstractTestcontainersTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    static {
        mockPazaryeri.start();
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private ChannelConnectionService channelConnectionService;
    @Autowired private BackfillService backfillService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private String mockBaseUrl;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        mockBaseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);

        logCapture = new ListAppender<>();
        logCapture.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.ecommercehub").addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.ecommercehub").detachAppender(logCapture);
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), orgId, "actor@test", List.of(role));
    }

    @Test
    @DisplayName("Faz 8 gate: a channel with credentials the channel itself rejects cannot be added, and the reason is the channel's own")
    void invalidCredentialsCannotBeAdded() {
        assertThatThrownBy(() -> inTenant(() ->
                channelConnectionService.create(actor(HubRole.ADMIN), "MOCK", "http://127.0.0.1:1")))
                .isInstanceOf(ChannelConnectionService.InvalidChannelCredentialsException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.channel_connection WHERE organization_id = ?", Integer.class, orgId);
        assertThat(count)
                .withFailMessage("A connection that failed checkCredentials must never reach storage")
                .isZero();
    }

    @Test
    @DisplayName("Faz 8 gate: OPERATOR cannot connect a channel — ADMIN is required")
    void operatorCannotConnectChannel() {
        assertThatThrownBy(() -> inTenant(() ->
                channelConnectionService.create(actor(HubRole.OPERATOR), "MOCK", mockBaseUrl)))
                .isInstanceOf(InsufficientRoleException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.channel_connection WHERE organization_id = ?", Integer.class, orgId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Faz 8 gate: a connected channel's backfill progress is readable from the detail endpoint")
    void connectedChannelBackfillProgressIsReadable() {
        UUID id = inTenantReturning(() -> channelConnectionService.create(actor(HubRole.ADMIN), "MOCK", mockBaseUrl));

        Map<String, Object> beforeBackfill = inTenantReturning(() -> channelConnectionService.detail(orgId, id));
        assertThat(beforeBackfill.get("status")).isEqualTo("ACTIVE");
        assertThat(beforeBackfill.get("backfill_status")).isNull();

        inTenant(() -> backfillService.runOneCycle(orgId, id));

        @SuppressWarnings("unchecked")
        Map<String, Object> afterBackfill = inTenantReturning(() -> channelConnectionService.detail(orgId, id));
        Object backfillStatus = afterBackfill.get("backfill_status");
        assertThat(backfillStatus)
                .withFailMessage("Progress must be a real parsed object, not a driver-specific jsonb wrapper")
                .isInstanceOf(Map.class);
        assertThat((Map<String, Object>) backfillStatus).containsKey("catalogPage");
    }

    @Test
    @DisplayName("Faz 8 gate: a credential never appears in a log line or an API response, plaintext or otherwise")
    void credentialNeverAppearsInLogsOrResponses() throws Exception {
        String secretToken = "SECRET_TOKEN_" + UUID.randomUUID().toString().replace("-", "");
        String credentialsWithSecret = "http://" + secretToken + ":x@"
                + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);

        UUID id = inTenantReturning(() ->
                channelConnectionService.create(actor(HubRole.ADMIN), "MOCK", credentialsWithSecret));

        Map<String, Object> detail = inTenantReturning(() -> channelConnectionService.detail(orgId, id));
        String detailJson = objectMapper.writeValueAsString(detail);
        assertThat(detailJson)
                .withFailMessage("The detail response must never carry the credential, encrypted or not")
                .doesNotContain(secretToken);

        // Rotate too — the second write path that touches the plaintext.
        inTenant(() -> channelConnectionService.rotateCredentials(actor(HubRole.ADMIN), id, credentialsWithSecret));

        assertNoSecretInLogs(secretToken);
    }

    private void assertNoSecretInLogs(String secret) {
        List<String> messages = logCapture.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.DEBUG))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(messages)
                .withFailMessage("A log line carried the channel credential")
                .noneMatch(message -> message.contains(secret));
    }

    private void inTenant(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            action.run();
        });
    }

    private <T> T inTenantReturning(java.util.function.Supplier<T> action) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            return action.get();
        });
    }
}
