package com.coreissuer.api.integration;

import com.coreissuer.api.dto.AuthResponse;
import com.coreissuer.api.dto.RegisterRequest;
import com.coreissuer.api.dto.TransferRequest;
import com.coreissuer.api.dto.TransferResponse;
import com.coreissuer.api.service.AuthService;
import com.coreissuer.api.service.WalletTransferService;
import com.coreissuer.common.domain.LedgerDirection;
import com.coreissuer.common.domain.Wallet;
import com.coreissuer.common.domain.WalletLedgerEntry;
import com.coreissuer.common.repository.WalletLedgerEntryRepository;
import com.coreissuer.common.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "coreissuer.security.pepper=test-pepper-value-for-wallet-it",
        "coreissuer.jwt.secret=wallet-integration-test-jwt-secret-at-least-thirty-two-bytes"
})
class WalletTransferIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("peervault")
            .withUsername("peervault")
            .withPassword("integration-test-password");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private AuthService authService;
    @Autowired private WalletTransferService transferService;
    @Autowired private WalletRepository wallets;
    @Autowired private WalletLedgerEntryRepository ledger;

    @Test
    void concurrentTransfersCannotSpendTheSameBalanceTwice() throws Exception {
        Accounts accounts = accountsWithSenderBalance("5.00");
        int threadCount = 3;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<TransferResponse>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String key = UUID.randomUUID().toString();
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return transferService.transfer(accounts.sender().user().id(), key,
                            request(accounts.recipient(), "2.00"));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<TransferResponse> responses = new ArrayList<>();
            for (Future<TransferResponse> result : results) {
                responses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).filteredOn(response -> "COMPLETED".equals(response.status())).hasSize(2);
            assertThat(responses).filteredOn(response -> "FAILED".equals(response.status())).hasSize(1);
            assertThat(balance(accounts.sender())).isEqualByComparingTo("1.00");
            assertThat(responses.stream().filter(response -> "COMPLETED".equals(response.status()))
                    .flatMap(response -> ledger.findByTransferId(response.id()).stream()))
                    .hasSize(4);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentRetriesWithSameIdempotencyKeyCreateOneTransfer() throws Exception {
        Accounts accounts = accountsWithSenderBalance("5.00");
        int threadCount = 3;
        String idempotencyKey = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<TransferResponse>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return transferService.transfer(accounts.sender().user().id(), idempotencyKey,
                            request(accounts.recipient(), "2.00"));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<TransferResponse> responses = new ArrayList<>();
            for (Future<TransferResponse> result : results) {
                responses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).extracting(TransferResponse::id).containsOnly(responses.get(0).id());
            assertThat(ledger.findByTransferId(responses.get(0).id())).hasSize(2);
            assertThat(balance(accounts.sender())).isEqualByComparingTo("3.00");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Accounts accountsWithSenderBalance(String amount) {
        AuthResponse sender = register("sender");
        AuthResponse recipient = register("recipient");
        Wallet senderWallet = wallets.findByUserIdAndCurrency(sender.user().id(), "USD").orElseThrow();
        senderWallet.setAvailableBalance(new BigDecimal(amount));
        wallets.saveAndFlush(senderWallet);

        WalletLedgerEntry openingBalance = new WalletLedgerEntry();
        openingBalance.setWalletId(senderWallet.getId());
        openingBalance.setReferenceId("opening-" + UUID.randomUUID());
        openingBalance.setDirection(LedgerDirection.CREDIT);
        openingBalance.setAmount(new BigDecimal(amount));
        openingBalance.setCurrency("USD");
        ledger.saveAndFlush(openingBalance);
        return new Accounts(sender, recipient);
    }

    private AuthResponse register(String role) {
        String suffix = UUID.randomUUID().toString();
        return authService.register(new RegisterRequest(role + "-" + suffix + "@example.test",
                "long-password-" + suffix, role));
    }

    private static TransferRequest request(AuthResponse recipient, String amount) {
        return new TransferRequest(recipient.user().email(), new BigDecimal(amount), "USD");
    }

    private BigDecimal balance(AuthResponse account) {
        return wallets.findByUserIdAndCurrency(account.user().id(), "USD")
                .orElseThrow().getAvailableBalance();
    }

    private record Accounts(AuthResponse sender, AuthResponse recipient) {
    }
}
