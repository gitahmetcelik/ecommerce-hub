package com.ecommercehub.domain.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * plan §3: the one table that is unambiguously personal data. Everything about how it
 * is handled — partitioning, retention, masking — exists because of what is in here.
 */
@Entity
@Table(name = "customer", schema = "hub")
public class Customer {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    private String email;

    private String phone;

    private String address;

    @Column(name = "erased_at")
    private Instant erasedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Customer() {
        // JPA
    }

    public Customer(UUID id, UUID organizationId, String firstName, String lastName,
                     String email, String phone, String address) {
        this.id = id;
        this.organizationId = organizationId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.address = address;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddress() {
        return address;
    }

    public Instant getErasedAt() {
        return erasedAt;
    }

    public boolean isErased() {
        return erasedAt != null;
    }

    /**
     * Replaces every identifying field with a fixed placeholder and keeps the row.
     *
     * <p>The placeholders are constants, not per-customer pseudonyms: a pseudonym that
     * differs per person is still an identifier, and two orders sharing one would still
     * link the same human across them. What survives here is "an order was placed by
     * somebody", which is what the business genuinely needs.
     */
    void erase(Instant at) {
        this.firstName = "ERASED";
        this.lastName = "ERASED";
        this.email = null;
        this.phone = null;
        this.address = null;
        this.erasedAt = at;
    }
}
