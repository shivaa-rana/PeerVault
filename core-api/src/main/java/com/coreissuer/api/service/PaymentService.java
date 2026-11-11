package com.coreissuer.api.service;

import com.coreissuer.api.dto.FundWalletRequest;
import com.coreissuer.api.dto.PaymentIntentResponse;
import com.coreissuer.api.exception.ApiConflictException;
import com.coreissuer.api.exception.ProviderUnavailableException;
import com.coreissuer.api.exception.ResourceNotFoundException;
import com.coreissuer.api.payment.PaymentProvider;
import com.coreissuer.api.payment.ProviderIntent;
import com.coreissuer.api.payment.StripeWebhookVerifier;
import com.coreissuer.common.domain.LedgerDirection;
import com.coreissuer.common.domain.PaymentIntent;
import com.coreissuer.common.domain.TransferIdempotencyRecord;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletLedgerEntry;
import com.coreissuer.common.repository.PaymentIntentRepository;
import com.coreissuer.common.repository.TransferIdempotencyRepository;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WebhookEventRepository;
import com.coreissuer.common.util.CryptoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final String OPERATION = "POST:/api/wallet/fund";
    private final PaymentProvider provider;
    private final StripeWebhookVerifier webhookVerifier;
    private final ObjectMapper objectMapper;
    private final WalletRepository wallets;
    private final PaymentIntentRepository paymentIntents;
    private final WalletLedgerEntryRepository ledger;
    private final TransferIdempotencyRepository idempotency;
    private final WebhookEventRepository webhookEvents;
    private final AuditService auditService;

    @Value("${coreissuer.wallet.default-currency:INR}")
    private String defaultCurrency;

    @Transactional
    public PaymentIntentResponse createIntent(UUID userId, String idempotencyKey, FundWalletRequest request) {
        String key = idempotencyKey.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Idempotency-Key must not be blank");
        String currency = request.currency() == null || request.currency().isBlank()
                ? defaultCurrency.toUpperCase(Locale.ROOT) : request.currency().toUpperCase(Locale.ROOT);
        if (!"INR".equals(currency) && !"USD".equals(currency)) {
            throw new IllegalArgumentException("Unsupported funding currency");
        }
        String requestHash = CryptoUtils.simpleHash(request.amount().stripTrailingZeros().toPlainString()
                + "\n" + currency);
        int claimed = idempotency.claim(UUID.randomUUID(), userId, OPERATION, key, requestHash);
        if (claimed == 0) return replay(userId, key, requestHash);

        Wallet wallet = wallets.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet for funding currency not found"));
        String providerKey = "pv-" + CryptoUtils.simpleHash(userId + "\n" + OPERATION + "\n" + key);
        ProviderIntent external;
        try {
            external = provider.createIntent(request.amount(), currency, providerKey);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new ProviderUnavailableException("Stripe payment-intent request failed");
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setUserId(userId);
        intent.setWalletId(wallet.getId());
        intent.setProvider("STRIPE");
        intent.setProviderIntentId(external.id());
        intent.setAmount(request.amount());
        intent.setCurrency(currency);
        intent.setStatus(external.status());
        paymentIntents.save(intent);

        PaymentIntentResponse response = new PaymentIntentResponse("STRIPE", external.id(),
                external.clientSecret(), request.amount().toPlainString(), currency, external.status());
        saveIdempotentResponse(userId, key, requestHash, response);
        auditService.record(userId, "PAYMENT_INTENT_CREATED", external.id(), Map.of("provider", "STRIPE"));
        return response;
    }

    @Transactional
    public void processStripeWebhook(String payload, String signature) {
        webhookVerifier.verify(payload, signature);
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid webhook JSON");
        }
        String eventId = text(event, "id");
        String type = text(event, "type");
        if (eventId == null || type == null) throw new IllegalArgumentException("Webhook event is incomplete");
        if (webhookEvents.claim(eventId, type) == 0) return;

        if (!"payment_intent.succeeded".equals(type) && !"payment_intent.payment_failed".equals(type)
                && !"payment_intent.canceled".equals(type)) {
            return;
        }
        String providerIntentId = text(event.path("data").path("object"), "id");
        if (providerIntentId == null) throw new IllegalArgumentException("Webhook payment intent is missing");
        JsonNode providerPayment = event.path("data").path("object");
        PaymentIntent intent = paymentIntents.findByProviderIntentIdForUpdate(providerIntentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found"));
        if ("payment_intent.succeeded".equals(type)) {
            if ("SUCCEEDED".equals(intent.getStatus())) return;
            verifySuccessfulPayment(providerPayment, intent);
            Wallet wallet = wallets.lockAllInIdOrder(List.of(intent.getWalletId())).stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Funding wallet not found"));
            if (!"ACTIVE".equals(wallet.getStatus())) {
                throw new ApiConflictException("Funding wallet is not active");
            }
            wallet.setAvailableBalance(wallet.getAvailableBalance().add(intent.getAmount()));
            ledger.save(fundingEntry(wallet.getId(), null, intent, LedgerDirection.CREDIT));
            ledger.save(fundingEntry(null, "PAYMENT_CLEARING", intent, LedgerDirection.DEBIT));
            intent.setStatus("SUCCEEDED");
            auditService.record(intent.getUserId(), "PAYMENT_VERIFIED", intent.getId().toString(),
                    Map.of("provider", "STRIPE"));
        } else if (!"SUCCEEDED".equals(intent.getStatus())) {
            intent.setStatus("FAILED");
            auditService.record(intent.getUserId(), "PAYMENT_FAILED", intent.getId().toString(),
                    Map.of("provider", "STRIPE"));
        }

    }

    private static void verifySuccessfulPayment(JsonNode payment, PaymentIntent intent) {
        JsonNode receivedAmount = payment.get("amount_received");
        long expectedAmount;
        try {
            expectedAmount = intent.getAmount().movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Stored payment amount is not a valid Stripe minor-unit value", e);
        }
        String currency = text(payment, "currency");
        if (receivedAmount == null || !receivedAmount.isIntegralNumber()
                || receivedAmount.longValue() != expectedAmount
                || currency == null || !currency.equalsIgnoreCase(intent.getCurrency())) {
            throw new ApiConflictException("Stripe payment amount or currency does not match the payment intent");
        }
    }

    private PaymentIntentResponse replay(UUID userId, String key, String requestHash) {
        TransferIdempotencyRecord record = idempotency.findByUserIdAndOperationAndIdempotencyKey(
                userId, OPERATION, key).orElseThrow(() -> new IllegalStateException("Idempotency record missing"));
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ApiConflictException("Idempotency key was already used with a different request");
        }
        if (record.getResponseBody() == null) {
            throw new IllegalStateException("Idempotency result was not committed");
        }
        try {
            return objectMapper.readValue(record.getResponseBody(), PaymentIntentResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored idempotency response", e);
        }
    }

    private void saveIdempotentResponse(UUID userId, String key, String requestHash,
                                        PaymentIntentResponse response) {
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

    private static WalletLedgerEntry fundingEntry(UUID walletId, String accountCode, PaymentIntent intent,
                                                  LedgerDirection direction) {
        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setWalletId(walletId);
        entry.setAccountCode(accountCode);
        entry.setReferenceId(intent.getProviderIntentId());
        entry.setDirection(direction);
        entry.setAmount(intent.getAmount());
        entry.setCurrency(intent.getCurrency());
        return entry;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
