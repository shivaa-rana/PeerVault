package com.coreissuer.api.controller;

import com.coreissuer.api.dto.WalletResponse;
import com.coreissuer.api.dto.WalletTransactionResponse;
import com.coreissuer.api.security.AuthenticatedUser;
import com.coreissuer.api.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService walletService;

    @GetMapping
    public WalletResponse wallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.getWallet(user.id());
    }

    @GetMapping("/transactions")
    public Page<WalletTransactionResponse> transactions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return walletService.history(user.id(), page, size);
    }

    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }
}
