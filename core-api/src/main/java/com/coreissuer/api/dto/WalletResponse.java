package com.coreissuer.api.dto;

import java.util.UUID;

public record WalletResponse(UUID id, String availableBalance, String currency, String status) {
}
