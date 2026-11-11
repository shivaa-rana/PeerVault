package com.coreissuer.common.repository;

import com.coreissuer.common.domain.WalletLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, Long> {
    List<WalletLedgerEntry> findByTransferId(UUID transferId);

    @Query("""
            select e from WalletLedgerEntry e
            where e.walletId in (select w.id from Wallet w where w.userId = :userId)
            order by e.createdAt desc
            """)
    Page<WalletLedgerEntry> findVisibleToUser(@Param("userId") UUID userId, Pageable pageable);
}
