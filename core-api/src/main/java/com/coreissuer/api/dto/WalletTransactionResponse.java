package com.coreissuer.api.dto;

import java.time.Instant;
import java.util.UUID;

public record WalletTransactionResponse(
        Long id, UUID transferId, String referenceId, String direction,
        String amount, String currency, Instant createdAt) {
}
