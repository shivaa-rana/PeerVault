package com.coreissuer.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_flag")
@Getter
@Setter
@NoArgsConstructor
public class FraudFlag {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "risk_reason", nullable = false, length = 120)
    private String riskReason;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "review_status", nullable = false, length = 16)
    private String reviewStatus = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
