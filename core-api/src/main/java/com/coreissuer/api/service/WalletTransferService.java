package com.coreissuer.api.service;

import com.coreissuer.api.dto.TransferRequest;
import com.coreissuer.api.dto.TransferResponse;
import com.coreissuer.api.exception.ApiConflictException;
import com.coreissuer.api.exception.ResourceNotFoundException;
import com.coreissuer.common.domain.FraudFlag;
import com.coreissuer.common.domain.LedgerDirection;
import com.coreissuer.common.domain.TransferIdempotencyRecord;
import com.coreissuer.common.domain.TransferStatus;
import com.coreissuer.common.domain.TransferStateMachine;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletLedgerEntry;
import com.coreissuer.common.domain.WalletTransfer;
import com.coreissuer.common.domain.WalletUser;
import com.coreissuer.common.repository.FraudFlagRepository;
import com.coreissuer.common.repository.TransferIdempotencyRepository;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WalletTransferRepository;
import com.coreissuer.common.repository.WalletUserRepository;
import com.coreissuer.common.util.CryptoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletTransferService {
    private static final String OPERATION = "POST:/api/transfers";

    private final WalletUserRepository users;
    private final WalletRepository wallets;
    private final WalletTransferRepository transfers;
    private final WalletLedgerEntryRepository ledger;
    private final TransferIdempotencyRepository idempotency;
    private final FraudFlagRepository fraudFlags;
    private final TransferRiskService riskService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional
    public TransferResponse transfer(UUID userId, String idempotencyKey, TransferRequest request) {
        String key = idempotencyKey.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Idempotency-Key must not be blank");
        String currency = request.currency().toUpperCase(Locale.ROOT);
        String normalizedRecipient = normalizeEmail(request.recipientEmail());
        String requestHash = CryptoUtils.simpleHash(normalizedRecipient + "\n"
                + request.amount().stripTrailingZeros().toPlainString() + "\n" + currency);

        int claimed = idempotency.claim(UUID.randomUUID(), userId, OPERATION, key, requestHash);
        if (claimed == 0) return replay(userId, key, requestHash);

        WalletUser recipient = users.findByEmail(normalizedRecipient)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient not found"));
        Wallet senderCandidate = wallets.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found"));
        Wallet recipientCandidate = wallets.findByUserIdAndCurrency(recipient.getId(), currency)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient wallet for currency not found"));
        if (senderCandidate.getId().equals(recipientCandidate.getId())) {
            throw new ApiConflictException("A wallet cannot transfer to itself");
        }

        List<Wallet> lockedWallets = wallets.lockAllInIdOrder(
                List.of(senderCandidate.getId(), recipientCandidate.getId()));
        if (lockedWallets.size() != 2) throw new ResourceNotFoundException("Transfer wallet not found");
        Wallet sender = lockedWallets.stream().filter(w -> w.getId().equals(senderCandidate.getId()))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found"));
        Wallet receiver = lockedWallets.stream().filter(w -> w.getId().equals(recipientCandidate.getId()))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Recipient wallet not found"));
        if (!"ACTIVE".equals(sender.getStatus()) || !"ACTIVE".equals(receiver.getStatus())) {
            throw new ApiConflictException("Both wallets must be active");
        }

        WalletTransfer transfer = new WalletTransfer();
        transfer.setSenderWalletId(sender.getId());
        transfer.setRecipientWalletId(receiver.getId());
        transfer.setInitiatedBy(userId);
        transfer.setAmount(request.amount());
        transfer.setCurrency(currency);
        transfer.setStatus(TransferStatus.INITIATED);
        transfer = transfers.saveAndFlush(transfer);
        transfer.setStatus(TransferStateMachine.transition(transfer.getStatus(), TransferStatus.PROCESSING));

        String riskReason = riskService.evaluate(userId, request.amount());
        if (riskReason != null) {
            transfer.setStatus(TransferStateMachine.transition(transfer.getStatus(), TransferStatus.FAILED));
            transfer.setFailureReason(riskReason);
            FraudFlag flag = new FraudFlag();
            flag.setUserId(userId);
            flag.setTransferId(transfer.getId());
            flag.setRiskReason(riskReason);
            flag.setSeverity("HIGH");
            fraudFlags.save(flag);
            auditService.record(userId, "TRANSFER_FLAGGED", transfer.getId().toString(),
                    Map.of("reason", riskReason));
        } else if (sender.getAvailableBalance().compareTo(request.amount()) < 0) {
            transfer.setStatus(TransferStateMachine.transition(transfer.getStatus(), TransferStatus.FAILED));
            transfer.setFailureReason("INSUFFICIENT_FUNDS");
            auditService.record(userId, "TRANSFER_FAILED", transfer.getId().toString(),
                    Map.of("reason", "INSUFFICIENT_FUNDS"));
        } else {
            sender.setAvailableBalance(sender.getAvailableBalance().subtract(request.amount()));
            receiver.setAvailableBalance(receiver.getAvailableBalance().add(request.amount()));
            transfer.setStatus(TransferStateMachine.transition(transfer.getStatus(), TransferStatus.COMPLETED));

            ledger.save(entry(sender.getId(), transfer, LedgerDirection.DEBIT));
            ledger.save(entry(receiver.getId(), transfer, LedgerDirection.CREDIT));
            auditService.record(userId, "TRANSFER_COMPLETED", transfer.getId().toString(), Map.of());
        }

        TransferResponse response = toResponse(transfers.save(transfer));
        saveIdempotentResponse(userId, key, requestHash, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> list(UUID userId, int page, int size) {
        return transfers.findVisibleToUser(userId, PageRequest.of(page, size)).map(WalletTransferService::toResponse);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID userId, UUID transferId) {
        return transfers.findVisibleTransfer(transferId, userId).map(WalletTransferService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found"));
    }

    private TransferResponse replay(UUID userId, String key, String requestHash) {
        TransferIdempotencyRecord record = idempotency.findByUserIdAndOperationAndIdempotencyKey(
                userId, OPERATION, key).orElseThrow(() -> new IllegalStateException("Idempotency record missing"));
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ApiConflictException("Idempotency key was already used with a different request");
        }
        if (record.getResponseBody() == null) {
            throw new IllegalStateException("Idempotency result was not committed");
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored idempotency response", e);
        }
    }

    private void saveIdempotentResponse(UUID userId, String key, String requestHash, TransferResponse response) {
        TransferIdempotencyRecord record = idempotency.findByUserIdAndOperationAndIdempotencyKey(
                userId, OPERATION, key).orElseThrow(() -> new IllegalStateException("Idempotency claim missing"));
        record.setRequestHash(requestHash);
        try {
            record.setResponseBody(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to store idempotency response", e);
        }
        idempotency.save(record);
    }

    private WalletLedgerEntry entry(UUID walletId, WalletTransfer transfer, LedgerDirection direction) {
        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setWalletId(walletId);
        entry.setTransferId(transfer.getId());
        entry.setReferenceId(transfer.getId().toString());
        entry.setDirection(direction);
        entry.setAmount(transfer.getAmount());
        entry.setCurrency(transfer.getCurrency());
        return entry;
    }

    private static TransferResponse toResponse(WalletTransfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getSenderWalletId(),
                transfer.getRecipientWalletId(), transfer.getAmount().toPlainString(), transfer.getCurrency(),
                transfer.getStatus().name(), transfer.getFailureReason(), transfer.getCreatedAt());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
