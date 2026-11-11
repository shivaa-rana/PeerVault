package com.coreissuer.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final String SECRET = "a-test-secret-containing-at-least-thirty-two-bytes";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService(objectMapper, SECRET, 900);
        service.validateConfiguration();
    }

    @Test
    void issuedTokenContainsTheUserIdAndVerifies() {
        UUID userId = UUID.randomUUID();

        assertThat(service.verifyAndGetUserId(service.issue(userId))).isEqualTo(userId);
    }

    @Test
    void rejectsTamperedSignature() {
        String[] parts = service.issue(UUID.randomUUID()).split("\\.");
        parts[2] = encode("invalid-signature".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.verifyAndGetUserId(String.join(".", parts)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = signedToken(Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", UUID.randomUUID().toString(), "exp", Instant.now().getEpochSecond() - 1));

        assertThatThrownBy(() -> service.verifyAndGetUserId(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expired");
    }

    @Test
    void rejectsUnsupportedAlgorithmHeaderEvenWhenSignatureIsValid() throws Exception {
        String token = signedToken(Map.of("alg", "none", "typ", "JWT"),
                Map.of("sub", UUID.randomUUID().toString(), "exp", Instant.now().plusSeconds(60).getEpochSecond()));

        assertThatThrownBy(() -> service.verifyAndGetUserId(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
    }

    private String signedToken(Map<String, Object> header, Map<String, Object> claims) throws Exception {
        String content = encode(objectMapper.writeValueAsBytes(header)) + "."
                + encode(objectMapper.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + encode(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
