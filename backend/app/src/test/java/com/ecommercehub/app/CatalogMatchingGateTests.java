package com.ecommercehub.app;

import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.paging.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan Phase 3 gate: unmatched catalog items never silently vanish, stock is never
 * touched for them, and SKU is always tried before barcode.
 */
@SpringBootTest
public class CatalogMatchingGateTests extends AbstractTestcontainersTest {

    @Autowired
    private CatalogMatchingService catalogMatchingService;

    @Autowired
    private OrderProcessingService orderProcessingService;

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
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), orgId, "actor@test", List.of(role));
    }

    private UUID seedVariant(String sku, String barcode) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)", productId, orgId, sku);
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku, barcode) VALUES (?, ?, ?, ?, ?)",
                variantId, orgId, productId, sku, barcode);
        return variantId;
    }

    /**
     * Bug found running Faz 8's real-Shopify verification: a dev-store item with
     * neither field (a Gift Card-shaped product) made {@code importFromChannel} throw
     * from deep inside {@code createVariant}'s SKU-minting, which rolled back the
     * whole backfill cycle and made {@code BackfillScheduler} retry the same page
     * forever, every 5 seconds, against a live channel. The fix mirrors {@link
     * CatalogMatchingService#resolve}'s own unmatched-item path — queue it, return
     * null, let the rest of the page's items still import.
     */
    @Test
    @DisplayName("Bugfix gate: a catalog item with neither sku nor barcode is queued for review, not thrown, and does not block the rest of the page")
    void catalogItemWithNeitherSkuNorBarcodeIsQueuedNotThrown() {
        UUID result = catalogMatchingService.importFromChannel(
                orgId, channelConnectionId, "cp-giftcard", "cv-giftcard", null, null, "Gift Card");

        assertThat(result)
                .withFailMessage("Nothing to identify the item by — no variant can be minted for it")
                .isNull();

        Integer candidateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.mapping_candidate WHERE organization_id = ? AND channel_variant_id = 'cv-giftcard'",
                Integer.class, orgId);
        assertThat(candidateCount).isEqualTo(1);

        Integer operatorQueueCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.operator_queue WHERE organization_id = ? AND type = 'UNMATCHED_CATALOG_ITEM'",
                Integer.class, orgId);
        assertThat(operatorQueueCount).isEqualTo(1);

        Integer variantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);
        assertThat(variantCount).withFailMessage("No variant must be created when there is no identifier to give it").isZero();

        // The rest of the page must still import — one bad item cannot poison the cycle.
        UUID normalResult = catalogMatchingService.importFromChannel(
                orgId, channelConnectionId, "cp-normal", "cv-normal", "SKU-NORMAL", null, "Ordinary product");
        assertThat(normalResult).isNotNull();
    }

    @Test
    @DisplayName("Phase 3 gate: a completely unmatched item is queued for review, not silently dropped, and touches no stock")
    void unmatchedItemIsQueuedNotDropped() {
        OrderEventPayload event = new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(),
                "CO-" + UUID.randomUUID(), Instant.now(), 1L, new BigDecimal("19.99"), "USD",
                List.of(new OrderEventPayload.OrderEventItem("UNKNOWN-SKU", "cp-1", "cv-1", null,
                        1, new BigDecimal("19.99"), BigDecimal.ZERO, OrderItemStatus.CREATED)),
                UUID.randomUUID().toString());

        orderProcessingService.process(event);

        Integer candidateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.mapping_candidate WHERE organization_id = ? AND channel_variant_id = 'cv-1'",
                Integer.class, orgId);
        assertThat(candidateCount).isEqualTo(1);

        Integer operatorQueueCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.operator_queue WHERE organization_id = ? AND type = 'UNMATCHED_CATALOG_ITEM'",
                Integer.class, orgId);
        assertThat(operatorQueueCount).isEqualTo(1);

        Integer orderItemCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.order_item WHERE organization_id = ?", Integer.class, orgId);
        assertThat(orderItemCount)
                .withFailMessage("No variant_id exists for an unmatched item — no order_item can be created for it")
                .isZero();

        Integer stockRowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.stock WHERE organization_id = ?", Integer.class, orgId);
        assertThat(stockRowCount).withFailMessage("Phase 3 gate: stock must never be touched for an unmatched item").isZero();
    }

    @Test
    @DisplayName("Phase 3 gate: the same unmatched item seen twice queues exactly one review row, not two")
    void repeatedUnmatchedItemDoesNotDuplicateTheReviewRow() {
        for (int i = 0; i < 2; i++) {
            OrderEventPayload event = new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(),
                    "CO-" + UUID.randomUUID(), Instant.now(), 1L, new BigDecimal("19.99"), "USD",
                    List.of(new OrderEventPayload.OrderEventItem("UNKNOWN-SKU", "cp-2", "cv-2", null,
                            1, new BigDecimal("19.99"), BigDecimal.ZERO, OrderItemStatus.CREATED)),
                    UUID.randomUUID().toString());
            orderProcessingService.process(event);
        }

        Integer candidateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.mapping_candidate WHERE organization_id = ? AND channel_variant_id = 'cv-2'",
                Integer.class, orgId);
        assertThat(candidateCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 3 gate: SKU match wins even when a different variant also has a matching barcode")
    void skuMatchIsTriedBeforeBarcode() {
        UUID byBarcode = seedVariant("OTHER-SKU", "SHARED-BARCODE");
        UUID bySku = seedVariant("MATCH-ME", "DIFFERENT-BARCODE");

        CatalogMatchingService.MatchResult result = catalogMatchingService.resolve(
                orgId, channelConnectionId, "cp", "cv-sku-test", "MATCH-ME", "SHARED-BARCODE", "title");

        assertThat(result.matched()).isTrue();
        assertThat(result.variantId()).isEqualTo(bySku);
    }

    @Test
    @DisplayName("Phase 3 gate: exactly one variant with a matching barcode auto-resolves via AUTO_BARCODE")
    void singleBarcodeMatchAutoResolves() {
        UUID variantId = seedVariant("BARCODE-ONLY-SKU", "UNIQUE-BARCODE-1");

        CatalogMatchingService.MatchResult result = catalogMatchingService.resolve(
                orgId, channelConnectionId, "cp", "cv-barcode-test", "NO-SUCH-SKU", "UNIQUE-BARCODE-1", "title");

        assertThat(result.matched()).isTrue();
        assertThat(result.variantId()).isEqualTo(variantId);

        String source = jdbcTemplate.queryForObject(
                "SELECT mapping_source FROM hub.channel_product_mapping WHERE channel_variant_id = 'cv-barcode-test'",
                String.class);
        assertThat(source).isEqualTo("AUTO_BARCODE");
    }

    @Test
    @DisplayName("Phase 3 gate: two variants sharing a barcode is ambiguous — queued for review, not guessed at")
    void ambiguousBarcodeMatchIsQueuedNotGuessed() {
        seedVariant("SKU-1", "SHARED-AMBIGUOUS");
        seedVariant("SKU-2", "SHARED-AMBIGUOUS");

        CatalogMatchingService.MatchResult result = catalogMatchingService.resolve(
                orgId, channelConnectionId, "cp", "cv-ambiguous", "NO-MATCH-SKU", "SHARED-AMBIGUOUS", "title");

        assertThat(result.matched()).isFalse();

        Map<String, Object> candidate = jdbcTemplate.queryForMap(
                "SELECT candidate_variant_ids FROM hub.mapping_candidate WHERE channel_variant_id = 'cv-ambiguous'");
        assertThat(candidate.get("candidate_variant_ids")).isNotNull();
    }

    @Test
    @DisplayName("Phase 3 gate: an operator resolving a mapping_candidate by hand creates a MANUAL mapping and an audit entry")
    void manualResolutionCreatesMappingAndAuditEntry() {
        UUID variantId = seedVariant("MANUAL-TARGET-SKU", null);

        catalogMatchingService.resolve(orgId, channelConnectionId, "cp", "cv-manual", "NO-MATCH", null, "title");
        UUID candidateId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.mapping_candidate WHERE channel_variant_id = 'cv-manual'", UUID.class);

        catalogMatchingService.resolveManually(actor(HubRole.OPERATOR), candidateId, variantId);

        String mappedVariant = jdbcTemplate.queryForObject(
                "SELECT variant_id::text FROM hub.channel_product_mapping WHERE channel_variant_id = 'cv-manual'", String.class);
        assertThat(mappedVariant).isEqualTo(variantId.toString());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.mapping_candidate WHERE id = ?", String.class, candidateId);
        assertThat(status).isEqualTo("RESOLVED");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'CATALOG_MAPPING_RESOLVED'",
                Integer.class, orgId);
        assertThat(auditCount).isEqualTo(1);

        String queueStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.operator_queue WHERE organization_id = ? AND type = 'UNMATCHED_CATALOG_ITEM' AND reference_id = ?",
                String.class, orgId, candidateId);
        assertThat(queueStatus)
                .withFailMessage("A resolved candidate must close its own operator_queue row — otherwise the queue never empties")
                .isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("v5 Faz 2 gate H6: an OBSERVER cannot resolve a mapping candidate, and the refusal is audited")
    void observerCannotResolveManually() {
        UUID variantId = seedVariant("OBSERVER-DENIED-SKU", null);
        catalogMatchingService.resolve(orgId, channelConnectionId, "cp", "cv-observer-resolve", "NO-MATCH", null, "title");
        UUID candidateId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.mapping_candidate WHERE channel_variant_id = 'cv-observer-resolve'", UUID.class);

        assertThatThrownBy(() -> catalogMatchingService.resolveManually(actor(HubRole.OBSERVER), candidateId, variantId))
                .isInstanceOf(InsufficientRoleException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.mapping_candidate WHERE id = ?", String.class, candidateId);
        assertThat(status).withFailMessage("A refused resolve must leave the candidate exactly as it was").isEqualTo("PENDING");

        Integer deniedCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'PERMISSION_DENIED'
                """, Integer.class, orgId);
        assertThat(deniedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("arayuz-3: pendingCandidatesWithDetails resolves candidate_variant_ids to real variant rows and open-order impact")
    void pendingCandidatesWithDetailsResolvesRealVariants() {
        UUID busyVariant = seedVariant("SKU-BUSY", "SHARED-DETAIL");
        UUID quietVariant = seedVariant("SKU-QUIET", "SHARED-DETAIL");

        OrderEventPayload event = new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(),
                "CO-" + UUID.randomUUID(), Instant.now(), 1L, new BigDecimal("19.99"), "USD",
                List.of(new OrderEventPayload.OrderEventItem("SKU-BUSY", "cp-busy", "cv-busy", null,
                        1, new BigDecimal("19.99"), BigDecimal.ZERO, OrderItemStatus.CREATED)),
                UUID.randomUUID().toString());
        orderProcessingService.process(event);

        catalogMatchingService.resolve(orgId, channelConnectionId, "cp", "cv-detail-test", "NO-MATCH", "SHARED-DETAIL", "title");

        List<Map<String, Object>> rows = catalogMatchingService.pendingCandidatesWithDetails(new PageRequest(0, 50)).items();
        Map<String, Object> row = rows.stream()
                .filter(r -> "cv-detail-test".equals(r.get("channel_variant_id")))
                .findFirst()
                .orElseThrow();

        // Bug found post-Faz-8: candidates used to come back as pgjdbc's jsonb driver
        // wrapper, not a List — a wrapper whose toString() happens to echo the same
        // JSON text (PGobject.toString() == getValue()), which is exactly why a
        // string-matching assertion here would never have caught it either way. These
        // assertions read the parsed structure directly instead.
        assertThat(row.get("candidates"))
                .withFailMessage("candidates must be a real List, not a PGobject-shaped jsonb driver wrapper")
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) row.get("candidates");
        Set<Object> skus = candidates.stream().map(c -> c.get("sku")).collect(java.util.stream.Collectors.toSet());
        assertThat(skus).contains("SKU-BUSY", "SKU-QUIET");

        Map<String, Object> busyCandidate = candidates.stream()
                .filter(c -> "SKU-BUSY".equals(c.get("sku"))).findFirst().orElseThrow();
        assertThat(busyCandidate.get("openOrderItems"))
                .withFailMessage("The variant with one open order item must report openOrderItems=1, not 0")
                .isEqualTo(1);

        assertThat(busyVariant).isNotEqualTo(quietVariant);
    }

    @Test
    @DisplayName("arayuz-3: ignoring a candidate marks it IGNORED, audits it, and closes its queue row without creating a mapping")
    void ignoringCandidateClosesWithoutMapping() {
        catalogMatchingService.resolve(orgId, channelConnectionId, "cp", "cv-ignore-test", "NO-MATCH", null, "title");
        UUID candidateId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.mapping_candidate WHERE channel_variant_id = 'cv-ignore-test'", UUID.class);

        catalogMatchingService.ignore(actor(HubRole.OPERATOR), candidateId);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.mapping_candidate WHERE id = ?", String.class, candidateId);
        assertThat(status).isEqualTo("IGNORED");

        Integer mappingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.channel_product_mapping WHERE channel_variant_id = 'cv-ignore-test'", Integer.class);
        assertThat(mappingCount).isZero();

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'CATALOG_MAPPING_IGNORED'",
                Integer.class, orgId);
        assertThat(auditCount).isEqualTo(1);

        String queueStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.operator_queue WHERE organization_id = ? AND type = 'UNMATCHED_CATALOG_ITEM' AND reference_id = ?",
                String.class, orgId, candidateId);
        assertThat(queueStatus).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("v5 Faz 2 gate H6: an OBSERVER cannot ignore a mapping candidate, and the refusal is audited")
    void observerCannotIgnore() {
        catalogMatchingService.resolve(orgId, channelConnectionId, "cp", "cv-observer-ignore", "NO-MATCH", null, "title");
        UUID candidateId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.mapping_candidate WHERE channel_variant_id = 'cv-observer-ignore'", UUID.class);

        assertThatThrownBy(() -> catalogMatchingService.ignore(actor(HubRole.OBSERVER), candidateId))
                .isInstanceOf(InsufficientRoleException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM hub.mapping_candidate WHERE id = ?", String.class, candidateId);
        assertThat(status).withFailMessage("A refused ignore must leave the candidate exactly as it was").isEqualTo("PENDING");

        Integer deniedCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.audit_log WHERE organization_id = ? AND action = 'PERMISSION_DENIED'
                """, Integer.class, orgId);
        assertThat(deniedCount).isEqualTo(1);
    }
}
