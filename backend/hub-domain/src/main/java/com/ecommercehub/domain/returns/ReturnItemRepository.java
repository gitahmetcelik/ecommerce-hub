package com.ecommercehub.domain.returns;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReturnItemRepository extends JpaRepository<ReturnItem, UUID> {

    List<ReturnItem> findByReturnRequestId(UUID returnRequestId);
}
