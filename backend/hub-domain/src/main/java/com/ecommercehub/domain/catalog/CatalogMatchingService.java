package com.ecommercehub.domain.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public CatalogMatchingService(ProductRepository productRepository,
                                   VariantRepository variantRepository,
                                   ChannelProductMappingRepository mappingRepository,
                                   MappingCandidateRepository candidateRepository,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.mappingRepository = mappingRepository;
        this.candidateRepository = candidateRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        Variant variant = variantRepository.findByOrganizationIdAndSku(organizationId, sku)
                .orElseGet(() -> {
                    Product product = productRepository.save(new Product(UUID.randomUUID(), organizationId, title));
                    Variant created = new Variant(UUID.randomUUID(), organizationId, product.getId(), sku);
                    created.setBarcode(barcode);
                    return variantRepository.save(created);
                });

        recordMapping(organizationId, channelConnectionId, channelProductId, channelVariantId,
                variant.getId(), ChannelProductMapping.MappingSource.AUTO_SKU);
        return variant.getId();
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
    public void resolveManually(UUID candidateId, UUID variantId, UUID userId) {
        MappingCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("No mapping_candidate with id " + candidateId));

        mappingRepository.save(new ChannelProductMapping(UUID.randomUUID(), candidate.getOrganizationId(),
                variantId, candidate.getChannelConnectionId(), candidate.getChannelProductId(),
                candidate.getChannelVariantId(), ChannelProductMapping.MappingSource.MANUAL, userId));

        candidate.markResolved();

        jdbcTemplate.update("""
                INSERT INTO hub.audit_log (id, organization_id, user_id, action, details)
                VALUES (gen_random_uuid(), ?, ?, 'CATALOG_MAPPING_RESOLVED', jsonb_build_object(
                    'mappingCandidateId', ?::text, 'variantId', ?::text, 'channelVariantId', ?))
                """,
                candidate.getOrganizationId(), userId, candidateId, variantId, candidate.getChannelVariantId());
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
