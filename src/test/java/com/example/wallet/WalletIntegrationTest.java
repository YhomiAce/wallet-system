package com.example.wallet;

import com.example.wallet.account.WalletService;
import com.example.wallet.cache.BalanceService;
import com.example.wallet.common.WalletException;
import com.example.wallet.domain.Account;
import com.example.wallet.recon.ReconciliationJob;
import com.example.wallet.transfer.TransferService;
import com.example.wallet.transfer.TransferService.DepositCommand;
import com.example.wallet.transfer.TransferService.TransferCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class WalletIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired WalletService wallets;
    @Autowired TransferService transfers;
    @Autowired BalanceService balances;
    @Autowired ReconciliationJob recon;
    @Autowired JdbcClient jdbc;

    // ---- helpers -----------------------------------------------------------------------------

    private Account wallet() { return wallets.create(UUID.randomUUID().toString()); }
    private static String key() { return UUID.randomUUID().toString(); }
    private long balance(Account a) { return balances.get(a.id()).balanceKobo(); }

    private void fund(Account a, long kobo) {
        transfers.deposit(new DepositCommand(key(), a.accountNumber(), kobo, "fund"));
    }

    private TransferCommand transferCmd(String key, Account from, Account to, long kobo) {
        return new TransferCommand(key, from.accountNumber(), to.accountNumber(), kobo, "test");
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    void transferWithFeesPostsOneBalancedJournal() {
        Account a = wallet(), b = wallet();
        fund(a, 10_000_000);                                            // N100,000.00

        var tx = transfers.transfer(transferCmd(key(), a, b, 1_000_000)).tx();   // N10,000.00

        assertThat(tx.stampDuty()).isEqualTo(5_000);                    // N50.00
        assertThat(tx.fee()).isEqualTo(1_000);                          // N10.00
        assertThat(tx.vat()).isEqualTo(75);                             // N0.75
        assertThat(tx.totalDebit()).isEqualTo(1_006_075);               // N10,060.75
        assertThat(balance(a)).isEqualTo(10_000_000 - 1_006_075);
        assertThat(balance(b)).isEqualTo(1_000_000);

        long debits = jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE journal_id = :j AND direction = 'DEBIT'")
                .param("j", tx.journalId()).query(Long.class).single();
        long credits = jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE journal_id = :j AND direction = 'CREDIT'")
                .param("j", tx.journalId()).query(Long.class).single();
        assertThat(debits).isEqualTo(credits).isEqualTo(1_006_075);
    }

    @Test
    void retryWithSameIdempotencyKeyDoesNotDoubleCharge() {
        Account a = wallet(), b = wallet();
        fund(a, 10_000_000);
        String k = key();

        var first = transfers.transfer(transferCmd(k, a, b, 1_000_000));
        long afterFirst = balance(a);
        var second = transfers.transfer(transferCmd(k, a, b, 1_000_000));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.tx().id()).isEqualTo(first.tx().id());
        assertThat(balance(a)).isEqualTo(afterFirst);

        // Same key, different payload -> rejected, never silently executed.
        assertThatThrownBy(() -> transfers.transfer(transferCmd(k, a, b, 2_000)))
                .isInstanceOfSatisfying(WalletException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void insufficientFundsIsRejectedAndNothingIsPosted() {
        Account a = wallet(), b = wallet();
        fund(a, 5_000);

        assertThatThrownBy(() -> transfers.transfer(transferCmd(key(), a, b, 1_000_000)))
                .isInstanceOfSatisfying(WalletException.class,
                        e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(balance(a)).isEqualTo(5_000);
        assertThat(balance(b)).isZero();
    }

    @Test
    void concurrentTransfersNeverOverdrawAndLedgerStaysConsistent() throws Exception {
        Account a = wallet(), b = wallet();
        fund(a, 100_000);                                   // N1,000.00
        // Each transfer costs 10,000 + 1,000 fee + 75 VAT = 11,075 kobo -> exactly 9 can succeed.

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    transfers.transfer(transferCmd(key(), a, b, 10_000));
                    return true;
                } catch (WalletException e) {
                    if ("INSUFFICIENT_FUNDS".equals(e.code())) return false;
                    throw e;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) if (f.get()) succeeded++;
        pool.shutdown();

        assertThat(succeeded).isEqualTo(9);
        assertThat(balance(a)).isEqualTo(100_000 - 9 * 11_075);   // 325
        assertThat(balance(b)).isEqualTo(9 * 10_000);

        var report = recon.run();
        assertThat(report.mismatchedAccounts()).isZero();
        assertThat(report.ledgerBalanced()).isTrue();
    }

    @Test
    void reversalRestoresBalancesAndCanOnlyHappenOnce() {
        Account a = wallet(), b = wallet();
        fund(a, 10_000_000);
        var tx = transfers.transfer(transferCmd(key(), a, b, 1_000_000)).tx();

        var reversed = transfers.reverse(tx.id(), "customer dispute");
        assertThat(reversed.tx().status()).isEqualTo("REVERSED");
        assertThat(balance(a)).isEqualTo(10_000_000);
        assertThat(balance(b)).isZero();

        assertThat(transfers.reverse(tx.id(), "again").replayed()).isTrue();   // idempotent
        assertThat(recon.run().mismatchedAccounts()).isZero();
    }

    @Test
    void ledgerIsAppendOnly() {
        Account a = wallet(), b = wallet();
        fund(a, 100_000);
        var tx = transfers.transfer(transferCmd(key(), a, b, 10_000)).tx();

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = amount + 1 WHERE journal_id = :j")
                .param("j", tx.journalId()).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE journal_id = :j")
                .param("j", tx.journalId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
