package com.example.wallet.domain;

public enum LineType {
    TOTAL,        // what the sender is debited (principal + fees)
    PRINCIPAL,    // what the recipient receives
    STAMP_DUTY,
    FEE,
    VAT
}
