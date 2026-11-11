package com.coreissuer.api.dto;

import java.time.Instant;
import java.util.UUID;

public record FraudFlagResponse(UUID id, UUID userId, UUID transferId, String riskReason,
                                String severity, String reviewStatus, Instant createdAt) {
}
