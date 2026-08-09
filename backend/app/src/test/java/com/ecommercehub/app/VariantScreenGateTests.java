package com.ecommercehub.app;

import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.catalog.VariantScreenService;
import com.ecommercehub.domain.paging.PageRequest;
import com.ecommercehub.domain.paging.PageResponse;
import com.ecommercehub.domain.stock.StockAdjustmentConflictException;
import com.ecommercehub.domain.stock.StockAdjustmentReason;
import com.ecommercehub.domain.stock.StockAdjustmentService;
import com.ecommercehub.domain.stock.StockConsistencyService;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan v5 Faz 7 §7.5 gate: server pagination actually pages (never the whole table in
 * one request), search works across sku/barcode/title, a manual correction writes the
 * ledger + push + audit trail and leaves the nightly consistency check clean, a
 * reasonless correction is refused, OBSERVER cannot correct stock, and two concurrent
 * corrections leave the second one seeing a conflict rather than silently overwriting.
 */
@SpringBootTest
public class VariantScreenGateTests extends AbstractTestcontainersTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private VariantScreenService variantScreenService;
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private StockAdjustmentService stockAdjustmentService;
    @Autowired private StockConsistencyService stockConsistencyService;

    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), orgId, "actor@test", List.of(role));
    }

    // =========================================================================
    // Gate: server pagination
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: the variant list pages on the server — no single request returns the whole table")
    void listPagesOnTheServer() {
        for (int i = 0; i < 120; i++) {
            insertVariant("SKU-PAGE-" + i, "Paged product " + i);
        }

        PageResponse<Map<String, Object>> page1 = inTenantReturning(
                () -> variantScreenService.list(orgId, new PageRequest(0, 50), null, null, null, null));
        PageResponse<Map<String, Object>> page2 = inTenantReturning(
                () -> variantScreenService.list(orgId, new PageRequest(1, 50), null, null, null, null));

        assertThat(page1.total()).isEqualTo(120);
        assertThat(page1.items())
                .withFailMessage("A page of 50 over 120 rows must return exactly 50, not the whole table")
                .hasSize(50);
        assertThat(page2.items()).hasSize(50);

        Set<Object> page1Ids = new HashSet<>();
        page1.items().forEach(row -> page1Ids.add(row.get("id")));
        Set<Object> page2Ids = new HashSet<>();
        page2.items().forEach(row -> page2Ids.add(row.get("id")));
        assertThat(page1Ids)
                .withFailMessage("Page 2 must be a different slice, not a repeat of page 1")
                .doesNotContainAnyElementsOf(page2Ids);
    }

    // =========================================================================
    // Gate: server-side search
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: search works on sku, barcode and title, all server-side")
    void searchWorksOnSkuBarcodeAndTitle() {
        UUID bySku = insertVariant("UNIQUE-SKU-TOKEN", "Ordinary title");
        UUID byBarcode = insertVariantWithBarcode("SKU-OTHER-1", "UNIQUE-BARCODE-TOKEN", "Ordinary title");
        UUID byTitle = insertVariant("SKU-OTHER-2", "Unique Title Token Product");
        insertVariant("SKU-UNRELATED", "Nothing to do with the search");

        assertThat(idsOf(searchFor("UNIQUE-SKU-TOKEN"))).containsExactly(bySku);
        assertThat(idsOf(searchFor("UNIQUE-BARCODE-TOKEN"))).containsExactly(byBarcode);
        assertThat(idsOf(searchFor("Unique Title Token"))).containsExactly(byTitle);
    }

    private PageResponse<Map<String, Object>> searchFor(String q) {
        return inTenantReturning(() -> variantScreenService.list(orgId, new PageRequest(0, 50), q, null, null, null));
    }

    private List<UUID> idsOf(PageResponse<Map<String, Object>> page) {
        return page.items().stream().map(row -> (UUID) row.get("id")).toList();
    }

    // =========================================================================
    // Gate: manual correction writes the ledger + push + audit, consistency check stays clean
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: a manual correction writes stock_movement + a push + an audit_log row, and the nightly consistency check finds nothing wrong")
    void manualAdjustmentIsFullyRecordedAndConsistent() {
        UUID variantId = insertVariant("SKU-ADJUST", "Adjustable product");
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 10, null));

        inTenant(() -> stockAdjustmentService.adjust(actor(HubRole.OPERATOR), variantId, 10, 14,
                StockAdjustmentReason.COUNT_DISCREPANCY, "shelf recount"));

        Map<String, Object> movement = jdbcTemplate.queryForMap("""
                SELECT quantity, reason, adjustment_reason, note, actor_user_id
                FROM hub.stock_movement WHERE variant_id = ? AND adjustment_reason IS NOT NULL
                """, variantId);
        assertThat(movement.get("quantity")).isEqualTo(4);
        assertThat(movement.get("reason")).isEqualTo("ON_HAND_INCREASE");
        assertThat(movement.get("adjustment_reason")).isEqualTo("COUNT_DISCREPANCY");
        assertThat(movement.get("note")).isEqualTo("shelf recount");

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM hub.stock WHERE variant_id = ?", Integer.class, variantId);
        assertThat(onHand).isEqualTo(14);

        Integer auditCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'STOCK_MANUALLY_ADJUSTED'
                """, Integer.class, orgId);
        assertThat(auditCount).isEqualTo(1);

        int inconsistent = inTenantReturning(() -> stockConsistencyService.checkOrganization(orgId));
        assertThat(inconsistent)
                .withFailMessage("A manual correction must be indistinguishable from any other on_hand movement "
                        + "to the nightly ledger replay")
                .isZero();
    }

    // =========================================================================
    // Gate: reasonless correction rejected
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: a stock correction with no reason is rejected")
    void reasonlessAdjustmentRejected() {
        UUID variantId = insertVariant("SKU-NO-REASON", "Product");
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 5, null));

        assertThatThrownBy(() -> stockAdjustmentService.adjust(actor(HubRole.OPERATOR), variantId, 5, 8, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM hub.stock WHERE variant_id = ?", Integer.class, variantId);
        assertThat(onHand).withFailMessage("A refused correction must leave the counter untouched").isEqualTo(5);
    }

    // =========================================================================
    // Gate: OBSERVER cannot adjust stock
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: OBSERVER cannot manually correct stock")
    void observerCannotAdjustStock() {
        UUID variantId = insertVariant("SKU-OBSERVER", "Product");
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 5, null));

        assertThatThrownBy(() -> stockAdjustmentService.adjust(actor(HubRole.OBSERVER), variantId, 5, 9,
                StockAdjustmentReason.OTHER, "nope"))
                .isInstanceOf(InsufficientRoleException.class);

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM hub.stock WHERE variant_id = ?", Integer.class, variantId);
        assertThat(onHand).isEqualTo(5);
    }

    // =========================================================================
    // Gate: optimistic lock — the second concurrent correction sees the conflict
    // =========================================================================

    @Test
    @DisplayName("Faz 7 gate: two concurrent corrections based on the same stale count — the second sees a conflict, never silently overwrites")
    void secondConcurrentAdjustmentSeesConflict() {
        UUID variantId = insertVariant("SKU-CONFLICT", "Product");
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 10, null));

        // Both operators loaded the screen when on_hand was 10.
        inTenant(() -> stockAdjustmentService.adjust(actor(HubRole.OPERATOR), variantId, 10, 12,
                StockAdjustmentReason.COUNT_DISCREPANCY, "first correction"));

        assertThatThrownBy(() -> stockAdjustmentService.adjust(actor(HubRole.OPERATOR), variantId, 10, 15,
                StockAdjustmentReason.COUNT_DISCREPANCY, "second correction, based on stale data"))
                .isInstanceOf(StockAdjustmentConflictException.class)
                .satisfies(e -> assertThat(((StockAdjustmentConflictException) e).getActualOnHand()).isEqualTo(12));

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM hub.stock WHERE variant_id = ?", Integer.class, variantId);
        assertThat(onHand)
                .withFailMessage("The rejected second correction must not have applied its delta on top of the first")
                .isEqualTo(12);
    }

    // =========================================================================
    // Bugfix gate: channels comes back as a real array, not a jsonb driver wrapper
    // =========================================================================

    /**
     * Bug found running Faz 8's real-Shopify verification: every prior browser check
     * of the Products screen happened to use a variant with zero channel mappings —
     * {@code jsonb_agg} returns SQL {@code NULL} for those, and the driver-wrapper
     * question never came up. A variant with a real mapping exposed it: pgjdbc hands a
     * {@code jsonb} column back as its own wrapper object, not a {@code String}, so
     * {@code channels} used to come back shaped {@code {"type":"jsonb","value":"[...]"}}
     * instead of the array {@code VariantChannelSummary[]} on the frontend expects.
     */
    @Test
    @DisplayName("Bugfix gate: a variant's channels field is a real parsed array, not a jsonb driver wrapper, once it has a mapping")
    void channelsFieldIsARealArrayOnceMapped() {
        UUID variantId = insertVariant("SKU-CHANNEL-MAPPED", "Mapped product");
        UUID channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials)
                VALUES (?, ?, 'MOCK', 'n/a')
                """, channelConnectionId, orgId);
        jdbcTemplate.update("""
                INSERT INTO hub.channel_product_mapping
                    (organization_id, variant_id, channel_connection_id, channel_product_id, channel_variant_id, mapping_source)
                VALUES (?, ?, ?, 'cp-1', 'cv-1', 'AUTO_SKU')
                """, orgId, variantId, channelConnectionId);

        PageResponse<Map<String, Object>> page = inTenantReturning(
                () -> variantScreenService.list(orgId, new PageRequest(0, 50), "SKU-CHANNEL-MAPPED", null, null, null));

        Object channels = page.items().get(0).get("channels");
        assertThat(channels)
                .withFailMessage("channels must be a real List, not a PGobject-shaped wrapper Jackson would "
                        + "serialize as {\"type\":\"jsonb\",\"value\":\"...\"} instead of an array")
                .isInstanceOf(List.class);
        assertThat((List<?>) channels).hasSize(1);

        Optional<Map<String, Object>> detail = inTenantReturning(() -> variantScreenService.detail(orgId, variantId));
        assertThat(detail).isPresent();
        assertThat(detail.get().get("channels")).isInstanceOf(List.class);
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private UUID insertVariant(String sku, String title) {
        return insertVariantWithBarcode(sku, null, title);
    }

    private UUID insertVariantWithBarcode(String sku, String barcode, String title) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, title);
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku, barcode) VALUES (?, ?, ?, ?, ?)",
                variantId, orgId, productId, sku, barcode);
        return variantId;
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
