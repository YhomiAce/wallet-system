package com.example.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(Cache cache, Fees fees, Limits limits, Outbox outbox, Reconciliation reconciliation) {

    public record Cache(Duration ttl, String keyPrefix) {}

    public record Fees(long stampDutyKobo, long stampDutyThresholdKobo, long flatFeeKobo, BigDecimal vatRate) {}

    public record Limits(long maxTransferKobo) {}

    public record Outbox(long pollIntervalMs, int batchSize, String channel) {}

    public record Reconciliation(String cron) {}
}
