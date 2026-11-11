package com.coreissuer.common.repository;

import com.coreissuer.common.domain.PaymentIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.providerIntentId = :providerIntentId")
    Optional<PaymentIntent> findByProviderIntentIdForUpdate(
            @Param("providerIntentId") String providerIntentId);
}
