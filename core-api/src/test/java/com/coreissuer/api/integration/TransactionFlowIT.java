package com.coreissuer.api.integration;

import com.coreissuer.api.dto.AuthorizeRequest;
import com.coreissuer.api.dto.AuthorizeResponse;
import com.coreissuer.api.dto.CardResponse;
import com.coreissuer.api.dto.ProvisionCardRequest;
import com.coreissuer.api.service.CardService;
import com.coreissuer.api.service.TransactionService;
import com.coreissuer.common.domain.Account;
import com.coreissuer.common.domain.CardTier;
import com.coreissuer.common.domain.TransactionStatus;
import com.coreissuer.common.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests requiring Docker (Testcontainers spins up a real PostgreSQL instance).
 * Run with: ./mvnw test -pl core-api -Dtest=TransactionFlowIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "coreissuer.security.pepper=test-pepper-value-for-it",
        "coreissuer.jwt.secret=integration-test-jwt-secret-at-least-thirty-two-bytes",
        "coreissuer.security.admin-user=it-admin",
        "coreissuer.security.admin-password=it-password"
})
class TransactionFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("peervault")
            .withUsername("peervault")
            .withPassword("integration-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private TransactionService transactionService;
    @Autowired private CardService cardService;
    @Autowired private AccountRepository accountRepository;

    // ── helpers ───────────────────────────────────────────────────────────────

    private CardResponse provisionCard(String balance) {
        ProvisionCardRequest req = new ProvisionCardRequest();
        req.setTier(CardTier.STANDARD);
        req.setInitialBalance(new BigDecimal(balance));
        req.setCurrency("USD");
        return cardService.provisionCard(req);
    }

    private AuthorizeRequest authorizeRequest(String cardId, String amount) {
        return AuthorizeRequest.builder()
                .cardId(cardId).merchantId("1").mcc("5411")
                .amount(new BigDecimal(amount)).currency("USD")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("authorize then capture: cardholder balance stays reduced, merchant balance increases")
    void authorizeCapture_balancesCorrect() {
        CardResponse card = provisionCard("100.00");

        AuthorizeResponse auth = transactionService.authorize(authorizeRequest(card.getId(), "30.00"));
        assertThat(auth.getStatus()).isEqualTo(TransactionStatus.AUTHORIZED.name());
        assertThat(auth.getAvailableBalanceAfter()).isEqualByComparingTo("70.00");

        transactionService.capture(auth.getTransactionId());

        Account cardholder = accountRepository.findById(card.getAccountId())
                .orElseThrow(() -> new AssertionError("cardholder account missing"));
        assertThat(cardholder.getAvailableBalance()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("authorize then reverse: cardholder balance is fully restored")
    void authorizeReverse_balanceRestored() {
        CardResponse card = provisionCard("100.00");

        AuthorizeResponse auth = transactionService.authorize(authorizeRequest(card.getId(), "30.00"));
        transactionService.reverse(auth.getTransactionId());

        Account cardholder = accountRepository.findById(card.getAccountId())
                .orElseThrow(() -> new AssertionError("cardholder account missing"));
        assertThat(cardholder.getAvailableBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("authorize, capture, then refund: cardholder balance is fully restored")
    void authorizeCaptureRefund_balanceRestored() {
        CardResponse card = provisionCard("100.00");

        AuthorizeResponse auth = transactionService.authorize(authorizeRequest(card.getId(), "30.00"));
        transactionService.capture(auth.getTransactionId());
        transactionService.refund(auth.getTransactionId());

        Account cardholder = accountRepository.findById(card.getAccountId())
                .orElseThrow(() -> new AssertionError("cardholder account missing"));
        assertThat(cardholder.getAvailableBalance()).isEqualByComparingTo("100.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrent authorization — the headline pessimistic-lock test
    //
    // 3 threads simultaneously authorize $2 each on a card with $5.
    // Only 2 can succeed ($2+$2=$4 ≤ $5). The 3rd must decline INSUFFICIENT_FUNDS.
    // 3 threads keeps us within the VelocityCheck window (blocks at count > 3).
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent authorize: pessimistic lock prevents overbooking — exactly 2 succeed, 1 declines, final balance = $1")
    void concurrentAuthorize_pessimisticLock_preventsOverbooking() throws InterruptedException {
        CardResponse card = provisionCard("5.00");
        String cardId = card.getId();

        int threadCount = 3;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        AtomicInteger authorized = new AtomicInteger();
        AtomicInteger declined   = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    AuthorizeResponse resp = transactionService.authorize(
                            authorizeRequest(cardId, "2.00"));
                    if (TransactionStatus.AUTHORIZED.name().equals(resp.getStatus())) {
                        authorized.incrementAndGet();
                    } else {
                        declined.incrementAndGet();
                    }
                } catch (Exception e) {
                    declined.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await();          // wait until all threads are ready
        start.countDown();      // fire them all at once
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(authorized.get()).isEqualTo(2);
        assertThat(declined.get()).isEqualTo(1);

        Account account = accountRepository.findById(card.getAccountId())
                .orElseThrow(() -> new AssertionError("cardholder account missing"));
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("1.00");
    }
}
