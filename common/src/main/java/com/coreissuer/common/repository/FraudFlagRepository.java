package com.coreissuer.common.repository;

import com.coreissuer.common.domain.FraudFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {
}
