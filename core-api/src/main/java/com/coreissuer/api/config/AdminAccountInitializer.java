package com.coreissuer.api.config;

import com.coreissuer.common.domain.UserRole;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletUser;
import com.coreissuer.common.repository.WalletRepository;
import com.coreissuer.common.repository.WalletUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
public class AdminAccountInitializer implements ApplicationRunner {
    private final WalletUserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final List<String> currencies;

    public AdminAccountInitializer(WalletUserRepository users, WalletRepository wallets,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${coreissuer.admin.email:}") String email,
                                   @Value("${coreissuer.admin.password:}") String password,
                                   @Value("${coreissuer.wallet.supported-currencies:INR,USD}") List<String> currencies) {
        this.users = users;
        this.wallets = wallets;
        this.passwordEncoder = passwordEncoder;
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.password = password;
        this.currencies = currencies;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) return;
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("PEERVAULT_ADMIN_EMAIL and PEERVAULT_ADMIN_PASSWORD must be configured together");
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 12 || passwordBytes > 72) {
            throw new IllegalStateException("PEERVAULT_ADMIN_PASSWORD must be 12 to 72 UTF-8 bytes");
        }
        WalletUser user = users.findByEmail(email).orElseGet(() -> {
            WalletUser created = new WalletUser();
            created.setEmail(email);
            created.setFullName("PeerVault Administrator");
            created.setPasswordHash(passwordEncoder.encode(password));
            created.setRole(UserRole.ADMIN);
            return users.saveAndFlush(created);
        });
        if (user.getRole() != UserRole.ADMIN) {
            throw new IllegalStateException("Configured administrator email is already assigned to a non-admin user");
        }
        for (String currencyValue : currencies) {
            String currency = currencyValue.trim().toUpperCase(Locale.ROOT);
            if (wallets.findByUserIdAndCurrency(user.getId(), currency).isEmpty()) {
                Wallet wallet = new Wallet();
                wallet.setUserId(user.getId());
                wallet.setCurrency(currency);
                wallets.save(wallet);
            }
        }
    }
}
