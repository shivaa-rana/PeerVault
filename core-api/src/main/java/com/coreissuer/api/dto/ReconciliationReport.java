package com.coreissuer.api.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Instant generatedAt,
        int walletsChecked,
        int completedTransfersChecked,
        List<String> discrepancies) {
    public boolean balanced() {
        return discrepancies.isEmpty();
    }
}
