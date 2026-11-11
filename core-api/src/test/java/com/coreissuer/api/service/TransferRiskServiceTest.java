package com.coreissuer.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferRiskServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final TransferRiskService service = new TransferRiskService(redis);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service, "maxAmount", new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(service, "velocityLimit", 2L);
        ReflectionTestUtils.setField(service, "velocityWindowSeconds", 60L);
    }

    @Test
    void flagsTransfersAboveVelocityLimit() {
        when(values.increment("risk:transfer:" + userId)).thenReturn(3L);

        assertThat(service.evaluate(userId, new BigDecimal("10.00")))
                .isEqualTo("TRANSFER_VELOCITY_EXCEEDED");
    }

    @Test
    void blocksAmountsWithoutCallingRedis() {
        assertThat(service.evaluate(userId, new BigDecimal("1000.01")))
                .isEqualTo("MAX_TRANSFER_AMOUNT_EXCEEDED");
    }

    @Test
    void failsClosedWhenRedisCannotReturnVelocityCount() {
        when(values.increment("risk:transfer:" + userId)).thenReturn(null);

        assertThatThrownBy(() -> service.evaluate(userId, new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("velocity");
    }
}
