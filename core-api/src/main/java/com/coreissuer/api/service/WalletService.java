package com.coreissuer.api.service;

import com.coreissuer.api.dto.WalletResponse;
import com.coreissuer.api.dto.WalletTransactionResponse;
import com.coreissuer.api.exception.ResourceNotFoundException;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository wallets;
    private final WalletLedgerEntryRepository ledger;

    @Value("${coreissuer.wallet.default-currency:INR}")
    private String defaultCurrency;

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId) {
        Wallet wallet = wallets.findByUserIdAndCurrency(userId, defaultCurrency.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
        return new WalletResponse(wallet.getId(), wallet.getAvailableBalance().toPlainString(),
                wallet.getCurrency(), wallet.getStatus());
    }

    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> history(UUID userId, int page, int size) {
        Page<WalletTransactionResponse> result = ledger.findVisibleToUser(userId, PageRequest.of(page, size))
                .map(entry -> new WalletTransactionResponse(entry.getId(), entry.getTransferId(),
                        entry.getReferenceId(), entry.getDirection().name(), entry.getAmount().toPlainString(),
                        entry.getCurrency(), entry.getCreatedAt()));
        return result;
    }
}
