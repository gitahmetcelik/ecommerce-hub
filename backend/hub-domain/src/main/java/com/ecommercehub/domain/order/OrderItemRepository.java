package com.ecommercehub.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findBySalesOrderId(UUID salesOrderId);

    Optional<OrderItem> findBySalesOrderIdAndVariantId(UUID salesOrderId, UUID variantId);
}
