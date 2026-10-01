package com.example.wallet.outbox;

import com.example.wallet.config.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Relays outbox rows to a Redis pub/sub channel. Delivery is AT LEAST ONCE: if Redis fails mid-batch the
 * transaction rolls back and the batch is retried. Consumers must tolerate duplicates and out-of-order
 * events, which is why every payload carries the account's monotonically increasing `version`.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repo;
    private final StringRedisTemplate redis;
    private final WalletProperties props;

    public OutboxPublisher(OutboxRepository repo, StringRedisTemplate redis, WalletProperties props) {
        this.repo = repo;
        this.redis = redis;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxRepository.Row> rows = repo.lockBatch(props.outbox().batchSize());
        if (rows.isEmpty()) return;
        for (var row : rows) {
            redis.convertAndSend(props.outbox().channel(), row.payload());
        }
        repo.markPublished(rows.stream().map(OutboxRepository.Row::id).toList());
    }

    /** Housekeeping so the outbox does not grow forever. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        int n = repo.deletePublishedOlderThanDays(7);
        if (n > 0) log.info("Purged {} published outbox rows", n);
    }
}
