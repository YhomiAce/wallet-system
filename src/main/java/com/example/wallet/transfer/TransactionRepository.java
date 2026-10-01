package com.example.wallet.transfer;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private static final String COLUMNS = "id, type, idempotency_key, request_hash, source_account_id, "
            + "destination_account_id, amount, stamp_duty, fee, vat, total_debit, status, journal_id, "
            + "reference, narration, created_at";

    private static final RowMapper<TransactionRecord> MAPPER = (rs, n) -> new TransactionRecord(
            rs.getObject("id", UUID.class),
            rs.getString("type"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("source_account_id", UUID.class),
            rs.getObject("destination_account_id", UUID.class),
            rs.getLong("amount"),
            rs.getLong("stamp_duty"),
            rs.getLong("fee"),
            rs.getLong("vat"),
            rs.getLong("total_debit"),
            rs.getString("status"),
            rs.getObject("journal_id", UUID.class),
            rs.getString("reference"),
            rs.getString("narration"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key. Returns 0 if (source, key) already exists. A concurrent duplicate request
     * BLOCKS on the unique index until the first one commits or rolls back, then gets 0 (or inserts, if the
     * first one failed) - so two simultaneous retries can never both move money.
     */
    public int insertIfAbsent(UUID id, String type, String key, String hash, UUID source, UUID destination,
                              long amount, long stampDuty, long fee, long vat, long totalDebit,
                              String reference, String narration) {
        return jdbc.sql("""
                        INSERT INTO transactions (id, type, idempotency_key, request_hash, source_account_id,
                            destination_account_id, amount, stamp_duty, fee, vat, total_debit, status, reference, narration)
                        VALUES (:id, :type, :key, :hash, :src, :dst, :amount, :stamp, :fee, :vat, :total,
                            'PENDING', :ref, :narration)
                        ON CONFLICT (source_account_id, idempotency_key) DO NOTHING
                        """)
                .param("id", id).param("type", type).param("key", key).param("hash", hash)
                .param("src", source).param("dst", destination).param("amount", amount)
                .param("stamp", stampDuty).param("fee", fee).param("vat", vat).param("total", totalDebit)
                .param("ref", reference).param("narration", narration)
                .update();
    }

    public Optional<TransactionRecord> findByKey(UUID sourceAccountId, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE source_account_id = :s AND idempotency_key = :k")
                .param("s", sourceAccountId).param("k", key).query(MAPPER).optional();
    }

    public Optional<TransactionRecord> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<TransactionRecord> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    public void markCompleted(UUID id, UUID journalId) {
        jdbc.sql("UPDATE transactions SET status = 'COMPLETED', journal_id = :j, updated_at = now() WHERE id = :id")
                .param("j", journalId).param("id", id).update();
    }

    public void markReversed(UUID id) {
        jdbc.sql("UPDATE transactions SET status = 'REVERSED', updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }
}
