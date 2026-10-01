package com.example.wallet.transfer;

import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
        UUID id,
        String type,
        String idempotencyKey,
        String requestHash,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amount,
        long stampDuty,
        long fee,
        long vat,
        long totalDebit,
        String status,
        UUID journalId,
        String reference,
        String narration,
        Instant createdAt) {}
