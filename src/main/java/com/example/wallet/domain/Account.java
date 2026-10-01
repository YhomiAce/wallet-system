package com.example.wallet.domain;

import java.util.UUID;

public record Account(
        UUID id,
        String kind,
        String type,
        Direction normalSide,
        String currency,
        long balance,
        boolean balanceTracked,
        boolean allowNegative,
        String status,
        long version,
        String accountNumber,
        String ownerId) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
