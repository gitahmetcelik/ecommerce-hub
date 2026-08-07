package com.ecommercehub.domain.price;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plan §6.2 point 1: the variant's center-owned list price — one authoritative row per
 * (organization, variant), enforced by {@code uk_price_org_variant} (V1007). {@code
 * effectiveFrom} is bumped on every write rather than tracked as history; the plan
 * scopes price history/drift reconciliation out of this phase (§6.3).
 */
@Entity
@Table(name = "price", schema = "hub")
public class Price {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "list_price", nullable = false)
    private BigDecimal listPrice;

    // DB column is CHAR(3) — see SalesOrder.currency for why both @JdbcTypeCode(CHAR)
    // and length = 3 are required, not just one.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Price() {
        // JPA
    }

    public Price(UUID id, UUID organizationId, UUID variantId, BigDecimal listPrice, String currency, BigDecimal vatRate) {
        this.id = id;
        this.organizationId = organizationId;
        this.variantId = variantId;
        this.listPrice = listPrice;
        this.currency = currency;
        this.vatRate = vatRate;
        this.effectiveFrom = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    void apply(BigDecimal listPrice, String currency, BigDecimal vatRate) {
        this.listPrice = listPrice;
        this.currency = currency;
        this.vatRate = vatRate;
        this.effectiveFrom = Instant.now();
    }
}
