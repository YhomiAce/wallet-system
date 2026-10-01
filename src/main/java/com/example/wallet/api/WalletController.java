package com.example.wallet.api;

import com.example.wallet.account.WalletService;
import com.example.wallet.api.Dtos.CreateWalletRequest;
import com.example.wallet.api.Dtos.EntriesPage;
import com.example.wallet.api.Dtos.EntryResponse;
import com.example.wallet.api.Dtos.WalletResponse;
import com.example.wallet.cache.BalanceService;
import com.example.wallet.cache.BalanceService.BalanceView;
import com.example.wallet.domain.Account;
import com.example.wallet.ledger.LedgerQueries;
import com.example.wallet.outbox.BalanceEventBroadcaster;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * NOTE: authentication/authorization is intentionally omitted from this example. In production every
 * endpoint must verify that the authenticated customer OWNS the wallet being read or debited.
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService wallets;
    private final BalanceService balances;
    private final LedgerQueries ledgerQueries;
    private final BalanceEventBroadcaster broadcaster;

    public WalletController(WalletService wallets, BalanceService balances, LedgerQueries ledgerQueries,
                            BalanceEventBroadcaster broadcaster) {
        this.wallets = wallets;
        this.balances = balances;
        this.ledgerQueries = ledgerQueries;
        this.broadcaster = broadcaster;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@Valid @RequestBody CreateWalletRequest req) {
        Account a = wallets.create(req.customerId());
        return ResponseEntity.created(URI.create("/api/v1/wallets/" + a.id()))
                .body(WalletResponse.from(a, "0.00"));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id) {
        Account a = wallets.get(id);
        BalanceView b = balances.get(id);
        return WalletResponse.from(a, b.balance());
    }

    /** The hot path for mobile/web: one Redis GET, falling back to one primary-key lookup. */
    @GetMapping("/{id}/balance")
    public BalanceView balance(@PathVariable UUID id) {
        return balances.get(id);
    }

    /** Statement. Cursor = id of the last entry you received; omit for the newest page. */
    @GetMapping("/{id}/entries")
    public EntriesPage entries(@PathVariable UUID id,
                               @RequestParam(required = false) Long before,
                               @RequestParam(defaultValue = "50") int limit) {
        wallets.get(id);   // 404 if not a wallet
        int size = Math.max(1, Math.min(limit, 100));
        List<EntryResponse> items = ledgerQueries.findEntries(id, before, size).stream()
                .map(EntryResponse::from).toList();
        Long next = items.size() == size ? items.get(items.size() - 1).id() : null;
        return new EntriesPage(items, next);
    }

    /**
     * Server-Sent Events: pushes a `balance` event whenever the wallet changes. Clients should ignore any
     * event whose `version` is not greater than the last one they applied (duplicates/out-of-order are possible).
     */
    @GetMapping(path = "/{id}/balance/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        BalanceView current = balances.get(id);   // 404 early if unknown
        SseEmitter emitter = broadcaster.subscribe(id);
        try {
            emitter.send(SseEmitter.event().name("balance").data(current, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
