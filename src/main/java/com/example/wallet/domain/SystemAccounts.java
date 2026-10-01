package com.example.wallet.domain;

import java.util.UUID;

/** IDs seeded by V4__seed_system_accounts.sql. */
public final class SystemAccounts {
    private SystemAccounts() {}

    public static final UUID SETTLEMENT         = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID FEE_INCOME         = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID STAMP_DUTY_PAYABLE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID VAT_PAYABLE        = UUID.fromString("00000000-0000-0000-0000-000000000004");
}
