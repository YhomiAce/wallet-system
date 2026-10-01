package com.example.wallet.transfer;

import com.example.wallet.account.AccountRepository;
import com.example.wallet.common.WalletException;
import com.example.wallet.config.WalletProperties;
import com.example.wallet.domain.Account;
import com.example.wallet.domain.Direction;
import com.example.wallet.domain.LineType;
import com.example.wallet.domain.PostingLine;
import com.example.wallet.domain.SystemAccounts;
import com.example.wallet.ledger.LedgerQueries;
import com.example.wallet.ledger.LedgerService;
import com.example.wallet.transfer.FeeCalculator.Fees;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a money movement as ONE database transaction:
 *   claim idempotency key -> compute fees -> post balanced journal -> mark completed.
 * Business rejections (e.g. insufficient funds) roll everything back, including the idempotency claim,
 * so a client may retry the same key once the problem is fixed (e.g. after funding the wallet).
 */
@Service
public class TransferService {

    public record TransferCommand(String idempotencyKey, String sourceAccountNumber,
                                  String destinationAccountNumber, long amountKobo, String narration) {}

    public record DepositCommand(String idempotencyKey, String destinationAccountNumber,
                                 long amountKobo, String narration) {}

    /** replayed = true when an earlier identical request is returned instead of executing again. */
    public record TxResult(TransactionRecord tx, boolean replayed) {}

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final LedgerQueries ledgerQueries;
    private final TransactionRepository txRepo;
    private final FeeCalculator feeCalculator;
    private final WalletProperties props;

    public TransferService(AccountRepository accounts, LedgerService ledger, LedgerQueries ledgerQueries,
                           TransactionRepository txRepo, FeeCalculator feeCalculator, WalletProperties props) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.ledgerQueries = ledgerQueries;
        this.txRepo = txRepo;
        this.feeCalculator = feeCalculator;
        this.props = props;
    }

    public Fees quote(long amountKobo) {
        validateAmount(amountKobo);
        return feeCalculator.calculate(amountKobo);
    }

    @Transactional
    public TxResult transfer(TransferCommand c) {
        validateAmount(c.amountKobo());
        Account src = wallet(c.sourceAccountNumber());
        Account dst = wallet(c.destinationAccountNumber());
        if (src.id().equals(dst.id())) throw WalletException.invalid("Cannot transfer to the same account");

        return execute("TRANSFER", src, dst, c.amountKobo(), feeCalculator.calculate(c.amountKobo()),
                c.idempotencyKey(), c.narration());
    }

    /**
     * Money arriving from outside (bank transfer / card funding confirmed by the provider).
     * The idempotency key should be the provider's unique reference, so webhook retries are harmless.
     * Why the settlement account is the "source"
     * Here the settlement account takes the role of the sender (src). Because it's an asset account with a DEBIT normal side,
     * being debited makes its balance go up. The system accounts are also set up so that:
     *
     * allow_negative = true: it can drop below zero in the ledger (for example if a withdrawal is processed before the matching deposit is recorded).
     * balance_tracked = false: it never gets a locked balance column update. Every deposit in the whole system touches it,
     * so locking it would make deposits wait in a single queue. Its balance can be calculated from the ledger when needed.
     */
    @Transactional
    public TxResult deposit(DepositCommand c) {
        validateAmount(c.amountKobo());
        Account dst = wallet(c.destinationAccountNumber());
        Account settlement = accounts.findById(SystemAccounts.SETTLEMENT).orElseThrow();
        return execute("DEPOSIT", settlement, dst, c.amountKobo(), Fees.NONE, c.idempotencyKey(), c.narration());
    }

    @Transactional
    public TxResult reverse(UUID transactionId, String reason) {
        TransactionRecord tx = txRepo.lockById(transactionId)       // serialises concurrent reversal attempts
                .orElseThrow(() -> WalletException.transactionNotFound(transactionId));
        if ("REVERSED".equals(tx.status())) return new TxResult(tx, true);
        if (!"COMPLETED".equals(tx.status())) throw WalletException.invalidState("Only completed transactions can be reversed");

        // A reversal is a NEW journal with every leg flipped. Nothing is ever edited or deleted.
        List<PostingLine> flipped = ledgerQueries.findJournalLines(tx.journalId()).stream()
                .map(l -> new PostingLine(l.accountId(), l.direction().opposite(), l.amount(), LineType.valueOf(l.lineType())))
                .toList();

        String description = "Reversal of " + tx.reference() + (reason == null || reason.isBlank() ? "" : ": " + reason);
        ledger.post("REVERSAL", newReference("RVS"), description, tx.journalId(), flipped);
        txRepo.markReversed(tx.id());
        return new TxResult(txRepo.findById(tx.id()).orElseThrow(), false);
    }

    @Transactional(readOnly = true)
    public TransactionRecord get(UUID id) {
        return txRepo.findById(id).orElseThrow(() -> WalletException.transactionNotFound(id));
    }

    // ---------------------------------------------------------------------------------------------

    private TxResult execute(String type, Account src, Account dst, long amount, Fees fees,
                             String idempotencyKey, String narration) {
        String hash = sha256(String.join("|", type, src.id().toString(), dst.id().toString(),
                Long.toString(amount), narration == null ? "" : narration));
        UUID txId = UUID.randomUUID();
        String reference = newReference("TRX");
        long total = amount + fees.total();

        int claimed = txRepo.insertIfAbsent(txId, type, idempotencyKey, hash, src.id(), dst.id(),
                amount, fees.stampDuty(), fees.fee(), fees.vat(), total, reference, narration);

        if (claimed == 0) {
            TransactionRecord existing = txRepo.findByKey(src.id(), idempotencyKey).orElseThrow();
            if (!existing.requestHash().equals(hash)) throw WalletException.idempotencyConflict();
            return new TxResult(existing, true);
        }

        // 1 debit (sender pays everything) and up to 4 credits, netting to zero:
        //   DR sender            10,060.75
        //     CR recipient       10,000.00
        //     CR stamp duty          50.00
        //     CR fee income          10.00
        //     CR VAT payable          0.75
        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(src.id(), Direction.DEBIT, total, LineType.TOTAL));
        lines.add(new PostingLine(dst.id(), Direction.CREDIT, amount, LineType.PRINCIPAL));
        if (fees.stampDuty() > 0) lines.add(new PostingLine(SystemAccounts.STAMP_DUTY_PAYABLE, Direction.CREDIT, fees.stampDuty(), LineType.STAMP_DUTY));
        if (fees.fee() > 0)       lines.add(new PostingLine(SystemAccounts.FEE_INCOME, Direction.CREDIT, fees.fee(), LineType.FEE));
        if (fees.vat() > 0)       lines.add(new PostingLine(SystemAccounts.VAT_PAYABLE, Direction.CREDIT, fees.vat(), LineType.VAT));

        var result = ledger.post(type, reference, narration, null, lines);
        txRepo.markCompleted(txId, result.journalId());
        return new TxResult(txRepo.findById(txId).orElseThrow(), false);
    }

    private Account wallet(String accountNumber) {
        return accounts.findWalletByNumber(accountNumber)
                .orElseThrow(() -> WalletException.accountNotFound(accountNumber));
    }

    private void validateAmount(long amountKobo) {
        if (amountKobo <= 0) throw WalletException.invalid("Amount must be greater than zero");
        if (amountKobo > props.limits().maxTransferKobo()) {
            throw WalletException.limitExceeded("Amount exceeds the per-transaction limit");
        }
    }

    private static String newReference(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
