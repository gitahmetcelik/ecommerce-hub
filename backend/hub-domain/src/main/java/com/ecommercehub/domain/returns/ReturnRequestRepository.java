package com.ecommercehub.domain.returns;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

    /** A channel redelivering the same return must not create a second one. */
    Optional<ReturnRequest> findByOrganizationIdAndSalesOrderIdAndChannelReturnId(
            UUID organizationId, UUID salesOrderId, String channelReturnId);

    List<ReturnRequest> findByStatus(ReturnStatus status);

    /** Backs the 24h reminder: awaiting approval, past the reminder mark, not yet reminded. */
    List<ReturnRequest> findByStatusAndReminderAtBeforeAndRemindedAtIsNull(ReturnStatus status, Instant before);

    /** Backs the 48h timeout. */
    List<ReturnRequest> findByStatusAndTimeoutAtBefore(ReturnStatus status, Instant before);
}
