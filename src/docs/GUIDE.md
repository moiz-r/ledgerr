# Ledgerr Learning Guide

This guide teaches you how to build a production-grade, fault-tolerant ledger service in Java.
The main goal is to understand how ledgers work and introduce you to fintech systems.

You will learn by following a theory -> exercise -> test mindset for each module.
All code references are snippets, not full implementations.

Target stack: Java 22 + Spring Boot + Postgres + Flyway + JPA.
Package base: com.moiz.ledgerr

---

## 0. Prerequisites and Setup

### Skills checklist
- Java basics: classes, records, enums, generics, collections
- Spring basics: beans, dependency injection, @Configuration, @Service
- Maven basics: mvn test, mvn spring-boot:run
- SQL basics: tables, indexes, constraints, transactions

### Environment checklist
- Java 22 installed
- Maven installed
- Postgres running locally
- IDE configured with annotation processing (for Lombok if you use it)

### Project layout (flat structure)

```
src/main/java/com/moiz/ledgerr/
  model/          # Entities, enums, DTOs
  repository/     # Spring Data repositories
  service/        # Services and implementations
  interfaces/     # Controllers (API entry points)
  infrastructure/ # External integrations
  configuration/  # Spring configuration
  scheduler/      # Background jobs
  utils/          # Validators, exceptions, helpers
```

---

## 1. Core Ledger Concepts (Read First)

### Why ledgers exist
- Financial data must be immutable and auditable
- Balances should be derived from ledger entries, not mutated in place
- Every movement must be traceable to a transaction

### Double-entry accounting (non-negotiable)
- Every transaction has at least two entries
- For each currency: sum(debits) == sum(credits)
- Entries are append-only (never updated or deleted)

### How transactions interact with accounts (mental model)
- `transactions` is the immutable "what happened" wrapper (reference_id, status, metadata)
- `ledger_entries` are the immutable line items (which accounts changed)
- `accounts` are buckets; cached balances change only by posting transactions

Ledger entry invariants:
- amount is always positive; sign is via direction (DEBIT/CREDIT)
- per currency in a transaction: total debits == total credits
- entry currency matches account currency (until explicit FX modeling exists)

### Idempotency
- Clients retry requests; the ledger must not double-charge
- A unique reference_id prevents duplicate postings
- Same reference_id + different payload must return conflict

### Transaction lifecycle
- PENDING: recorded, not settled
- POSTED: finalized, affects balances
- REJECTED: invalid request
- REVERSED: corrected by compensating transaction

What happens at each step:
- PENDING: validate request, write transaction + entries as pending, do not change posted balances
- POSTED: commit transaction + entries, update posted balances, emit outbox event
- REJECTED: store transaction with failure reason, no balance changes, optional event for audit
- REVERSED: create a new compensating transaction that offsets the original, preserve immutability

Transaction lifecycle diagram:

```
            +-----------+
            |  PENDING  |
            +-----------+
              |       \
              |        \
              v         v
         +---------+  +----------+
         | POSTED  |  | REJECTED |
         +---------+  +----------+
              |
              v
         +----------+
         | REVERSED |
         +----------+
```

### Fault-tolerance principles
- Single DB transaction for posting (all-or-nothing)
- Optimistic locking for concurrent writes
- Outbox for reliable event publishing
- Reconciliation for mismatches with external systems

---

## Reference: Ledger Design Patterns and Practices (Spring Boot)

This section is a practical checklist of patterns you will keep reusing while building ledgers.
It is written as a reference you can come back to while implementing.

### 1) Treat the ledger as the source of truth (append-only)
- Make `ledger_entries` immutable and append-only. Do not update or delete entries.
- Corrections are modeled as new transactions (reversals / adjustments), not mutations.
- Keep business objects (like “Order” or “Payment”) separate from accounting records.

Reference:
- Stripe: Ledger as an immutable log of events: https://stripe.com/blog/ledger-stripe-system-for-tracking-and-validating-money-movement
- Modern Treasury: enforcing immutability and reversal strategies: https://www.moderntreasury.com/journal/enforcing-immutability-in-your-double-entry-ledger

### 2) Enforce invariants at write time
- Zero-sum per currency for each transaction (sum(debits) == sum(credits)).
- Amounts are stored in minor units (BIGINT/Long) and must be positive; direction is the sign.
- Currency consistency: entry currency must match account currency (unless you explicitly model FX).
- Idempotency: `reference_id` is unique and prevents duplicate financial effects.

Recommended implementation shape:
- A validator pipeline that runs before persistence.
- DB constraints as a backstop (CHECKs, UNIQUE indexes, foreign keys).

### 3) One posting operation = one database transaction
- The posting service method should be `@Transactional` and include:
  - insert transaction row
  - insert ledger entries
  - update denormalized balances (if you keep them)
  - insert outbox event(s)
- If any step fails, everything rolls back.

Spring Boot practice:
- Put `@Transactional` on service-layer methods (not on controllers).
- Keep the transactional method small and deterministic.

### 4) Concurrency control: optimistic locking first
- Use optimistic locking (`@Version`) on `accounts` if you update denormalized balances.
- Expect conflicts under load; handle them explicitly:
  - catch optimistic lock failures
  - retry the whole posting transaction with backoff + jitter
  - cap retries and return a retriable error when exhausted

Reference:
- JPA optimistic locking with @Version: https://www.baeldung.com/jpa-optimistic-locking

When optimistic locking is not enough:
- Hot accounts (system revenue, clearing accounts) may require:
  - sharded system accounts, or
  - targeted pessimistic locks, or
  - async aggregation downstream

### 5) Isolation level strategy (Postgres)

Why you care:
- Posting is a classic “invariant-maintaining transaction”. Concurrency can violate invariants unless
  you use locking, optimistic retries, or a stricter isolation level.

Practical choices:
- Default Postgres isolation is `READ COMMITTED`.
- For high-integrity posting paths you can choose stricter isolation (`REPEATABLE READ` or `SERIALIZABLE`),
  but you must be prepared to retry transactions when the database reports serialization failures.

Reference:
- Postgres isolation levels and the need to retry on serialization failure: https://www.postgresql.org/docs/current/transaction-iso.html

Rule of thumb:
- Start with optimistic locking + retries.
- If you still see invariant breaks under concurrency, revisit: isolation, row locks, and data modeling.

### 6) Transactional outbox (reliability for event publishing)

Problem:
- “Dual write”: update database + publish message. Either can succeed while the other fails.

Pattern:
- Within the same DB transaction, write an outbox record.
- A separate publisher job (polling) reads unpublished rows and publishes to your broker.
- Mark published after successful publish.

Important practice:
- Assume the outbox publisher can publish the same event more than once.
- Consumers must be idempotent (track event_key/message id).

References:
- Transactional outbox pattern (microservices.io): https://microservices.io/patterns/data/transactional-outbox.html
- Spring discussion of outbox vs transaction synchronization caveats: https://spring.io/blog/2023/10/24/a-use-case-for-transactions-adapting-to-transactional-outbox-pattern

### 7) Idempotency everywhere (API + events)
- API write endpoints require an idempotency key (`reference_id`).
- Store a `request_hash` to detect “same key, different payload” conflicts.
- For outbox events, use `event_key` unique constraint.
- For consumers, keep a “processed events” store keyed by event_key.

### 8) Reconciliation as a first-class workflow
- External processors and banks settle later and sometimes disagree.
- Store raw external transactions as immutable records.
- Reconciliation produces statuses (MATCHED, MISSING_INTERNAL, AMOUNT_MISMATCH, etc.).
- Resolution writes compensating transactions; never mutate history.

### 9) Observability and auditability
- Every transaction should carry metadata:
  - actor (user/admin/system)
  - correlation_id / trace_id
  - request hash
  - source (api, job, reconciliation)
- Emit structured logs and metrics for:
  - posting attempts, rejects, optimistic lock retries
  - serialization failures
  - outbox lag (unpublished count, oldest age)
  - reconciliation mismatch counts

### 10) Integrity checks and “drift detection” jobs
- Nightly/periodic checks:
  - every posted transaction is zero-sum per currency
  - denormalized balances match sums of ledger entries
  - no orphaned entries
- Treat failures as incidents: you found a correctness bug.

### 11) Testing practices that matter for ledgers
- Integration tests with real Postgres (Testcontainers): correctness lives at the DB boundary.
- Concurrency tests: parallel postings against the same account.
- Idempotency tests: same reference_id retried multiple times.
- Outbox tests: publisher crash/retry and duplicate publish.
- Reconciliation tests: mismatch detection and compensating transactions.

---

---

## Module 1: Account Entity (Foundation)

### What an account is (in a ledger)
An account is a bucket of value. It is not "a user" and it is not "auth".
Accounts are where ledger entries post debits/credits.

Key properties:
- `asset_class`: what kind of bucket this is (ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE)
- `currency`: accounts are single-currency (ISO-4217 like USD/EUR)
- `balance_posted` / `balance_pending`: cached/denormalized for fast reads
- `version`: optimistic locking if you update balances under concurrency

### Perspective rule (important)
The ledger is modeled from the platform/company perspective:
- User wallet balances are typically LIABILITIES (the platform owes users).
- Platform bank cash is typically an ASSET (the platform owns cash).

### Asset classes, simply
Asset classes exist so debit and credit have consistent meaning and reporting:

- ASSET: what you own (cash, bank, receivables)
- LIABILITY: what you owe (user balances, payables)
- EQUITY: owners' claim (capital, retained earnings)
- REVENUE: money earned (fees, spread)
- EXPENSE: money spent (bank fees, chargeback costs)

Normal balance behavior (common convention):
- ASSET / EXPENSE: DEBIT increases, CREDIT decreases
- LIABILITY / REVENUE / EQUITY: CREDIT increases, DEBIT decreases

### Multi-currency rule
- Enforce zero-sum per currency: for each transaction+currency bucket, sum(debits) == sum(credits)
- Keep accounts single-currency
- If you do FX (USD->EUR), model it explicitly (rate snapshot + FX accounts)

### Should we store user info in accounts?
No. Keep ledger accounts auth-agnostic.
If you need ownership, store a lightweight reference (e.g., `owner_reference`).

### Snippets (updated to BIGINT account ids)
Asset class enum:

```java
public enum AssetClass {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}
```

Account entity skeleton (id is BIGINT):

```java
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private AssetClass assetClass;

    private String currency;

    private Long balancePosted;
    private Long balancePending;

    @Version
    private Integer version;

    private Instant createdAt;
    private Instant updatedAt;
}
```

### Exercises
- Create AssetClass enum
- Create Account entity with JPA annotations
- Decide account ownership strategy (no auth data in ledger; optional owner_reference)
- Ensure account is single-currency and currency is ISO-4217

### Tests (descriptions only)
- Persist account and verify fields
- Verify optimistic locking rejects stale updates
- Validate currency format and asset class constraints

---

## Module 2: Transactions and Ledger Entries (Core Ledger)

### Theory
A transaction is a container for multiple ledger entries.
Ledger entries represent the atomic movements of value.
Entries are immutable.

Key ideas:
- Direction is DEBIT or CREDIT
- Amount is always positive
- Zero-sum per currency is mandatory
- reference_id enforces idempotency

### Snippets

Direction enum:

```java
public enum Direction {
    DEBIT,
    CREDIT
}
```

Transaction status enum:

```java
public enum TransactionStatus {
    PENDING,
    POSTED,
    REJECTED,
    REVERSED
}
```

Transaction entity skeleton:

```java
@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    private UUID id;

    @Column(unique = true)
    private String referenceId;

    private String description;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private String requestHash;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    private UUID reversedTransactionId;

    private Instant createdAt;
    private Instant updatedAt;
}
```

Ledger entry entity skeleton:

```java
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id
    private UUID id;

    private UUID transactionId;
    private Long accountId;

    private Long amount;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private String currency;

    private Instant createdAt;
}
```

Double-entry diagram (zero-sum per currency):

```
Transaction T1 (USD)

  [User Wallet]    DEBIT   1050
  [Merchant Wallet] CREDIT 1000
  [Platform Revenue] CREDIT  50

  Debits total:  1050
  Credits total: 1050
  Zero-sum: OK
```

### Common scenarios (platform perspective)

1) User deposit $100 USD (cash-in)
- Platform Cash USD (ASSET): +100
- User Wallet USD (LIABILITY): +100

2) User withdrawal $40 USD (cash-out)
- User Wallet USD (LIABILITY): -40
- Platform Cash USD (ASSET): -40

3) User-to-user transfer $25 USD (internal)
- Sender Wallet USD (LIABILITY): -25
- Receiver Wallet USD (LIABILITY): +25
Notes: platform assets do not change; total liabilities stay the same.

4) Transfer with $1 fee charged to sender
- Sender Wallet USD (LIABILITY): -26
- Receiver Wallet USD (LIABILITY): +25
- Fee Revenue (REVENUE): +1

5) Platform pays a $3 bank fee
- Bank Fees (EXPENSE): +3
- Platform Cash USD (ASSET): -3

6) Capital injection $1000
- Platform Cash USD (ASSET): +1000
- Paid-in Capital (EQUITY): +1000

7) Owner distribution $200
- Platform Cash USD (ASSET): -200
- Equity / Distributions (EQUITY): -200

### Exercises
- Create Direction and TransactionStatus enums
- Create Transaction and LedgerEntry entities
- Add constraints for amount > 0
- Ensure entry currency matches account currency
- Write a zero-sum validator (see Module 4)

### Tests (descriptions only)
- Creating a transaction inserts entries
- Zero-sum violation throws validation error
- Idempotency key duplicates do not create double postings

---

## Module 3: Repository Layer (Data Access)

### Theory
Spring Data JPA provides CRUD operations and query methods.
Repositories should be thin and focused on data access.

Key ideas:
- JpaRepository for base CRUD
- Custom finder methods via naming conventions
- Locking for concurrency-sensitive reads

### Snippets

AccountRepository:

```java
public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCurrency(String currency);
    List<Account> findByAssetClass(AssetClass assetClass);
    boolean existsByName(String name);
}
```

TransactionRepository:

```java
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByReferenceId(String referenceId);
    List<Transaction> findByStatus(TransactionStatus status);
}
```

LedgerEntryRepository:

```java
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionId(UUID transactionId);
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
```

### Exercises
- Implement repository interfaces
- Add custom query for account statement by date range
- Add optimistic locking queries where needed

### Tests (descriptions only)
- Repository CRUD basic test
- Find by reference_id returns correct transaction
- Statement query orders entries by timestamp

---

## Module 4: Validation and Business Rules

### Theory
Validation is the gatekeeper for ledger correctness.
Use a validator layer to enforce invariants before persistence.

Core invariants:
- Zero-sum per currency
- Currency consistency
- Non-negative amounts
- Sufficient funds (posted - holds)
- Idempotency conflict detection

### Snippets

Zero-sum validator outline:

```java
public class ZeroSumValidator {
    public void validate(List<LedgerEntry> entries) {
        // group entries by currency
        // sum debits and credits
        // throw if mismatch
    }
}
```

Idempotency validator outline:

```java
public class IdempotencyValidator {
    public Optional<Transaction> check(String referenceId, String requestHash) {
        // find transaction by referenceId
        // if present and hash differs -> conflict
        // if present and hash matches -> return existing
        // else return empty
    }
}
```

### Exercises
- Create custom exceptions in utils
- Implement ZeroSumValidator
- Implement CurrencyConsistencyValidator
- Implement SufficientFundsValidator
- Implement RequestHashCalculator (SHA-256)

### Tests (descriptions only)
- Zero-sum mismatch throws exception
- Currency mismatch throws exception
- Insufficient funds throws exception
- Idempotency conflict throws exception

---

## Module 5: Holds and Authorizations (Mandatory)

### Theory
Holds reserve funds without posting final transfer.
Available balance = posted_balance - active_holds.

Hold lifecycle:
- ACTIVE: reserved
- CAPTURED: converted to transaction
- RELEASED: removed without posting
- EXPIRED: removed after expiry

Hold lifecycle diagram:

```
          +--------+
          | ACTIVE |
          +--------+
            |  |  \
            |  |   \
            v  v    v
      +---------+ +----------+ +---------+
      | CAPTURED| | RELEASED | | EXPIRED |
      +---------+ +----------+ +---------+
```

### Snippets

Hold status enum:

```java
public enum HoldStatus {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED
}
```

Hold entity outline:

```java
@Entity
@Table(name = "holds")
public class Hold {
    @Id
    private UUID id;
    private String referenceId;
    private UUID accountId;
    private Long amount;
    private String currency;

    @Enumerated(EnumType.STRING)
    private HoldStatus status;

    private Instant expiresAt;
    private Instant createdAt;
    private UUID captureTransactionId;
}
```

### Exercises
- Create HoldStatus enum and Hold entity
- Build HoldRepository
- Implement available balance calculation
- Create hold expiration job (scheduler)
- Implement hold capture logic

### Tests (descriptions only)
- Active holds reduce available balance
- Capture creates a posted transaction
- Expired holds no longer affect balance

---

## Module 6: Transactional Outbox and Reconciliation (Mandatory)

### Theory: Transactional Outbox
Writing to a DB and publishing to a broker is a dual-write problem.
The outbox pattern writes events in the same DB transaction as ledger updates.
Then a background job publishes them later.

Benefits:
- No lost events
- No phantom events
- Guaranteed eventual delivery

Outbox flow diagram:

```
Client Request
     |
     v
DB Transaction
  - insert transaction
  - insert entries
  - update balances
  - insert outbox event
     |
     v
Commit
     |
     v
Outbox Publisher
  - poll unpublished events
  - publish to broker
  - mark published
```

### Theory: Reconciliation
External processors may differ from your ledger due to delays, fees, and errors.
Reconciliation detects mismatches and resolves them with compensating transactions.

Reconciliation flow diagram:

```
External Feed
     |
     v
Import External Transactions
     |
     v
Match to Internal Ledger
  |      |       |
  |      |       +--> AMOUNT/CURRENCY MISMATCH
  |      +----------> MISSING_INTERNAL
  +-----------------> MATCHED
     |
     v
Resolution
  - post compensating transaction
  - mark RESOLVED
```

### Snippets

Outbox event entity outline:

```java
@Entity
@Table(name = "ledger_events")
public class LedgerEvent {
    @Id
    private UUID id;
    private String eventKey;
    private String aggregateType;
    private UUID aggregateId;
    private String eventType;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;
}
```

External transaction outline:

```java
@Entity
@Table(name = "external_transactions")
public class ExternalTransaction {
    @Id
    private UUID id;
    private String provider;
    private String externalId;
    private Instant occurredAt;
    private String currency;
    private Long amount;
    private String type;
    private String raw;
}
```

Reconciliation status enum:

```java
public enum ReconciliationStatus {
    MATCHED,
    MISSING_INTERNAL,
    MISSING_EXTERNAL,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    RESOLVED
}
```

Reconciliation entity outline:

```java
@Entity
@Table(name = "reconciliations")
public class Reconciliation {
    @Id
    private UUID id;
    private String provider;
    private String externalId;
    private UUID internalTransactionId;

    @Enumerated(EnumType.STRING)
    private ReconciliationStatus status;

    private Long diffAmount;
    private String notes;
    private Instant createdAt;
    private Instant resolvedAt;
}
```

### Exercises
- Create LedgerEvent entity and repository
- Create outbox publisher job (scheduler)
- Create ExternalTransaction entity and repository
- Create Reconciliation entity and repository
- Build reconciliation matcher
- Build compensating transaction creator

### Tests (descriptions only)
- Outbox events are created within posting transaction
- Outbox publisher marks events as published
- Reconciliation detects mismatches correctly
- Compensating transactions restore zero-sum integrity

---

## Posting Flow (Production-Grade Outline)

All posting must happen in a single DB transaction.
If any step fails, nothing is committed.

Steps:
1. Validate request
2. Check idempotency
3. Load accounts (with optimistic locking)
4. Validate funds and currency
5. Insert transaction row
6. Insert ledger entries
7. Update denormalized balances
8. Insert outbox event

---

## Testing Strategy

### Unit tests (fast)
- Validators: zero-sum, currency consistency, sufficient funds
- Idempotency conflict detection
- Request hash calculator

### Integration tests (real DB)
- Post transaction end-to-end
- Optimistic locking under concurrency
- Outbox publisher job
- Reconciliation flow

### Concurrency tests
- Parallel debits on same account
- Ensure no double-spend

### Property-based tests
- Random entry sets always satisfy or reject zero-sum

---

## Common Pitfalls

- Using floating point for money
- Allowing ledger entry updates
- Skipping idempotency
- Publishing events outside the DB transaction
- Ignoring reconciliation mismatches

---

## Interview Talking Points

- "Ledger entries are the immutable source of truth."
- "Balances are derived views for performance."
- "Idempotency prevents double charges under retries."
- "Outbox solves dual-write failure modes."
- "Reconciliation handles real-world settlement mismatches."

---

## Next Steps

1. Implement Module 1 entities and tests
2. Implement Module 2 entities and validators
3. Build repository layer and integration tests
4. Add posting service with transactional logic
5. Implement holds, outbox, and reconciliation
6. Expand API endpoints and load tests
