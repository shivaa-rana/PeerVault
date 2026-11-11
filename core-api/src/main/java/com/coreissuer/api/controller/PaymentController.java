package com.coreissuer.api.controller;

import com.coreissuer.api.dto.FundWalletRequest;
import com.coreissuer.api.dto.PaymentIntentResponse;
import com.coreissuer.api.security.AuthenticatedUser;
import com.coreissuer.api.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/wallet/fund")
    public PaymentIntentResponse fund(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 80) String idempotencyKey,
            @Valid @RequestBody FundWalletRequest request) {
        return paymentService.createIntent(user.id(), idempotencyKey, request);
    }

    @PostMapping("/payments/stripe/webhook")
    public void stripeWebhook(@RequestHeader("Stripe-Signature") String signature,
                              @RequestBody byte[] payload) {
        paymentService.processStripeWebhook(new String(payload, StandardCharsets.UTF_8), signature);
    }
}
