package com.ecommercehub.domain.catalog;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan §3/Phase 3: SKU first, then barcode. Plan §3 treats sku, barcode and the channel's
 * own variant id as three distinct concepts, none of which substitutes for another —
 * barcode is only ever a fallback. Anything that does not resolve to exactly one
 * variant — nothing found, or more than one candidate — goes to mapping_candidate
 * and the operator queue instead of being silently dropped or guessed at (Phase 3 gate:
 * "none of them is silently dropped").
 */
@Service
public class CatalogMatchingService {

    private static final Logger log = LoggerFactory.getLogger(CatalogMatchingService.class);

    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;
    private final ChannelProductMappingRepository mappingRepository;
    private final MappingCandidateRepository candidateRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public CatalogMatchingService(ProductRepository productRepository,
                                   VariantRepository variantRepository,
                                   ChannelProductMappingRepository mappingRepository,
                                   MappingCandidateRepository candidateRepository,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   AuditLogService auditLogService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.mappingRepository = mappingRepository;
        this.candidateRepository = candidateRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * The operator matching screen's data. `candidates` resolves candidate_variant_ids to
     * real variant rows (sku/barcode/title) plus how many open order items already depend
     * on each one — a candidate list can only exist here at all when it has zero entries
     * (nothing matched) or two-plus (an ambiguous barcode); {@link #resolve} auto-resolves
     * anything with exactly one clean match before a mapping_candidate row is ever written,
     * so "which one is right" is always a real judgment call by the time it reaches this
     * screen, never a formality.
     */
    public List<Map<String, Object>> pendingCandidatesWithDetails() {
        return jdbcTemplate.queryForList("""
                SELECT mc.id, mc.channel_connection_id, mc.channel_product_id, mc.channel_variant_id,
                       mc.barcode, mc.title, mc.status, mc.created_at,
                       (
                           SELECT jsonb_agg(jsonb_build_object(
                               'variantId', v.id, 'sku', v.sku, 'barcode', v.barcode, 'title', p.title,
                               'openOrderItems', (
                                   SELECT count(*) FROM hub.order_item oi
                                   WHERE oi.variant_id = v.id
                                     AND oi.status NOT IN ('DELIVERED', 'CANCELLED', 'PAYMENT_TIMEOUT')
                               )
                           ))
                           FROM jsonb_array_elements_text(COALESCE(mc.candidate_variant_ids, '[]'::jsonb)) AS cand(variant_id)
                           JOIN hub.variant v ON v.id = cand.variant_id::uuid
                           JOIN hub.product p ON p.id = v.product_id
                       ) AS candidates
                FROM hub.mapping_candidate mc
                WHERE mc.status = 'PENDING' ORDER BY mc.created_at LIMIT 200
                """);
    }

    /**
     * Plan Phase 3 backfill: unlike {@link #resolve}, this trusts the channel's own
     * catalog feed as a source of truth and creates a variant when nothing matches —
     * appropriate here specifically because we're importing FROM the catalog, not
     * trying to match an incoming order against an already-established one.
     */
    @Transactional
    public UUID importFromChannel(UUID organizationId, UUID channelConnectionId, String channelProductId,
                                   String channelVariantId, String sku, String barcode, String title) {
        Optional<ChannelProductMapping> existing = mappingRepository
                .findByOrganizationIdAndChannelConnectionIdAndChannelVariantId(organizationId, channelConnectionId, channelVariantId);
        if (existing.isPresent()) {
            return existing.get().getVariantId();
        }

        Match match = findForImport(organizationId, sku, barcode);
        Variant variant = match.variant() != null
                ? match.variant()
                : createVariant(organizationId, sku, barcode, title);

        recordMapping(organizationId, channelConnectionId, channelProductId, channelVariantId,
                variant.getId(), match.source());
        return variant.getId();
    }

    /**
     * SKU first, then barcode — the same order {@link #resolve} uses.
     *
     * <p>The barcode fallback was missing here originally and only started to matter once
     * a channel with no seller SKU existed: without it, importing the same physical
     * product from two channels produced two variants for it, and each would then carry
     * its own idea of that product's stock.
     */
    private Match findForImport(UUID organizationId, String sku, String barcode) {
        if (sku != null && !sku.isBlank()) {
            Optional<Variant> bySku = variantRepository.findByOrganizationIdAndSku(organizationId, sku);
            if (bySku.isPresent()) {
                return new Match(bySku.get(), ChannelProductMapping.MappingSource.AUTO_SKU);
            }
        }

        if (barcode != null && !barcode.isBlank()) {
            List<Variant> byBarcode = variantRepository.findByOrganizationIdAndBarcode(organizationId, barcode);
            // Exactly one, or none. An ambiguous barcode stays a human's decision even on
            // the import path, where guessing would attach a channel's entire catalogue to
            // the wrong variants in one pass.
            if (byBarcode.size() == 1) {
                return new Match(byBarcode.get(0), ChannelProductMapping.MappingSource.AUTO_BARCODE);
            }
        }

        return new Match(null, sku != null && !sku.isBlank()
                ? ChannelProductMapping.MappingSource.AUTO_SKU
                : ChannelProductMapping.MappingSource.AUTO_BARCODE);
    }

    /**
     * Creates the variant a channel's catalogue implies.
     *
     * <p>When the channel supplies no SKU, one is minted from the barcode. Plan §3 makes
     * the SKU <em>our</em> key rather than the channel's, so a channel that does not have
     * one is not an error to reject — it is a channel we have to name things for. Leaving
     * it null is not an option either: the column is NOT NULL precisely because every
     * variant must be referable by an identifier we control.
     */
    private Variant createVariant(UUID organizationId, String sku, String barcode, String title) {
        String effectiveSku = sku != null && !sku.isBlank() ? sku : mintSkuFromBarcode(barcode);

        Product product = productRepository.save(new Product(UUID.randomUUID(), organizationId, title));
        Variant created = new Variant(UUID.randomUUID(), organizationId, product.getId(), effectiveSku);
        created.setBarcode(barcode);
        return variantRepository.save(created);
    }

    /** Deterministic, so re-importing the same catalogue converges instead of multiplying variants. */
    private String mintSkuFromBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            throw new IllegalArgumentException("A channel item with neither a sku nor a barcode cannot be "
                    + "imported — there is nothing to identify it by");
        }
        return "BC-" + barcode;
    }

    private record Match(Variant variant, ChannelProductMapping.MappingSource source) {
    }

    public record MatchResult(boolean matched, UUID variantId) {
        static MatchResult matched(UUID variantId) {
            return new MatchResult(true, variantId);
        }

        static final MatchResult UNMATCHED = new MatchResult(false, null);
    }

    @Transactional
    public MatchResult resolve(UUID organizationId, UUID channelConnectionId, String channelProductId,
                                String channelVariantId, String sku, String barcode, String title) {
        Optional<ChannelProductMapping> existing = mappingRepository
                .findByOrganizationIdAndChannelConnectionIdAndChannelVariantId(organizationId, channelConnectionId, channelVariantId);
        if (existing.isPresent()) {
            return MatchResult.matched(existing.get().getVariantId());
        }

        Optional<Variant> bySku = variantRepository.findByOrganizationIdAndSku(organizationId, sku);
        if (bySku.isPresent()) {
            return recordMapping(organizationId, channelConnectionId, channelProductId, channelVariantId,
                    bySku.get().getId(), ChannelProductMapping.MappingSource.AUTO_SKU);
        }

        if (barcode != null && !barcode.isBlank()) {
            List<Variant> byBarcode = variantRepository.findByOrganizationIdAndBarcode(organizationId, barcode);
            if (byBarcode.size() == 1) {
                return recordMapping(organizationId, channelConnectionId, channelProductId, channelVariantId,
                        byBarcode.get(0).getId(), ChannelProductMapping.MappingSource.AUTO_BARCODE);
            }
            if (byBarcode.size() > 1) {
                queueForReview(organizationId, channelConnectionId, channelProductId, channelVariantId, barcode, title,
                        byBarcode.stream().map(Variant::getId).toList());
                return MatchResult.UNMATCHED;
            }
        }

        queueForReview(organizationId, channelConnectionId, channelProductId, channelVariantId, barcode, title, List.of());
        return MatchResult.UNMATCHED;
    }

    /**
     * Plan §3: operator resolves a mapping_candidate by hand — always MANUAL source,
     * always audited (who, what, when). The candidate itself does NOT retroactively
     * heal any order items that were skipped while it was unresolved (Plan Phase 3 gate
     * only requires they reached the operator queue without corrupting stock, not
     * automatic backfill-after-the-fact); a human can act on the operator_queue entry.
     */
    @Transactional
    public void resolveManually(AuthenticatedUser actor, UUID candidateId, UUID variantId) {
        requireOperator(actor, "resolve a mapping candidate");

        MappingCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("No mapping_candidate with id " + candidateId));

        mappingRepository.save(new ChannelProductMapping(UUID.randomUUID(), candidate.getOrganizationId(),
                variantId, candidate.getChannelConnectionId(), candidate.getChannelProductId(),
                candidate.getChannelVariantId(), ChannelProductMapping.MappingSource.MANUAL, actor.userId()));

        candidate.markResolved();

        jdbcTemplate.update("""
                INSERT INTO hub.audit_log (id, organization_id, user_id, action, details)
                VALUES (gen_random_uuid(), ?, ?, 'CATALOG_MAPPING_RESOLVED', jsonb_build_object(
                    'mappingCandidateId', ?::text, 'variantId', ?::text, 'channelVariantId', ?))
                """,
                candidate.getOrganizationId(), actor.userId(), candidateId, variantId, candidate.getChannelVariantId());

        closeReviewQueueItem(candidate.getOrganizationId(), candidateId);
    }

    /**
     * Plan §3 eslesme_adayi.durum = YOKSAYILDI — the operator has decided this channel
     * item is not going to be matched at all (a discontinued line, a test listing). Unlike
     * {@link #resolveManually}, this creates no mapping — a future sync of the same
     * channel_variant_id will queue a fresh candidate rather than silently staying ignored
     * forever, since ignoring today says nothing about whether it should stay ignored.
     */
    @Transactional
    public void ignore(AuthenticatedUser actor, UUID candidateId) {
        requireOperator(actor, "ignore a mapping candidate");

        MappingCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("No mapping_candidate with id " + candidateId));

        candidate.markIgnored();

        jdbcTemplate.update("""
                INSERT INTO hub.audit_log (id, organization_id, user_id, action, details)
                VALUES (gen_random_uuid(), ?, ?, 'CATALOG_MAPPING_IGNORED', jsonb_build_object(
                    'mappingCandidateId', ?::text, 'channelVariantId', ?))
                """,
                candidate.getOrganizationId(), actor.userId(), candidateId, candidate.getChannelVariantId());

        closeReviewQueueItem(candidate.getOrganizationId(), candidateId);
    }

    /**
     * Plan v5 §2.5, H6: SecurityConfig is a coarse gate only — the real per-action
     * decision lives here, the same pattern ReturnFulfilmentService.issueRefund
     * established. A denied attempt is audited too; the point of an audit trail is
     * what was tried, not only what succeeded.
     */
    private void requireOperator(AuthenticatedUser actor, String action) {
        if (!actor.hasAtLeast(HubRole.OPERATOR)) {
            auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", action, "role", actor.effectiveRole().name(), "required", "OPERATOR"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.OPERATOR);
        }
    }

    // queueForReview's operator_queue row (type UNMATCHED_CATALOG_ITEM, reference_id =
    // candidateId) has no other closing path — without this, a resolved or ignored
    // candidate stays PENDING in the queue forever, which is exactly the stale-notification
    // failure the queue redesign (ui-plani.md §4.1) is supposed to make impossible.
    private void closeReviewQueueItem(UUID organizationId, UUID candidateId) {
        jdbcTemplate.update("""
                UPDATE hub.operator_queue SET status = 'RESOLVED', updated_at = now(), version = version + 1
                WHERE organization_id = ? AND type = 'UNMATCHED_CATALOG_ITEM' AND reference_id = ? AND status = 'PENDING'
                """,
                organizationId, candidateId);
    }

    private MatchResult recordMapping(UUID organizationId, UUID channelConnectionId, String channelProductId,
                                       String channelVariantId, UUID variantId, ChannelProductMapping.MappingSource source) {
        mappingRepository.save(new ChannelProductMapping(UUID.randomUUID(), organizationId, variantId,
                channelConnectionId, channelProductId, channelVariantId, source, null));
        return MatchResult.matched(variantId);
    }

    private void queueForReview(UUID organizationId, UUID channelConnectionId, String channelProductId,
                                 String channelVariantId, String barcode, String title, List<UUID> candidateVariantIds) {
        // Idempotent — the same unmatched channel item seen again (retry, repeated
        // webhook) must not pile up duplicate review rows.
        Optional<MappingCandidate> existing = candidateRepository
                .findByOrganizationIdAndChannelConnectionIdAndChannelVariantId(organizationId, channelConnectionId, channelVariantId);
        if (existing.isPresent()) {
            return;
        }

        String candidateJson;
        try {
            candidateJson = candidateVariantIds.isEmpty() ? null : objectMapper.writeValueAsString(candidateVariantIds);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

        MappingCandidate candidate = candidateRepository.save(new MappingCandidate(UUID.randomUUID(), organizationId,
                channelConnectionId, channelProductId, channelVariantId, barcode, title, candidateJson));

        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (gen_random_uuid(), ?, 'UNMATCHED_CATALOG_ITEM', ?, ?)
                """,
                organizationId,
                "Channel variant " + channelVariantId + " (" + (title == null ? "no title" : title) + ") could not be matched to a variant",
                candidate.getId());

        log.info("Queued mapping_candidate {} for channel variant {} (org {})", candidate.getId(), channelVariantId, organizationId);
    }
}
