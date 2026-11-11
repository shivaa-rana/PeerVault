package com.coreissuer.api.service;

import com.coreissuer.api.dto.ReconciliationReport;
import com.coreissuer.common.domain.LedgerDirection;
import com.coreissuer.common.domain.TransferStatus;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletLedgerEntry;
import com.coreissuer.common.domain.WalletTransfer;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WalletTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletReconciliationService {
    private final WalletRepository wallets;
    private final WalletTransferRepository transfers;
    private final WalletLedgerEntryRepository ledger;

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<String> discrepancies = new ArrayList<>();
        List<WalletLedgerEntry> entries = ledger.findAll();
        Map<UUID, List<WalletLedgerEntry>> entriesByTransfer = entries.stream()
                .filter(entry -> entry.getTransferId() != null)
                .collect(Collectors.groupingBy(WalletLedgerEntry::getTransferId));
        Map<UUID, BigDecimal> derivedBalances = new HashMap<>();
        for (WalletLedgerEntry entry : entries) {
            if (entry.getWalletId() != null) {
                BigDecimal signed = entry.getDirection() == LedgerDirection.CREDIT
                        ? entry.getAmount() : entry.getAmount().negate();
                derivedBalances.merge(entry.getWalletId(), signed, BigDecimal::add);
            }
        }

        List<WalletTransfer> allTransfers = transfers.findAll();
        for (WalletTransfer transfer : allTransfers) {
            List<WalletLedgerEntry> transferEntries =
                    entriesByTransfer.getOrDefault(transfer.getId(), List.of());
            if (transfer.getStatus() == TransferStatus.COMPLETED) {
                BigDecimal debits = sum(transferEntries, LedgerDirection.DEBIT);
                BigDecimal credits = sum(transferEntries, LedgerDirection.CREDIT);
                boolean exactEntries = transferEntries.size() == 2
                        && transferEntries.stream().anyMatch(e -> transfer.getSenderWalletId().equals(e.getWalletId())
                        && e.getDirection() == LedgerDirection.DEBIT
                        && transfer.getCurrency().equals(e.getCurrency()))
                        && transferEntries.stream().anyMatch(e -> transfer.getRecipientWalletId().equals(e.getWalletId())
                        && e.getDirection() == LedgerDirection.CREDIT
                        && transfer.getCurrency().equals(e.getCurrency()));
                if (!exactEntries || debits.compareTo(transfer.getAmount()) != 0
                        || credits.compareTo(transfer.getAmount()) != 0) {
                    discrepancies.add("Transfer " + transfer.getId() + " has missing, duplicated, or unbalanced ledger entries");
                }
            } else if (!transferEntries.isEmpty()) {
                discrepancies.add("Non-completed transfer " + transfer.getId() + " has ledger entries");
            }
        }

        List<Wallet> allWallets = wallets.findAll();
        for (Wallet wallet : allWallets) {
            BigDecimal calculated = derivedBalances.getOrDefault(wallet.getId(), BigDecimal.ZERO);
            if (calculated.compareTo(wallet.getAvailableBalance()) != 0) {
                discrepancies.add("Wallet " + wallet.getId() + " cached balance differs from ledger balance");
            }
        }
        long completedTransfers = allTransfers.stream()
                .filter(transfer -> transfer.getStatus() == TransferStatus.COMPLETED).count();
        return new ReconciliationReport(Instant.now(), allWallets.size(), (int) completedTransfers, discrepancies);
    }

    private static BigDecimal sum(List<WalletLedgerEntry> entries, LedgerDirection direction) {
        return entries.stream().filter(entry -> entry.getDirection() == direction)
                .map(WalletLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
