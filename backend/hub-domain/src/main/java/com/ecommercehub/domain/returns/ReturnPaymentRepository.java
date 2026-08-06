package com.ecommercehub.domain.returns;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReturnPaymentRepository extends JpaRepository<ReturnPayment, UUID> {

    List<ReturnPayment> findByReturnRequestId(UUID returnRequestId);
}
