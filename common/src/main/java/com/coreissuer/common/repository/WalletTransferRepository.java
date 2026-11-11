package com.coreissuer.common.repository;

import com.coreissuer.common.domain.WalletTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletTransferRepository extends JpaRepository<WalletTransfer, UUID> {

    @Query("""
            select t from WalletTransfer t
            where t.senderWalletId in (select w.id from Wallet w where w.userId = :userId)
               or t.recipientWalletId in (select w.id from Wallet w where w.userId = :userId)
            order by t.createdAt desc
            """)
    Page<WalletTransfer> findVisibleToUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            select t from WalletTransfer t
            where t.id = :transferId and (
                t.senderWalletId in (select w.id from Wallet w where w.userId = :userId)
                or t.recipientWalletId in (select w.id from Wallet w where w.userId = :userId)
            )
            """)
    Optional<WalletTransfer> findVisibleTransfer(@Param("transferId") UUID transferId,
                                                  @Param("userId") UUID userId);
}
