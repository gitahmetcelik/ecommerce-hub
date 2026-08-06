package com.ecommercehub.domain.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    Optional<SalesOrder> findByOrganizationIdAndChannelConnectionIdAndChannelOrderNumber(
            UUID organizationId, UUID channelConnectionId, String channelOrderNumber);

    /** Plan §3: derived_status recomputation happens under this lock, in the same transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = ?1")
    Optional<SalesOrder> findByIdForUpdate(UUID id);
}
