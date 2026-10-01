package com.example.wallet.ledger;

import com.example.wallet.account.AccountRepository;
import com.example.wallet.cache.BalanceCache;
import com.example.wallet.common.Money;
import com.example.wallet.common.WalletException;
import com.example.wallet.domain.Account;
import com.example.wallet.domain.Direction;
import com.example.wallet.domain.PostingLine;
import com.example.wallet.outbox.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The only place that moves money. Posts one balanced journal atomically:
 *   lock tracked accounts (ordered) -> validate -> insert journal + entries -> update cached balances
 *   -> write outbox events (same txn) -> refresh Redis AFTER commit.
 *
 * It must be called inside an existing transaction (MANDATORY) so idempotency bookkeeping and the
 * money movement commit or roll back together.
 */
@Service
public class LedgerService {

    public record PostingResult(UUID journalId, Map<UUID, Long> newBalances) {}

    private record Row(PostingLine line, Long balanceAfter) {}

    private final AccountRepository accounts;
    private final JdbcClient jdbc;
    private final BalanceCache cache;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public LedgerService(AccountRepository accounts, JdbcClient jdbc, BalanceCache cache,
                         OutboxRepository outbox, ObjectMapper mapper) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.cache = cache;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PostingResult post(String journalType, String reference, String description,
                              UUID reversalOf, List<PostingLine> lines) {
        requireBalanced(lines);

        // 1. Load accounts. Tracked ones are locked in id order (deadlock-free); untracked are not locked at all.
        Set<UUID> ids = new TreeSet<>();
        lines.forEach(l -> ids.add(l.accountId()));
        Map<UUID, Account> byId = new HashMap<>();
        accounts.lockTracked(ids).forEach(a -> byId.put(a.id(), a));
        accounts.findUntracked(ids).forEach(a -> byId.put(a.id(), a));

        String currency = null;
        for (UUID id : ids) {
            Account a = byId.get(id);
            if (a == null) throw WalletException.accountNotFound(id);
            if (!a.isActive()) throw WalletException.accountNotActive(id);
            if (currency == null) currency = a.currency();
            else if (!currency.equals(a.currency())) throw WalletException.invalid("Cross-currency postings are not supported");
        }

        // 2. Compute running balances per entry (balance_after) and the final balance per tracked account.
        Map<UUID, Long> running = new HashMap<>();
        byId.values().stream().filter(Account::balanceTracked).forEach(a -> running.put(a.id(), a.balance()));

        List<Row> rows = new ArrayList<>();
        for (PostingLine l : lines) {
            Account a = byId.get(l.accountId());
            Long after = null;
            if (a.balanceTracked()) {
                long delta = l.direction() == a.normalSide() ? l.amount() : -l.amount();
                after = Math.addExact(running.get(a.id()), delta);
                running.put(a.id(), after);
            }
            rows.add(new Row(l, after));
        }

        // 3. Business rule (the DB CHECK constraint is the backstop): no overdrafts.
        for (Account a : byId.values()) {
            if (a.balanceTracked() && !a.allowNegative() && running.get(a.id()) < 0) {
                throw WalletException.insufficientFunds();
            }
        }

        // 4. Persist journal + entries.
        UUID journalId = UUID.randomUUID();
        jdbc.sql("INSERT INTO journals (id, type, reference, description, reversal_of) "
                        + "VALUES (:id, :type, :ref, :desc, :rev)")
                .param("id", journalId).param("type", journalType).param("ref", reference)
                .param("desc", description).param("rev", reversalOf).update();

        for (Row r : rows) {
            jdbc.sql("INSERT INTO ledger_entries (journal_id, account_id, direction, amount, balance_after, line_type) "
                            + "VALUES (:j, :acc, :dir, :amt, :after, :lt)")
                    .param("j", journalId)
                    .param("acc", r.line().accountId())
                    .param("dir", r.line().direction().name())
                    .param("amt", r.line().amount())
                    .param("after", r.balanceAfter(), Types.BIGINT)
                    .param("lt", r.line().lineType().name())
                    .update();
        }

        // 5. Update the cached balance column + queue outbox events (same transaction).
        Map<UUID, Long> newBalances = new HashMap<>();
        List<BalanceCache.Snapshot> snapshots = new ArrayList<>();
        for (UUID id : ids) {
            Account a = byId.get(id);
            if (!a.balanceTracked()) continue;
            long nb = running.get(id);
            newBalances.put(id, nb);
            if (nb == a.balance()) continue;

            accounts.updateBalance(id, nb);
            long newVersion = a.version() + 1;
            snapshots.add(new BalanceCache.Snapshot(id, newVersion, nb, a.currency()));
            outbox.insert(id, "BALANCE_UPDATED", toJson(Map.of(
                    "eventType", "BALANCE_UPDATED",
                    "accountId", id.toString(),
                    "balanceKobo", nb,
                    "balance", Money.format(nb),
                    "version", newVersion,
                    "currency", a.currency(),
                    "reference", reference,
                    "occurredAt", Instant.now().toString())));
        }

        // 6. Only after the DB commit succeeds do we touch Redis. A rolled-back transfer never pollutes the cache.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshots.forEach(cache::put);
            }
        });

        return new PostingResult(journalId, newBalances);
    }

    private static void requireBalanced(List<PostingLine> lines) {
        if (lines.size() < 2) throw WalletException.invalid("A journal needs at least two lines");
        long debits = 0, credits = 0;
        for (PostingLine l : lines) {
            if (l.amount() <= 0) throw WalletException.invalid("Line amounts must be positive");
            if (l.direction() == Direction.DEBIT) debits = Math.addExact(debits, l.amount());
            else credits = Math.addExact(credits, l.amount());
        }
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced journal: debits=" + debits + " credits=" + credits);
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
