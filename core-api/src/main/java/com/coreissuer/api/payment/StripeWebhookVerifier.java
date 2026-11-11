package com.coreissuer.api.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

@Component
public class StripeWebhookVerifier {
    private static final long TOLERANCE_SECONDS = 300;
    private final String webhookSecret;

    public StripeWebhookVerifier(@Value("${coreissuer.payments.stripe-webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public void verify(String payload, String signatureHeader) {
        if (webhookSecret.isBlank()) throw new IllegalStateException("Stripe webhook verification is not configured");
        if (signatureHeader == null) throw new IllegalArgumentException("Missing Stripe signature");
        String timestamp = null;
        List<byte[]> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            if ("t".equals(pair[0])) timestamp = pair[1];
            if ("v1".equals(pair[0])) {
                try {
                    signatures.add(HexFormat.of().parseHex(pair[1]));
                } catch (IllegalArgumentException ignored) {
                    // Invalid signatures are rejected below without revealing parsing details.
                }
            }
        }
        if (timestamp == null || signatures.isEmpty()) throw new IllegalArgumentException("Invalid Stripe signature");
        long eventTime;
        try {
            eventTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Stripe signature timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (eventTime < now - TOLERANCE_SECONDS || eventTime > now + TOLERANCE_SECONDS) {
            throw new IllegalArgumentException("Expired Stripe signature");
        }
        byte[] expected = hmac(timestamp + "." + payload);
        boolean valid = false;
        for (byte[] signature : signatures) {
            valid |= MessageDigest.isEqual(expected, signature);
        }
        if (!valid) {
            throw new IllegalArgumentException("Invalid Stripe signature");
        }
    }

    private byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to verify Stripe webhook", e);
        }
    }
}
