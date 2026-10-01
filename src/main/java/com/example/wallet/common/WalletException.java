package com.example.wallet.common;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Business exception with a stable machine-readable code for mobile/web clients. */
public class WalletException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private WalletException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static WalletException insufficientFunds() {
        return new WalletException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds");
    }
    public static WalletException accountNotFound(Object ref) {
        return new WalletException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ref);
    }
    public static WalletException accountNotActive(UUID id) {
        return new WalletException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_ACTIVE", "Account is not active: " + id);
    }
    public static WalletException transactionNotFound(UUID id) {
        return new WalletException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found: " + id);
    }
    public static WalletException idempotencyConflict() {
        return new WalletException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request");
    }
    public static WalletException invalid(String message) {
        return new WalletException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
    public static WalletException limitExceeded(String message) {
        return new WalletException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", message);
    }
    public static WalletException invalidState(String message) {
        return new WalletException(HttpStatus.CONFLICT, "INVALID_STATE", message);
    }
    public static WalletException conflict(String message) {
        return new WalletException(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
