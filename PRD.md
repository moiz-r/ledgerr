# Project Ledgerr: Core Ledger Service (Stripe-Style Design Doc)

**Status:** Proposed
**Owner:** [Your Name]
**Reviewers:** Backend Eng, Platform Eng, Risk/Compliance (mock), Data Eng (mock)
**Last Updated:** 2026-01-12
**Doc Type:** Design Doc (Internal)

---

## 0. TL;DR

Ledgerr is an **immutable, double-entry ledger** that acts as the **source of truth** for all balances and money movement. It prioritizes **integrity, auditability, and determinism**. All changes are append-only, validated as **zero-sum**, and written atomically under strict transactional guarantees.

This design also adds two production-grade primitives that make it recruiter-impressive:

1. **Transactional Outbox** to reliably emit events without dual-write bugs.
2. **Reconciliation** workflows to detect/resolve mismatches between internal ledger and external processors/banks.

---

## 1. Problem

A fintech cannot safely manage money using mutable “balance” columns and CRUD updates because:

* **Race conditions** cause negative balances, double-spend, and inconsistent views.
* **No auditability**: `balance = balance + 50` does not prove what happened or why.
* **Compliance & forensics** require a complete historical record (append-only, traceable, replayable).
* **External dependencies** (processors/banks) settle later, and mismatches must be detected and resolved.

---

## 2. Goals

### 2.1 Functional Goals

* Provide a **general-purpose double-entry ledger** that supports:

  * Transfers with split entries (fees, taxes, revenue splits)
  * Multi-account postings per transaction (N entries)
  * Transaction lifecycle: **PENDING → POSTED → REVERSED / REJECTED**
  * Holds/authorizations (optional but strongly recommended for realism)
  * Multi-currency transactions with explicit FX modeling (phase 3+)

### 2.2 Non-Functional Goals

* **Correctness first**: strong integrity and auditability over availability.
* **Idempotent writes**: clients can safely retry.
* **High concurrency** with deterministic outcomes under contention.
* **Operationally debuggable**: structured metadata, correlation IDs, event emission.
* **Reconciliation-ready**: track and resolve external mismatches.

---

## 3. Non-Goals (Explicit)

* HFT / quant execution, market making, or trading strategies.
* Full payments orchestration (ACH, cards, RTP) — Ledgerr is the ledger, not the gateway.
* Real-time fraud/AML decisioning (can be integrated later via events).
* A full GL/financial reporting system (we produce ledgers and statements; reporting is downstream).

---

## 4. Key Concepts & Accounting Model

### 4.1 Invariants (“Must Never Break”)

1. **Zero-sum per transaction (within currency):**
   For every transaction and currency, sum(debits) == sum(credits).

2. **Immutability:**
   No ledger entry is updated or deleted. Corrections happen via **reversal** transactions.

3. **Precision:**
   Store amounts in **minor units** using `BIGINT` (or numeric with strict constraints). Never float.

4. **Posting lifecycle integrity:**
   Only **POSTED** entries affect posted balances. **PENDING** entries do not change posted balance.

5. **Idempotency:**
   Same `reference_id` must not create duplicate financial effects.

### Interview talking points

* “I treat the ledger as the source of truth and balances as derived views.”
* “All invariants are enforced at write time, ideally at the DB layer where possible.”

---

## 5. Ledger Lifecycle

### 5.1 Transaction Status States

* **PENDING**: recorded but not settled; does not affect posted balances.
* **POSTED**: committed financial event; updates balances.
* **REJECTED**: invalid or failed (insufficient funds, constraint violations).
* **REVERSED**: a posted transaction is corrected by a compensating transaction.

### 5.2 Holds / Authorization (Recommended)

Holds reserve funds without posting the final transfer.

* Example: card authorization, withdrawal reservation, escrow.
* Available balance must account for holds.

**Definitions**

* `posted_balance`: sum of posted ledger entries
* `held_amount`: sum of active holds
* `available_balance = posted_balance - held_amount`

### Interview talking points

* “Separating pending vs posted is how you model real payment settlement.”
* “Holds prevent accidental spending of funds before settlement.”

---

## 6. Data Model (PostgreSQL)

### 6.1 Tables

#### accounts

Represents a bucket of value.

```sql
CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  asset_class TEXT NOT NULL CHECK (asset_class IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
  currency TEXT NOT NULL, -- ISO 4217
  balance_posted BIGINT NOT NULL DEFAULT 0,  -- denormalized
  balance_pending BIGINT NOT NULL DEFAULT 0, -- optional if we model pending balances
  version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_currency ON accounts(currency);
```

#### transactions

Financial event wrapper.

```sql
CREATE TABLE transactions (
  id UUID PRIMARY KEY,
  reference_id TEXT UNIQUE NOT NULL,    -- idempotency key
  description TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  status TEXT NOT NULL CHECK (status IN ('PENDING','POSTED','REJECTED','REVERSED')),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  reversed_transaction_id UUID NULL REFERENCES transactions(id) -- optional link
);
```

#### ledger_entries

Atomic movements. Amount is always positive; direction indicates sign.

```sql
CREATE TABLE ledger_entries (
  id UUID PRIMARY KEY,
  transaction_id UUID NOT NULL REFERENCES transactions(id),
  account_id UUID NOT NULL REFERENCES accounts(id),
  amount BIGINT NOT NULL CHECK (amount > 0),
  direction TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  currency TEXT NOT NULL, -- denormalize for invariant checks & performance
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_entries_tx ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_acct ON ledger_entries(account_id, created_at);
CREATE INDEX idx_ledger_entries_currency ON ledger_entries(currency);
```

> Note: storing `currency` on ledger_entries prevents expensive joins during invariant checks and partitioning.

#### holds (recommended)

```sql
CREATE TABLE holds (
  id UUID PRIMARY KEY,
  reference_id TEXT UNIQUE NOT NULL,
  account_id UUID NOT NULL REFERENCES accounts(id),
  amount BIGINT NOT NULL CHECK (amount > 0),
  currency TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','RELEASED','CAPTURED','EXPIRED')),
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  capture_transaction_id UUID NULL REFERENCES transactions(id)
);

CREATE INDEX idx_holds_account_status ON holds(account_id, status);
```

---

## 7. Core Write Path: Create Transaction

### 7.1 API: POST /v1/transactions

**Request**

```json
{
  "reference_id": "txn_12345_abc",
  "description": "Payment to merchant with fee",
  "status": "POSTED",
  "entries": [
    {"account_id":"user_wallet_1","amount":1050,"direction":"DEBIT","currency":"USD"},
    {"account_id":"merchant_wallet_2","amount":1000,"direction":"CREDIT","currency":"USD"},
    {"account_id":"platform_revenue_3","amount":50,"direction":"CREDIT","currency":"USD"}
  ],
  "metadata": {"source":"mobile_app","ip":"1.2.3.4","actor":"user:123"}
}
```

### 7.2 Validation (must be deterministic)

1. **Idempotency**: `reference_id` unique.
2. **Zero-sum per currency**: for each currency bucket: Σ(debits) == Σ(credits)
3. **Currency consistency per entry**: entry currency must match account currency (unless FX transaction type).
4. **Sufficient funds**: for debited accounts:

   * `available_balance >= debit_amount` (posted minus holds), or depending on product rules.

### 7.3 Atomicity Guarantees

All of the below must happen in **one DB transaction**:

* Insert transaction row
* Insert ledger entries
* Update denormalized balances (if status POSTED)
* Write outbox event(s)

If any step fails, nothing is committed.

### Interview talking points

* “I prioritize correctness by making posting and event creation part of the same database transaction.”
* “Idempotency is enforced with a DB unique constraint, not just application logic.”

---

## 8. Concurrency Control

### 8.1 Default: Optimistic Locking for Accounts

* Read account rows involved in transaction
* Compute balance deltas
* Update with `WHERE id=? AND version=?`

On conflict:

* Retry a small number of times with jitter
* If still failing, return `409 Conflict` with a retriable error code

### 8.2 Hot Account Strategy

For highly contended system accounts (e.g., platform revenue):

* Option A: **pessimistic row lock** for that account only
* Option B: shard system accounts (e.g., revenue_00..revenue_63) and rotate
* Option C: aggregate revenue downstream (less accurate real-time)

### Interview talking points

* “Optimistic locking scales well for user accounts; hot accounts need special handling or sharding.”

---

## 9. Reads: Statements & Balances

### 9.1 GET /v1/accounts/{id}/balance

Returns:

* posted_balance
* held_amount
* available_balance
* pending_balance (optional)

### 9.2 GET /v1/accounts/{id}/statement?from=&to=

Returns ledger entries with:

* transaction metadata (description, reference_id)
* running balance (computed in query or service layer)

> For performance, statements query ledger_entries by account_id + date index and optionally use monthly partitioning.

---

## 10. Phase 4 (Recruiter-Grade): Outbox + Reconciliation

This is the “this person understands production fintech” phase.

---

# 10A. Transactional Outbox (Design + Implementation)

## 10A.1 Problem

If we publish events to Kafka/SQS **outside** the DB transaction, we can get:

* Event published but ledger rolled back (phantom events)
* Ledger committed but event never published (missed notifications)

## 10A.2 Solution: Outbox Table

```sql
CREATE TABLE ledger_events (
  id UUID PRIMARY KEY,
  event_key TEXT UNIQUE NOT NULL, -- idempotency key for event consumers
  aggregate_type TEXT NOT NULL,    -- 'TRANSACTION','ACCOUNT','HOLD'
  aggregate_id UUID NOT NULL,
  event_type TEXT NOT NULL,        -- 'TransactionPosted', 'TransactionRejected', etc.
  payload JSONB NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  published_at TIMESTAMP NULL
);

CREATE INDEX idx_ledger_events_unpublished ON ledger_events(published_at) WHERE published_at IS NULL;
```

## 10A.3 Write Flow

When posting a transaction inside the DB transaction:

* Insert ledger + update balances
* Insert `ledger_events` row with payload snapshot

## 10A.4 Publisher Job (Worker)

A background worker:

* polls `ledger_events WHERE published_at IS NULL ORDER BY created_at LIMIT N`
* publishes to broker
* marks `published_at = NOW()` on success

**Idempotency**

* `event_key` ensures no duplicates even if job retries

### Interview talking points

* “Outbox prevents dual-write bugs and guarantees eventual event delivery.”
* “Consumers must also be idempotent; event_key is the contract.”

---

# 10B. Reconciliation (Design + Implementation)

## 10B.1 Problem

External systems (card processor, ACH operator, crypto custodian) may disagree with our ledger due to:

* Partial failures
* Processor fees
* Chargebacks
* Timing/settlement differences
* Human/ops adjustments

We need a formal model to detect mismatches and drive resolution without silently mutating history.

## 10B.2 Schema

### external_transactions

Represents imported transactions from external providers.

```sql
CREATE TABLE external_transactions (
  id UUID PRIMARY KEY,
  provider TEXT NOT NULL,               -- 'stripe', 'adyen', 'bank_xyz'
  external_id TEXT NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  currency TEXT NOT NULL,
  amount BIGINT NOT NULL,
  type TEXT NOT NULL,                   -- 'settlement','fee','chargeback'
  raw JSONB NOT NULL,
  UNIQUE(provider, external_id)
);
```

### reconciliations

Tracks reconciliation results and resolution.

```sql
CREATE TABLE reconciliations (
  id UUID PRIMARY KEY,
  provider TEXT NOT NULL,
  external_id TEXT NOT NULL,
  internal_transaction_id UUID NULL REFERENCES transactions(id),
  status TEXT NOT NULL CHECK (status IN (
    'MATCHED','MISSING_INTERNAL','MISSING_EXTERNAL','AMOUNT_MISMATCH','CURRENCY_MISMATCH','RESOLVED'
  )),
  diff_amount BIGINT NOT NULL DEFAULT 0,
  notes TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMP NULL,
  UNIQUE(provider, external_id)
);
```

## 10B.3 Reconciliation Job

A job runs periodically:

1. Import external transactions (or accept via API upload)
2. Attempt to match to internal transactions:

   * by reference mapping table (best)
   * or by heuristic: (timestamp window, amount, currency, counterparty)
3. Create/Update reconciliation status:

   * MATCHED
   * AMOUNT_MISMATCH
   * MISSING_INTERNAL
   * MISSING_EXTERNAL

## 10B.4 Resolution Strategy

Never edit existing ledger entries.

Resolution creates **new compensating transactions**:

* Processor fee missing → post fee transaction
* Over/under settlement → post adjustment transaction
* Chargeback → post reversal transaction

Emit events:

* `ReconciliationMismatchDetected`
* `ReconciliationResolved`

### Interview talking points

* “Reconciliation issues are operational realities; the system must surface and resolve them without violating immutability.”
* “We record raw external data for audit, and corrections occur via compensating entries.”

---

## 11. Error Handling & API Contracts

### Error codes (examples)

* `IDEMPOTENCY_CONFLICT` (same reference_id but different payload)
* `INSUFFICIENT_FUNDS`
* `ZERO_SUM_VIOLATION`
* `CURRENCY_MISMATCH`
* `OPTIMISTIC_LOCK_RETRY_EXHAUSTED`

### Idempotency nuance (important)

If the client retries with the same `reference_id`:

* If payload matches, return the original result
* If payload differs, return a **409** with `IDEMPOTENCY_CONFLICT`

This is a very “Stripe-like” behavior.

---

## 12. Performance & Scaling

* Partition `ledger_entries` by month for long-term performance.
* Index for statement reads: `(account_id, created_at)`
* Maintain denormalized balances for fast reads; audit correctness remains in ledger.
* Outbox publisher uses batch sizes and backoff.
* Add a nightly “ledger integrity check” job:

  * ensure zero-sum per transaction per currency
  * ensure posted balances match ledger sum (detect drift bugs)

### Interview talking points

* “I keep correctness in the ledger, and scale reads through denormalized balances + partitioning.”

---

## 13. Security & Auditability

* `transactions.metadata` includes:

  * actor identity
  * request hash (sha256 of canonical request JSON)
  * ip, user-agent
  * correlation_id / trace_id

* Strict role-based controls for admin adjustments; all admin actions must be ledgered too.

---

## 14. Testing Strategy (Recruiter-Impressive)

### Must-have tests

1. **Property-based**: generated transactions always satisfy invariants; rejected otherwise
2. **Concurrency**: 100 parallel debits; ensure no double-spend
3. **Idempotency**: same reference_id 10x; ledger only once
4. **Outbox**: fail publisher; ensure event eventually published
5. **Reconciliation**: imported mismatches generate correct statuses and compensating transactions

Use Testcontainers to run real Postgres.

---

# 15. Failure Scenarios (Interview Favorites) + Your Answers

### Scenario A: Double-spend under concurrency

**What happens?** Two debits race.
**Answer:** optimistic locking ensures only one update succeeds; the other retries or fails with retriable error.

### Scenario B: Ledger committed but event publish fails

**Answer:** outbox row is already committed; publisher retries; eventual publish guaranteed. No dual-write bug.

### Scenario C: Event published twice

**Answer:** consumers must be idempotent using `event_key`. Publisher also enforces uniqueness.

### Scenario D: External settlement mismatch discovered 2 days later

**Answer:** reconciliation flags mismatch; resolution posts compensating transaction; no mutation of history.

### Scenario E: Hot revenue account causes retry storms

**Answer:** treat system accounts differently (pessimistic lock or sharding), plus rate-limit and batching.

### Scenario F: Idempotency key reused with different payload

**Answer:** reject with `IDEMPOTENCY_CONFLICT` to prevent ambiguous money movement.

---

## 16. Rollout Plan

### Phase 1 (Core)

* accounts/transactions/ledger_entries
* POST /transactions (POSTED only)
* statements, balances
* optimistic locking + idempotency

### Phase 2 (Safety)

* pending lifecycle
* holds + available balance
* reversal transactions
* integrity check job

### Phase 3 (Multi-currency)

* explicit FX model with rate snapshot
* FX spread accounted to revenue

### Phase 4 (Recruiter-grade)

* transactional outbox + publisher worker
* reconciliation import + mismatch detection + resolution flows
* events emitted for downstream systems

---

## Appendix: Implementation Notes (Spring Boot / Java)

* Use a single `@Transactional` service method for posting.
* Compute deltas per account and apply updates with version checks.
* Store a canonical hash of the request for idempotency conflict detection.
* Implement outbox publisher as a separate Spring scheduled job or worker service.
* Use Flyway migrations with strict ordering.
* Use JUnit + Testcontainers integration tests for concurrency and invariants.

---

# End of Design Doc

---

## If you want the “implementation drop” next (recommended)

I can now produce, in the next message, a complete Phase 4 skeleton including:

1. Flyway SQL migrations for `ledger_events`, `external_transactions`, `reconciliations`
2. Java entities + repositories
3. `OutboxPublisher` worker (poll, publish, mark published)
4. Reconciliation importer + matcher strategy (exact match + heuristic)
5. Minimal endpoints:

   * `POST /v1/reconciliation/import`
   * `GET /v1/reconciliation/{provider}/{external_id}`
   * `POST /v1/reconciliation/{id}/resolve` (creates compensating txn)

Say “generate Phase 4 code skeleton” and I will output the full file-by-file structure with code blocks.
