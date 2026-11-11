package com.coreissuer.api.payment;

public record ProviderIntent(String id, String clientSecret, String status) {
}
