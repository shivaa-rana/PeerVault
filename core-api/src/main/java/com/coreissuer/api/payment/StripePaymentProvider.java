package com.coreissuer.api.payment;

import com.coreissuer.api.exception.ProviderUnavailableException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class StripePaymentProvider implements PaymentProvider {
    private final RestClient restClient;
    private final String secretKey;

    public StripePaymentProvider(RestClient.Builder builder,
                                 @Value("${coreissuer.payments.stripe-secret-key:}") String secretKey) {
        this.restClient = builder.baseUrl("https://api.stripe.com/v1").build();
        this.secretKey = secretKey;
    }

    @Override
    public ProviderIntent createIntent(BigDecimal amount, String currency, String idempotencyKey) {
        if (secretKey.isBlank()) throw new ProviderUnavailableException("Stripe is not configured");
        long minorAmount;
        try {
            minorAmount = amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stripe funding amount supports at most two decimal places");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(minorAmount));
        form.add("currency", currency.toLowerCase(Locale.ROOT));
        form.add("automatic_payment_methods[enabled]", "true");
        StripeIntent response = restClient.post()
                .uri("/payment_intents")
                .headers(headers -> {
                    headers.setBearerAuth(secretKey);
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    headers.set("Idempotency-Key", idempotencyKey);
                })
                .body(form)
                .retrieve()
                .body(StripeIntent.class);
        if (response == null || response.id == null || response.clientSecret == null) {
            throw new ProviderUnavailableException("Stripe returned an incomplete payment intent");
        }
        return new ProviderIntent(response.id, response.clientSecret, response.status);
    }

    private static final class StripeIntent {
        public String id;
        @JsonProperty("client_secret")
        public String clientSecret;
        public String status;
    }
}
