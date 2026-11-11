package com.coreissuer.api.service;

import com.coreissuer.common.domain.LedgerDirection;
import com.coreissuer.common.domain.TransferStatus;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletLedgerEntry;
import com.coreissuer.common.domain.WalletTransfer;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WalletTransferRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletReconciliationServiceTest {
    private final WalletLedgerEntryRepository ledger = mock(WalletLedgerEntryRepository.class);
    private final WalletTransferRepository transfers = mock(WalletTransferRepository.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final WalletReconciliationService service =
            new WalletReconciliationService(wallets, transfers, ledger);

    @Test
    void reportsMissingLedgerPostingsAndCountsOnlyCompletedTransfers() {
        WalletTransfer completed = transfer(TransferStatus.COMPLETED);
        WalletTransfer failed = transfer(TransferStatus.FAILED);
        when(ledger.findAll()).thenReturn(List.of());
        when(transfers.findAll()).thenReturn(List.of(completed, failed));
        when(wallets.findAll()).thenReturn(List.of());

        var report = service.reconcile();

        assertThat(report.completedTransfersChecked()).isEqualTo(1);
        assertThat(report.discrepancies()).hasSize(1);
        assertThat(report.discrepancies().get(0)).contains(completed.getId().toString());
    }

    @Test
    void reportsBalancedLedgerAndCachedWalletBalances() {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        WalletTransfer transfer = transfer(TransferStatus.COMPLETED);
        transfer.setSenderWalletId(senderId);
        transfer.setRecipientWalletId(recipientId);
        WalletLedgerEntry debit = entry(senderId, transfer.getId(), LedgerDirection.DEBIT);
        WalletLedgerEntry credit = entry(recipientId, transfer.getId(), LedgerDirection.CREDIT);
        WalletLedgerEntry openingBalance = entry(senderId, null, LedgerDirection.CREDIT);
        openingBalance.setAmount(new BigDecimal("100.00"));
        Wallet sender = wallet(senderId, new BigDecimal("90.00"));
        Wallet recipient = wallet(recipientId, new BigDecimal("10.00"));
        when(ledger.findAll()).thenReturn(List.of(openingBalance, debit, credit));
        when(transfers.findAll()).thenReturn(List.of(transfer));
        when(wallets.findAll()).thenReturn(List.of(sender, recipient));

        assertThat(service.reconcile().balanced()).isTrue();
    }

    private static WalletTransfer transfer(TransferStatus status) {
        WalletTransfer transfer = new WalletTransfer();
        transfer.setId(UUID.randomUUID());
        transfer.setSenderWalletId(UUID.randomUUID());
        transfer.setRecipientWalletId(UUID.randomUUID());
        transfer.setAmount(new BigDecimal("10.00"));
        transfer.setCurrency("USD");
        transfer.setStatus(status);
        return transfer;
    }

    private static WalletLedgerEntry entry(UUID walletId, UUID transferId, LedgerDirection direction) {
        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setWalletId(walletId);
        entry.setTransferId(transferId);
        entry.setDirection(direction);
        entry.setAmount(new BigDecimal("10.00"));
        entry.setCurrency("USD");
        return entry;
    }

    private static Wallet wallet(UUID id, BigDecimal balance) {
        Wallet wallet = new Wallet();
        wallet.setId(id);
        wallet.setAvailableBalance(balance);
        return wallet;
    }
}
