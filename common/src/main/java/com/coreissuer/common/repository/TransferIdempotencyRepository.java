package com.coreissuer.common.repository;

import com.coreissuer.common.domain.TransferIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransferIdempotencyRepository extends JpaRepository<TransferIdempotencyRecord, UUID> {

    Optional<TransferIdempotencyRecord> findByUserIdAndOperationAndIdempotencyKey(
            UUID userId, String operation, String idempotencyKey);

    @Modifying
    @Query(value = """
            insert into transfer_idempotency
                (id, user_id, operation, idempotency_key, request_hash, created_at)
            values (:id, :userId, :operation, :key, :requestHash, now())
            on conflict (user_id, operation, idempotency_key) do nothing
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("userId") UUID userId,
              @Param("operation") String operation, @Param("key") String key,
              @Param("requestHash") String requestHash);
}
