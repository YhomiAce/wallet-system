package com.example.wallet.recon;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proves the books agree with the cached balances:
 *   1. every tracked account's stored balance == balance recomputed from its ledger entries
 *   2. total debits == total credits across the whole ledger
 * Runs on a REPEATABLE READ snapshot so concurrent transfers cannot cause false alarms.
 * In a multi-instance deployment, guard the schedule with a distributed lock (e.g. ShedLock).
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    public record Mismatch(UUID accountId, long stored, long computed) {}

    public record Report(List<Mismatch> mismatches, boolean ledgerBalanced) {
        public int mismatchedAccounts() {
            return mismatches.size();
        }
    }

    private final JdbcClient jdbc;
    private final AtomicInteger mismatchGauge = new AtomicInteger();

    public ReconciliationJob(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("ledger.reconciliation.mismatches", mismatchGauge);
    }

    @Scheduled(cron = "${wallet.reconciliation.cron:0 0 2 * * *}")
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Report run() {
        List<Mismatch> mismatches = jdbc.sql("""
                        SELECT a.id,
                               a.balance AS stored,
                               COALESCE(SUM(CASE WHEN e.direction = a.normal_side THEN e.amount ELSE -e.amount END), 0) AS computed
                          FROM accounts a
                          LEFT JOIN ledger_entries e ON e.account_id = a.id
                         WHERE a.balance_tracked
                         GROUP BY a.id
                        HAVING a.balance <> COALESCE(SUM(CASE WHEN e.direction = a.normal_side THEN e.amount ELSE -e.amount END), 0)
                        """)
                .query((rs, i) -> new Mismatch(rs.getObject("id", UUID.class), rs.getLong("stored"), rs.getLong("computed")))
                .list();

        boolean balanced = jdbc.sql("""
                        SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
                             = COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
                          FROM ledger_entries
                        """)
                .query(Boolean.class).single();

        mismatchGauge.set(mismatches.size() + (balanced ? 0 : 1));
        mismatches.forEach(m -> log.error("RECON MISMATCH account={} stored={} computed={}", m.accountId(), m.stored(), m.computed()));
        if (!balanced) log.error("RECON FAILURE: total debits != total credits");
        else if (mismatches.isEmpty()) log.info("Reconciliation OK");

        return new Report(mismatches, balanced);
    }
}
