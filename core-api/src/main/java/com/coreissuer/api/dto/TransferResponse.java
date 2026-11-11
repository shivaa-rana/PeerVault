package com.coreissuer.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID senderWalletId,
        UUID recipientWalletId,
        String amount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt) {
}
