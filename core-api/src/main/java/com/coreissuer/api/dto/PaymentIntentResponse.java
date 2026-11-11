package com.coreissuer.api.dto;

public record PaymentIntentResponse(String provider, String providerIntentId, String clientSecret,
                                    String amount, String currency, String status) {
}
