package com.coreissuer.api.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookVerifierTest {
    private static final String SECRET = "whsec_test_secret";

    @Test
    void acceptsValidSignatureWithinTolerance() throws Exception {
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(SECRET);
        String payload = "{\"type\":\"payment_intent.succeeded\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        assertThatCode(() -> verifier.verify(payload, signatureHeader(timestamp, payload)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSignatureForDifferentPayload() throws Exception {
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(SECRET);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signatureHeader(timestamp, "{\"type\":\"payment_intent.succeeded\"}");

        assertThatThrownBy(() -> verifier.verify("{\"type\":\"payment_intent.failed\"}", signature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Stripe signature");
    }

    @Test
    void rejectsExpiredSignature() throws Exception {
        StripeWebhookVerifier verifier = new StripeWebhookVerifier(SECRET);
        String payload = "{}";
        String timestamp = Long.toString(Instant.now().getEpochSecond() - 301);

        assertThatThrownBy(() -> verifier.verify(payload, signatureHeader(timestamp, payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expired Stripe signature");
    }

    private static String signatureHeader(String timestamp, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signedContent = timestamp + "." + payload;
        return "t=" + timestamp + ",v1="
                + HexFormat.of().formatHex(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
    }
}
