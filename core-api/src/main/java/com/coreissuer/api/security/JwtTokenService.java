package com.coreissuer.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtTokenService {
    private static final String ALGORITHM = "HmacSHA256";
    private final ObjectMapper objectMapper;
    private final String secret;
    private final long expirationSeconds;

    public JwtTokenService(ObjectMapper objectMapper,
                           @Value("${coreissuer.jwt.secret}") String secret,
                           @Value("${coreissuer.jwt.expiration:900}") long expirationSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.expirationSeconds = expirationSeconds;
    }

    @PostConstruct
    void validateConfiguration() {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("PEERVAULT_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (expirationSeconds < 60 || expirationSeconds > 86_400) {
            throw new IllegalStateException("JWT expiration must be between 60 and 86400 seconds");
        }
    }

    public String issue(UUID userId) {
        try {
            String header = encode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", userId.toString());
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().plusSeconds(expirationSeconds).getEpochSecond());
            String payload = encode(objectMapper.writeValueAsBytes(claims));
            String content = header + "." + payload;
            return content + "." + encode(sign(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to issue access token", e);
        }
    }

    public UUID verifyAndGetUserId(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT");
            Map<?, ?> header = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new IllegalArgumentException("Invalid JWT header");
            }
            String content = parts[0] + "." + parts[1];
            byte[] expected = sign(content);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("Invalid JWT signature");
            }
            Map<?, ?> claims = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            Object expiration = claims.get("exp");
            Object subject = claims.get("sub");
            if (!(expiration instanceof Number) || ((Number) expiration).longValue() <= Instant.now().getEpochSecond()
                    || !(subject instanceof String)) {
                throw new IllegalArgumentException("Expired or invalid JWT");
            }
            return UUID.fromString((String) subject);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Invalid JWT", e);
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
