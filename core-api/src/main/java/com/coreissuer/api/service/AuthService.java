package com.coreissuer.api.service;

import com.coreissuer.api.dto.AuthResponse;
import com.coreissuer.api.dto.LoginRequest;
import com.coreissuer.api.dto.RegisterRequest;
import com.coreissuer.api.dto.UserResponse;
import com.coreissuer.api.exception.ApiConflictException;
import com.coreissuer.api.exception.ResourceNotFoundException;
import com.coreissuer.api.security.JwtTokenService;
import com.coreissuer.common.domain.UserRole;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletUser;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WalletUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final WalletUserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final AuditService auditService;

    @Value("${coreissuer.wallet.default-currency:INR}")
    private String defaultCurrency;
    @Value("${coreissuer.wallet.supported-currencies:INR,USD}")
    private java.util.List<String> supportedCurrencies;
    @Value("${coreissuer.jwt.expiration:900}")
    private long expirationSeconds;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new ApiConflictException("An account with this email already exists");
        validatePasswordLength(request.password());

        WalletUser user = new WalletUser();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiConflictException("An account with this email already exists");
        }

        for (String currency : supportedCurrencies) {
            Wallet wallet = new Wallet();
            wallet.setUserId(user.getId());
            wallet.setCurrency(currency.trim().toUpperCase(Locale.ROOT));
            wallet.setAvailableBalance(java.math.BigDecimal.ZERO);
            wallets.save(wallet);
        }
        auditService.record(null, "USER_REGISTERED", user.getId().toString(), Map.of());
        return createResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        WalletUser user = users.findByEmail(email)
                .filter(candidate -> request.password().getBytes(StandardCharsets.UTF_8).length <= 72
                        && passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .filter(WalletUser::isEnabled)
                .orElse(null);
        if (user == null) {
            auditService.record(null, "LOGIN_FAILED", null, Map.of("reason", "INVALID_CREDENTIALS"));
            throw new BadCredentialsException("Invalid email or password");
        }
        auditService.record(user.getId(), "LOGIN_SUCCEEDED", user.getId().toString(), Map.of());
        return createResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        WalletUser user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return toResponse(user);
    }

    private AuthResponse createResponse(WalletUser user) {
        return new AuthResponse(tokens.issue(user.getId()), "Bearer", expirationSeconds, toResponse(user));
    }

    private static UserResponse toResponse(WalletUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePasswordLength(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72) {
            throw new IllegalArgumentException("Password must be 12 to 72 UTF-8 bytes");
        }
    }
}
