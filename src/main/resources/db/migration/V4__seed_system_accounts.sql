-- Fixed IDs so application code can reference them as constants.
INSERT INTO accounts (id, code, kind, type, normal_side, balance_tracked, allow_negative) VALUES
 ('00000000-0000-0000-0000-000000000001', 'SYS_SETTLEMENT',         'SYSTEM', 'ASSET',     'DEBIT',  false, true),
 ('00000000-0000-0000-0000-000000000002', 'SYS_FEE_INCOME',         'SYSTEM', 'REVENUE',   'CREDIT', false, false),
 ('00000000-0000-0000-0000-000000000003', 'SYS_STAMP_DUTY_PAYABLE', 'SYSTEM', 'LIABILITY', 'CREDIT', false, false),
 ('00000000-0000-0000-0000-000000000004', 'SYS_VAT_PAYABLE',        'SYSTEM', 'LIABILITY', 'CREDIT', false, false);
