package com.coreissuer.api.controller;

import com.coreissuer.api.dto.FraudFlagResponse;
import com.coreissuer.api.dto.ReconciliationReport;
import com.coreissuer.api.security.AuthenticatedUser;
import com.coreissuer.api.service.AdminOperationsService;
import com.coreissuer.api.service.WalletReconciliationService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
public class AdminWalletController {
    private final WalletReconciliationService reconciliation;
    private final AdminOperationsService adminOperations;

    @GetMapping("/reconciliation")
    public ReconciliationReport reconcile() {
        return reconciliation.reconcile();
    }

    @GetMapping("/fraud-flags")
    public Page<FraudFlagResponse> flags(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        WalletController.validatePage(page, size);
        return adminOperations.fraudFlags(page, size);
    }

    @PatchMapping("/fraud-flags/{id}")
    public FraudFlagResponse review(@PathVariable UUID id,
                                    @RequestBody Map<String, String> request,
                                    @AuthenticationPrincipal AuthenticatedUser reviewer) {
        return adminOperations.review(id, request.get("status"), reviewer.id());
    }

    @GetMapping("/audit-logs")
    public Page<com.coreissuer.common.domain.AuditLog> auditLogs(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        WalletController.validatePage(page, size);
        return adminOperations.auditLogs(page, size);
    }
}
