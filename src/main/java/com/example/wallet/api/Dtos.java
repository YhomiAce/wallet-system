package com.example.wallet.api;

import com.example.wallet.common.Money;
import com.example.wallet.domain.Account;
import com.example.wallet.ledger.LedgerQueries;
import com.example.wallet.transfer.TransactionRecord;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes. Money in responses is always a decimal STRING in naira. */
public final class Dtos {
    private Dtos() {}

    public record CreateWalletRequest(@NotBlank @Size(max = 64) String customerId) {}

    public record WalletResponse(UUID id, String accountNumber, String customerId, String currency,
                                 String status, String balance, long version) {
        public static WalletResponse from(Account a, String balance) {
            return new WalletResponse(a.id(), a.accountNumber(), a.ownerId(), a.currency(), a.status(), balance, a.version());
        }
    }

    public record QuoteRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

    public record FeeQuoteResponse(String amount, String stampDuty, String fee, String vat, String totalDebit) {}

    public record TransferRequest(
            @NotBlank @Pattern(regexp = "\\d{10}") String sourceAccountNumber,
            @NotBlank @Pattern(regexp = "\\d{10}") String destinationAccountNumber,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 140) String narration) {}

    public record DepositRequest(
            @NotBlank @Pattern(regexp = "\\d{10}") String destinationAccountNumber,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 140) String narration) {}

    public record ReversalRequest(@Size(max = 140) String reason) {}

    public record TransactionResponse(UUID id, String reference, String type, String status, String amount,
                                      String stampDuty, String fee, String vat, String totalDebit,
                                      String narration, Instant createdAt, boolean replayed) {
        public static TransactionResponse from(TransactionRecord t, boolean replayed) {
            return new TransactionResponse(t.id(), t.reference(), t.type(), t.status(),
                    Money.format(t.amount()), Money.format(t.stampDuty()), Money.format(t.fee()),
                    Money.format(t.vat()), Money.format(t.totalDebit()), t.narration(), t.createdAt(), replayed);
        }
    }

    public record EntryResponse(long id, String direction, String amount, String balanceAfter, String lineType,
                                String reference, String journalType, String description, Instant createdAt) {
        public static EntryResponse from(LedgerQueries.EntryView e) {
            return new EntryResponse(e.id(), e.direction().name(), Money.format(e.amount()),
                    e.balanceAfter() == null ? null : Money.format(e.balanceAfter()),
                    e.lineType(), e.reference(), e.journalType(), e.description(), e.createdAt());
        }
    }

    public record EntriesPage(List<EntryResponse> items, Long nextCursor) {}
}
