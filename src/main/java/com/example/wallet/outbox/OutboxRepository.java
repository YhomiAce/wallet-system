package com.example.wallet.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    public record Row(long id, String payload) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO outbox_events (aggregate_id, event_type, payload) "
                        + "VALUES (:agg, :type, CAST(:payload AS jsonb))")
                .param("agg", aggregateId).param("type", eventType).param("payload", payloadJson).update();
    }

    /** SKIP LOCKED lets several app instances poll concurrently without blocking or double-claiming. */
    public List<Row> lockBatch(int limit) {
        return jdbc.sql("SELECT id, payload::text AS payload FROM outbox_events "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED")
                .param("n", limit)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("payload")))
                .list();
    }

    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) return;
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids).update();
    }

    public int deletePublishedOlderThanDays(int days) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at < now() - make_interval(days => :d)")
                .param("d", days).update();
    }
}
