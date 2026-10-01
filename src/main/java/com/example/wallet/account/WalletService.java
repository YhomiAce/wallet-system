package com.example.wallet.account;

import com.example.wallet.common.WalletException;
import com.example.wallet.domain.Account;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private final AccountRepository accounts;

    public WalletService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account create(String customerId) {
        try {
            return accounts.createWallet(customerId, "NGN");
        } catch (DuplicateKeyException e) {
            throw WalletException.conflict("Customer already has an NGN wallet");
        }
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .filter(a -> "WALLET".equals(a.kind()))
                .orElseThrow(() -> WalletException.accountNotFound(id));
    }
}
