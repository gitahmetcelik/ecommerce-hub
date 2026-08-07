package com.ecommercehub.app;

import com.ecommercehub.app.backfill.BackfillCursor;
import com.ecommercehub.app.backfill.BackfillService;
import com.ecommercehub.app.backfill.ChannelBudgetRegistry;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 3 gate: resumable backfill against a real mock-pazaryeri instance —
 * MockPlatformConnector (already wired in the app context, Plan Phase 1) talks to
 * whichever base URL is decrypted from this test's own channel_connection row, so
 * no separate connector bean is needed per test.
 */
// pageSize=10 forces multiple pages (catalog: 11 items -> 2 pages, orders: 50 -> 5
// pages) while staying under BACKGROUND's 12-token share of the default 60/min budget
// (20% of 60) — deliberately chosen so this test's own tight retry loop never has to
// wait on the real-time budget refill to prove resumability across several cycles.
//
// cycle-period-ms is pushed out to effectively "never again" — BackfillScheduler's
// own periodic sweep would otherwise race the test's direct runOneCycle() calls on
// the very same channel_connection (both are real concurrent transactions against
// the same row), which surfaced as a genuine uk_sales_order_channel violation. The
// scheduler's one unavoidable startup-time fire happens before @BeforeEach inserts
// this test's connection, so it never sees it.
@SpringBootTest(properties = {"hub.backfill.page-size=10", "hub.backfill.cycle-period-ms=3600000"})
public class BackfillGateTests extends AbstractTestcontainersTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    static {
        mockPazaryeri.start();
    }

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private ChannelBudgetRegistry budgetRegistry;

    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        String baseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(baseUrl);
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, 'MOCK', ?, ?)
                """, channelConnectionId, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion());
    }

    @Test
    @DisplayName("Phase 3 gate: backfill runs catalog to completion, then orders to completion, without skipping either")
    void backfillCompletesCatalogThenOrders() {
        BackfillCursor cursor = runUntilComplete();

        assertThat(cursor.catalogDone()).isTrue();
        assertThat(cursor.ordersDone()).isTrue();

        Integer variantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);
        assertThat(variantCount).isGreaterThan(0);

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.sales_order WHERE organization_id = ?", Integer.class, orgId);
        assertThat(orderCount).isEqualTo(50); // mock-pazaryeri seeds exactly 50 orders
    }

    @Test
    @DisplayName("Phase 3 gate: resuming backfill after a simulated crash continues from the cursor, no duplicate rows")
    void resumingAfterCrashDoesNotDuplicate() {
        // Run partway, "crash" (nothing to undo — the whole point is the cursor already
        // survived on channel_connection.backfill_status), then keep going to completion.
        for (int i = 0; i < 3; i++) {
            backfillService.runOneCycle(orgId, channelConnectionId);
        }
        Integer variantCountAfterPartial = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);

        BackfillCursor finalCursor = runUntilComplete();
        assertThat(finalCursor.isComplete()).isTrue();

        Integer variantCountFinal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);
        Integer distinctSkuCount = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT sku) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);

        assertThat(variantCountFinal)
                .withFailMessage("Resuming must not recreate variants already imported before the simulated crash")
                .isEqualTo(distinctSkuCount);
        assertThat(variantCountFinal).isGreaterThanOrEqualTo(variantCountAfterPartial);

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.sales_order WHERE organization_id = ?", Integer.class, orgId);
        assertThat(orderCount).isEqualTo(50);
    }

    @Test
    @DisplayName("Phase 3 gate: backfill always runs under the BACKGROUND budget class, leaving INTERACTIVE untouched")
    void backfillUsesBackgroundBudgetLeavingInteractiveIntact() {
        RateLimitBudget budget = budgetRegistry.forConnection(orgId, channelConnectionId);

        // Drain BACKGROUND directly, simulating a backfill deep in its run — Plan §9's
        // separation is what lets a stock push triggered mid-backfill still go through
        // instead of waiting behind it (Phase 4 builds the actual push; this pins the
        // budget mechanism the backfill path itself calls into).
        int backgroundCapacity = 0;
        while (budget.tryAcquire(BudgetClass.BACKGROUND)) {
            backgroundCapacity++;
            if (backgroundCapacity > 1000) {
                break; // safety valve, should never trip
            }
        }
        assertThat(backgroundCapacity).isGreaterThan(0);

        assertThat(budget.tryAcquire(BudgetClass.INTERACTIVE))
                .withFailMessage("A stock push triggered while backfill has exhausted BACKGROUND must still succeed")
                .isTrue();
    }

    private BackfillCursor runUntilComplete() {
        BackfillCursor cursor = null;
        for (int i = 0; i < 100 && (cursor == null || !cursor.isComplete()); i++) {
            cursor = backfillService.runOneCycle(orgId, channelConnectionId);
        }
        return cursor;
    }
}
