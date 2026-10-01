package com.example.wallet.cache;

import com.example.wallet.account.AccountRepository;
import com.example.wallet.common.Money;
import com.example.wallet.common.WalletException;
import com.example.wallet.domain.Account;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** Read path for balances: Redis first (cache-aside), PostgreSQL on a miss. */
@Service
public class BalanceService {

    public record BalanceView(UUID accountId, String balance, long balanceKobo, String currency,
                              long version, Instant asOf) {}

    private final BalanceCache cache;
    private final AccountRepository accounts;

    public BalanceService(BalanceCache cache, AccountRepository accounts) {
        this.cache = cache;
        this.accounts = accounts;
    }

    public BalanceView get(UUID accountId) {
        var hit = cache.get(accountId);
        if (hit.isPresent()) return view(hit.get());

        // Only tracked accounts (customer wallets) are exposed. Misses for unknown ids are NOT cached.
        Account a = accounts.findById(accountId)
                .filter(Account::balanceTracked)
                .orElseThrow(() -> WalletException.accountNotFound(accountId));

        var snapshot = new BalanceCache.Snapshot(a.id(), a.version(), a.balance(), a.currency());
        cache.put(snapshot);   // version-guarded: cannot overwrite a newer value written by a commit
        return view(snapshot);
    }

    private static BalanceView view(BalanceCache.Snapshot s) {
        return new BalanceView(s.accountId(), Money.format(s.balance()), s.balance(), s.currency(),
                s.version(), Instant.now());
    }
}
