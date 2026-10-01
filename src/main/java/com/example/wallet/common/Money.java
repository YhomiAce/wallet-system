package com.example.wallet.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** All money inside the system is a long number of kobo. Naira only exists at the API edge. */
public final class Money {
    private Money() {}

    /** 10060.75 -> 1006075. Rejects more than 2 decimal places instead of silently rounding. */
    public static long toKobo(BigDecimal naira) {
        try {
            return naira.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw WalletException.invalid("Amount must have at most 2 decimal places");
        }
    }

    /** 1006075 -> "10060.75" (string so JS clients never touch floating point). */
    public static String format(long kobo) {
        return BigDecimal.valueOf(kobo, 2).toPlainString();
    }
}
