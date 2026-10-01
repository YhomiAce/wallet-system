# Wallet Service (Spring Boot 3 · Java 21 · PostgreSQL · Redis · Flyway)

A double-entry, ledger-first wallet. **The ledger is the source of truth; `accounts.balance` is a cached
projection** updated in the same DB transaction, with Redis in front for instant reads.

## Run

```bash
docker compose up -d          # PostgreSQL 16 + Redis 7
mvn spring-boot:run           # Flyway migrates on startup
mvn verify                    # integration tests use Testcontainers (Docker required)
```

## Try it

```bash
H='Content-Type: application/json'

# 1. two wallets (note each "id" and "accountNumber")
curl -s -X POST localhost:8080/api/v1/wallets -H "$H" -d '{"customerId":"cust-1"}'
curl -s -X POST localhost:8080/api/v1/wallets -H "$H" -d '{"customerId":"cust-2"}'

# 2. fund wallet 1 (provider reference = idempotency key)
curl -s -X POST localhost:8080/api/v1/deposits -H "$H" -H 'Idempotency-Key: dep-0001-abcd' \
  -d '{"destinationAccountNumber":"1000000001","amount":100000.00,"narration":"Top up"}'

# 3. quote, then transfer N10,000  ->  debited N10,060.75
curl -s -X POST localhost:8080/api/v1/transfers/quote -H "$H" -d '{"amount":10000.00}'
curl -s -X POST localhost:8080/api/v1/transfers -H "$H" -H 'Idempotency-Key: trf-0001-abcd' \
  -d '{"sourceAccountNumber":"1000000001","destinationAccountNumber":"1000000002","amount":10000.00,"narration":"Rent"}'

# 4. retry the SAME request: 200 + Idempotent-Replayed: true, no second debit

# 5. balance (Redis), statement (keyset-paged), live stream (SSE), reversal
curl -s localhost:8080/api/v1/wallets/<id>/balance
curl -s "localhost:8080/api/v1/wallets/<id>/entries?limit=20"
curl -N localhost:8080/api/v1/wallets/<id>/balance/stream
curl -s -X POST localhost:8080/api/v1/transactions/<txId>/reversal -H "$H" -d '{"reason":"dispute"}'
```

## The journal for a N10,000 transfer

| Account | Debit | Credit |
|---|---:|---:|
| Sender wallet (liability) | 10,060.75 | |
| Recipient wallet (liability) | | 10,000.00 |
| Stamp duty payable (liability) | | 50.00 |
| Fee income (revenue) | | 10.00 |
| VAT payable (liability) | | 0.75 |

## Request flow (transfer)

```
POST /transfers (Idempotency-Key)
 └─ BEGIN
     ├─ INSERT transactions ... ON CONFLICT DO NOTHING      -> idempotency claim (duplicates block, then replay)
     ├─ SELECT accounts ... ORDER BY id FOR UPDATE          -> lock sender+recipient, deadlock-free
     ├─ check funds, INSERT journal + ledger_entries (with balance_after)
     ├─ UPDATE accounts SET balance, version+1
     ├─ INSERT outbox_events                                -> same txn, so no lost events
     └─ COMMIT   (DB trigger verifies debits == credits here)
 └─ afterCommit: Redis SET-if-newer (Lua, version-guarded)
Outbox poller -> Redis pub/sub -> every instance -> SSE clients
```

## Design decisions

| Concern | Decision |
|---|---|
| Money type | `BIGINT` kobo everywhere; naira strings only at the API edge; >2 decimals rejected |
| Instant balance | cached column on `accounts` + Redis cache-aside; DB is authoritative |
| Cache staleness | write only **after commit**; Lua script refuses older versions; TTL heals missed writes; Redis outage falls back to DB |
| Double entry | one journal, N entries; DB deferred trigger enforces debits = credits at COMMIT |
| Immutability | triggers block UPDATE/DELETE/TRUNCATE on ledger tables; fixes are reversing journals |
| Idempotency | unique `(source_account, key)` + request hash; same key + different body = 409 |
| Concurrency | pessimistic row locks in `ORDER BY id`; `CHECK (balance >= 0)` as backstop |
| Hot accounts | fee/VAT/stamp/settlement accounts are `balance_tracked=false`: no lock, no contention; balance derived from ledger |
| Live updates | transactional outbox -> Redis pub/sub -> SSE; payload has `version` so clients drop stale/duplicate events |
| Reconciliation | nightly job on a REPEATABLE READ snapshot; compares stored vs recomputed balances + global debit/credit equality; exposes a metric |
| Statements | keyset pagination on `ledger_entries(account_id, id DESC)` |
| Errors | RFC 7807 problem responses with stable `code` (e.g. `INSUFFICIENT_FUNDS`) |

## Easy-to-miss aspects (covered here)

Frozen/closed accounts reject postings · per-transaction limit · fee quote before confirming ·
reversal that is itself a journal and can happen only once (`UNIQUE reversal_of`) · outbox purge ·
graceful shutdown · Hikari fail-fast timeouts · Redis timeout 200 ms · concurrency + idempotency + immutability tests.

## Deliberately NOT included (do these before production)

- **AuthN/AuthZ**: verify the caller owns the source wallet; protect `/deposits` (service auth + provider signature).
- **External bank transfers** (NIP etc.): needs a `PENDING` state, debit to a settlement/suspense account, provider
  requery on timeout, and auto-reversal on failure. Never assume a timeout means failure.
- **Limits & KYC tiers**: daily/cumulative limits, velocity checks, fraud rules, AML screening.
- **Rate limiting**, request signing, device binding, PIN/OTP step-up.
- **DB hardening**: app DB user without UPDATE/DELETE on ledger tables (migrations run as a different role),
  PITR backups, read replica for statements, monthly partitioning/archival of `ledger_entries`.
- **Distributed lock** for scheduled jobs (ShedLock) when running several instances.
- **Remitting** stamp duty / VAT payables and reconciling against provider settlement files.
- **Multi-currency**, balance snapshots/checkpoints for very old accounts, audit log of admin actions.
- Real NUBAN account numbers (check digit); this example uses a plain sequence.
- Fee/stamp-duty values in `application.yml` are illustrative: confirm current regulatory rules.
