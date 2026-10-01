package com.example.wallet.api;

import com.example.wallet.api.Dtos.DepositRequest;
import com.example.wallet.api.Dtos.FeeQuoteResponse;
import com.example.wallet.api.Dtos.QuoteRequest;
import com.example.wallet.api.Dtos.ReversalRequest;
import com.example.wallet.api.Dtos.TransactionResponse;
import com.example.wallet.api.Dtos.TransferRequest;
import com.example.wallet.common.Money;
import com.example.wallet.common.WalletException;
import com.example.wallet.transfer.FeeCalculator.Fees;
import com.example.wallet.transfer.TransferService;
import com.example.wallet.transfer.TransferService.DepositCommand;
import com.example.wallet.transfer.TransferService.TransferCommand;
import com.example.wallet.transfer.TransferService.TxResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /** Show the customer exactly what they will be charged BEFORE they confirm. */
    @PostMapping("/transfers/quote")
    public FeeQuoteResponse quote(@Valid @RequestBody QuoteRequest req) {
        long amount = Money.toKobo(req.amount());
        Fees f = transfers.quote(amount);
        return new FeeQuoteResponse(Money.format(amount), Money.format(f.stampDuty()), Money.format(f.fee()),
                Money.format(f.vat()), Money.format(amount + f.total()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transfer(@RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody TransferRequest req) {
        TxResult r = transfers.transfer(new TransferCommand(requireKey(key), req.sourceAccountNumber(),
                req.destinationAccountNumber(), Money.toKobo(req.amount()), req.narration()));
        return respond(r);
    }

    /** Internal/webhook-facing: must be protected (service-to-service auth + provider signature check). */
    @PostMapping("/deposits")
    public ResponseEntity<TransactionResponse> deposit(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody DepositRequest req) {
        TxResult r = transfers.deposit(new DepositCommand(requireKey(key), req.destinationAccountNumber(),
                Money.toKobo(req.amount()), req.narration()));
        return respond(r);
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(transfers.get(id), false);
    }

    @PostMapping("/transactions/{id}/reversal")
    public TransactionResponse reverse(@PathVariable UUID id, @RequestBody(required = false) ReversalRequest req) {
        TxResult r = transfers.reverse(id, req == null ? null : req.reason());
        return TransactionResponse.from(r.tx(), r.replayed());
    }

    private static ResponseEntity<TransactionResponse> respond(TxResult r) {
        var body = TransactionResponse.from(r.tx(), r.replayed());
        return r.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body)
                : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw WalletException.invalid("Idempotency-Key must be 8-64 chars of [A-Za-z0-9_-]");
        }
        return key;
    }
}
