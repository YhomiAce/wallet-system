package com.example.wallet.domain;

public enum Direction {
    DEBIT, CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
