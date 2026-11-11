package com.coreissuer.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferRiskService {
    private final StringRedisTemplate redis;

    @Value("${coreissuer.transfers.max-amount:100000.00}")
    private BigDecimal maxAmount;
    @Value("${coreissuer.transfers.velocity-limit:20}")
    private long velocityLimit;
    @Value("${coreissuer.transfers.velocity-window-seconds:60}")
    private long velocityWindowSeconds;

    public String evaluate(UUID userId, BigDecimal amount) {
        if (amount.compareTo(maxAmount) > 0) return "MAX_TRANSFER_AMOUNT_EXCEEDED";
        String key = "risk:transfer:" + userId;
        Long count = redis.opsForValue().increment(key);
        if (count == null) throw new IllegalStateException("Unable to evaluate transfer velocity");
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(velocityWindowSeconds));
        }
        return count > velocityLimit ? "TRANSFER_VELOCITY_EXCEEDED" : null;
    }
}
