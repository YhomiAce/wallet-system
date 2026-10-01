package com.example.wallet.ledger;

import com.example.wallet.domain.Direction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only ledger queries: statements and journal lookups. */
@Repository
public class LedgerQueries {

    public record EntryView(long id, Direction direction, long amount, Long balanceAfter, String lineType,
                            String reference, String journalType, String description, Instant createdAt) {}

    public record JournalLine(UUID accountId, Direction direction, long amount, String lineType) {}

    private final JdbcClient jdbc;

    public LedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keyset pagination: stable and O(log n) however deep the customer scrolls (OFFSET would degrade). */
    public List<EntryView> findEntries(UUID accountId, Long before, int limit) {
        long cursor = before == null ? Long.MAX_VALUE : before;
        return jdbc.sql("""
                        SELECT e.id, e.direction, e.amount, e.balance_after, e.line_type,
                               j.reference, j.type AS journal_type, j.description, e.created_at
                          FROM ledger_entries e
                          JOIN journals j ON j.id = e.journal_id
                         WHERE e.account_id = :acc AND e.id < :cursor
                         ORDER BY e.id DESC
                         LIMIT :limit
                        """)
                .param("acc", accountId).param("cursor", cursor).param("limit", limit)
                .query((rs, i) -> new EntryView(
                        rs.getLong("id"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount"),
                        rs.getObject("balance_after", Long.class),
                        rs.getString("line_type"),
                        rs.getString("reference"),
                        rs.getString("journal_type"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public List<JournalLine> findJournalLines(UUID journalId) {
        return jdbc.sql("SELECT account_id, direction, amount, line_type FROM ledger_entries "
                        + "WHERE journal_id = :j ORDER BY id")
                .param("j", journalId)
                .query((rs, i) -> new JournalLine(
                        rs.getObject("account_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount"),
                        rs.getString("line_type")))
                .list();
    }
}
