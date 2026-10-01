package com.example.wallet.account;

import com.example.wallet.domain.Account;
import com.example.wallet.domain.Direction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, kind, type, normal_side, currency, balance, balance_tracked, "
            + "allow_negative, status, version, account_number, owner_id";

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("kind"),
            rs.getString("type"),
            Direction.valueOf(rs.getString("normal_side")),
            rs.getString("currency"),
            rs.getLong("balance"),
            rs.getBoolean("balance_tracked"),
            rs.getBoolean("allow_negative"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getString("account_number"),
            rs.getString("owner_id"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<Account> findWalletByNumber(String accountNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE kind = 'WALLET' AND account_number = :n")
                .param("n", accountNumber).query(MAPPER).optional();
    }

    /**
     * Locks tracked accounts in a deterministic order (ORDER BY id). Every transaction takes locks in the
     * same order, so two opposite transfers (A->B and B->A) can never deadlock.
     */
    public List<Account> lockTracked(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) AND balance_tracked "
                        + "ORDER BY id FOR UPDATE")
                .param("ids", ids).query(MAPPER).list();
    }

    /** Untracked (hot system) accounts are read WITHOUT a lock: they only ever receive inserts into the ledger. */
    public List<Account> findUntracked(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) AND NOT balance_tracked")
                .param("ids", ids).query(MAPPER).list();
    }

    public void updateBalance(UUID id, long newBalance) {
        jdbc.sql("UPDATE accounts SET balance = :b, version = version + 1, updated_at = now() WHERE id = :id")
                .param("b", newBalance).param("id", id).update();
    }

    public Account createWallet(String ownerId, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO accounts (id, account_number, owner_id, kind, type, normal_side, currency, balance_tracked)
                VALUES (:id, nextval('account_number_seq')::text, :owner, 'WALLET', 'LIABILITY', 'CREDIT', :ccy, true)
                """)
                .param("id", id).param("owner", ownerId).param("ccy", currency).update();
        return findById(id).orElseThrow();
    }
}
