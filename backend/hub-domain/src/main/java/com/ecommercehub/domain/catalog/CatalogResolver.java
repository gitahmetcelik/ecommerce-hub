package com.ecommercehub.domain.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Faz 2 stand-in for real catalog matching (channel_product_mapping, mapping_candidate
 * — Faz 3): finds a variant by SKU, or creates a bare-bones product+variant for it if
 * none exists yet. Order processing must not block on catalog work that hasn't been
 * built. Faz 3 replaces the "create it" branch with routing unmatched items to
 * mapping_candidate + the operator queue instead.
 */
@Service
public class CatalogResolver {

    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;

    public CatalogResolver(ProductRepository productRepository, VariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    @Transactional
    public Variant resolveOrCreateBySku(UUID organizationId, String sku, String title) {
        return variantRepository.findByOrganizationIdAndSku(organizationId, sku)
                .orElseGet(() -> {
                    Product product = productRepository.save(new Product(UUID.randomUUID(), organizationId, title));
                    return variantRepository.save(new Variant(UUID.randomUUID(), organizationId, product.getId(), sku));
                });
    }
}
