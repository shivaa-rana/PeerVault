package com.coreissuer.api.payment;

import java.math.BigDecimal;

public interface PaymentProvider {
    ProviderIntent createIntent(BigDecimal amount, String currency, String idempotencyKey);
}
