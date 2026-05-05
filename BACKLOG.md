# Ledgerr Backlog

This is the prioritized implementation backlog for Ledgerr, ordered to maximize
CV signal per unit of effort and to prevent scope bloat. Build top-to-bottom.
**Stop after Tier 3 unless you have time and energy** — the project is already
recruiter-ready at that point.

Effort tags: **S** = half day, **M** = 1–2 days, **L** = 3–5 days.

---

## Critical Path (what "done" looks like)

Build in this order. Each tier is shippable on its own.

1. **Tier 0 — Foundation** (entities + schema). _Mostly done already._
2. **Tier 1 — Posting works correctly** (idempotent, atomic, concurrency-safe). _The minimum that earns the word "ledger"._
3. **Tier 2 — Lifecycle** (holds + reversals). _Separates "I read about ledgers" from "I've modeled settlement."_
4. **Tier 3 — The differentiator** (outbox + reconciliation + polish docs). _The reason a fintech recruiter calls you back._
5. **Tier 4 — Optional depth.** Pick one or two; skip the rest.
6. **Design-only.** Document in README, do not build.

If you finish Tier 3, your README + benchmarks + runbook tell the story for you.
Stop there. Do not chase Tier 4 perfection at the cost of polish.

---

## Tier 0 — Foundation

Goal: schema and entities exist, repositories compile, basic CRUD works.

- [x] **L1-1** Account entity with `id, name, accountType, properties (jsonb), assetClass, currency, balancePosted, balancePending, version, createdAt, updatedAt`. (S)
- [x] **L1-2** Repository finders: by currency, assetClass, accountType. Seed migration for system accounts. (S)
- [x] **Module 2 entities** `LedgerTransaction`, `LedgerEntry`, `Direction`, `TransactionStatus`. (S)
- [ ] **T0-1** Flyway migration `V1__initial_schema.sql` covers `accounts`, `transactions`, `ledger_entries` with all CHECK constraints, FKs, and indexes from the GUIDE. (S)
- [ ] **T0-2** Testcontainers Postgres harness wired into `mvn verify`. One smoke test that persists an account round-trips. (S)
- [ ] **T0-3** `docker compose up` boots Postgres + the app with seed system accounts (`platform_cash_USD`, `fee_revenue_USD`, etc.). (S)

**Stop here only if abandoning the project.** Without Tier 1, this is a CRUD app.

---

## Tier 1 — Posting works correctly (the credibility bar)

Goal: `POST /v1/transactions` is atomic, idempotent, zero-sum-validated, and
survives concurrent writes. Without this, nothing else matters.

- [ ] **L1-3** `POST /v1/transactions` accepts `reference_id, entries[], status=POSTED, metadata` and returns 201 with the created transaction. (M)
  - AC: zero-sum per currency enforced at write time.
  - AC: amount > 0; entry currency = account currency.
  - AC: `reference_id` unique constraint at DB layer.
  - AC: SHA-256 `request_hash` stored on the transaction row.

- [ ] **L1-5** Posting is one `@Transactional` service method: insert transaction, insert entries, update denormalized balances. Partial failure rolls back. (M)
  - AC: integration test asserts no partial rows on forced failure mid-method.

- [ ] **L1-4** Idempotency contract: same `reference_id` + same payload returns the original response; same key + different payload returns `409 IDEMPOTENCY_CONFLICT`. (S)
  - AC: deterministic canonical-JSON hashing; both branches covered by tests.

- [ ] **L1-8** Optimistic locking with bounded retry. (M)
  - AC: `@Version` on Account.
  - AC: jittered backoff, max 3 retries, then `409 OPTIMISTIC_LOCK_RETRY_EXHAUSTED`.
  - AC: **concurrency test** — 100 parallel debits on one account; ledger sum equals balance; no negative balance; no double-spend. _This test alone is worth half the project's CV value._

- [ ] **L1-6** `GET /v1/accounts/{id}/balance` returns `posted_balance, held_amount, available_balance`. (S)
- [ ] **L1-7** `GET /v1/accounts/{id}/statement?from=&to=&cursor=&limit=` with cursor pagination and running balance. (M)

- [ ] **X-1** Property-based tests (jqwik): generated entry sets are either accepted-and-balanced or rejected — never accepted-and-broken. (M)

**Tier 1 completion criteria:** you can demo a posting via curl, kill the
process mid-post and see no orphan rows, and run the 100-way concurrency test
to a clean pass. **Stop here only if time-boxed to one week total.**

---

## Tier 2 — Lifecycle (holds + reversals)

Goal: model real settlement, not just immediate transfers. This is what makes
the ledger look like a payments system instead of a journal.

- [ ] **L2-1** Posting with `status=PENDING` updates `balance_pending`, leaves `balance_posted` untouched. (S)
- [ ] **L2-2** `POST /v1/transactions/{id}/post` transitions PENDING → POSTED atomically. (S)
- [ ] **L2-3** `POST /v1/holds` reserves funds. Reduces `available_balance`. Idempotent on `reference_id`. (M)
- [ ] **L2-4** `POST /v1/holds/{id}/capture` posts a real transaction linked via `capture_transaction_id`. Remainder released. (M)
- [ ] **L2-5** `POST /v1/holds/{id}/release` returns funds to available. Idempotent. (S)
- [ ] **L2-6** Scheduled job expires holds past `expires_at` using a claim-and-update pattern (no global locks). (S)
- [ ] **L2-7** `POST /v1/transactions/{id}/reverse` creates a compensating transaction; original status flips to REVERSED; original rows untouched. (M)
- [ ] **L2-8** Nightly integrity-check job: zero-sum holds across all transactions; `balance_posted` matches ledger sum; no orphaned entries. Drift logged + metric exported. (M)

**Tier 2 completion criteria:** you can model "user authorizes $100 at a
merchant, captures $90, $10 released" and "operator reverses a posted
transaction" — both end-to-end with an audit trail.

---

## Tier 3 — The differentiator (DO THIS, this is your CV)

Goal: production-grade primitives that 90% of portfolio repos lack. Each item
here is a separate talking point in a fintech interview.

### Transactional outbox

- [ ] **L4-1** Posting transaction also inserts a `ledger_events` row in the same DB transaction. Partial index `WHERE published_at IS NULL`. (M)
- [ ] **L4-2** `OutboxPublisher` worker polls unpublished events, publishes via a `Publisher` interface (start with `LoggingPublisher`), marks `published_at`. Configurable batch size + backoff. (M)
- [ ] **L4-3** Crash-safety test: kill the publisher mid-batch with Testcontainers, restart, assert each event reaches the consumer with stable `event_key` and the consumer dedupes. (M)
- [ ] **L8-1** Use `SELECT ... FOR UPDATE SKIP LOCKED` so multiple publishers run safely. Adds ~5 lines. Big signal. (S)

### Reconciliation

- [ ] **L4-4** `POST /v1/reconciliation/import` accepts batches of `external_transactions`. `UNIQUE(provider, external_id)`. Raw JSON preserved. Idempotent re-import. (M)
- [ ] **L4-5** Reconciliation job matches imports to internal transactions; produces `MATCHED | MISSING_INTERNAL | MISSING_EXTERNAL | AMOUNT_MISMATCH | CURRENCY_MISMATCH` with `diff_amount`. Exact-match first, then heuristic fallback (timestamp window + amount + currency). (L)
- [ ] **L4-6** `POST /v1/reconciliation/{id}/resolve` creates a compensating transaction (fee, adjustment, or reversal); marks recon row RESOLVED with reason code. Emits `ReconciliationResolved` via outbox. (M)

### Polish that recruiters actually read

- [ ] **L5-2** OpenAPI 3.1 spec via `springdoc-openapi`, served at `/v3/api-docs`. CI fails on drift. (S)
- [ ] **L5-4** RFC 7807 `application/problem+json` for all errors with stable `type` URIs. (S)
- [ ] **L5-1** `Idempotency-Key` header support (Stripe-shaped). (S)
- [ ] **L9-3** `BENCHMARKS.md` with k6 or Gatling: postings/sec, p50/p99 latency, optimistic-retry rate. _Even modest numbers crush 90% of portfolio repos that have none._ (M)
- [ ] **L8-6** `RUNBOOK.md` with concrete first-response procedures for: balance-incorrect, outbox lag rising, recon mismatch surge, integrity-check drift, optimistic-lock storm. (S)
- [ ] **DOC-1** `DESIGN_DECISIONS.md` listing ~10 tradeoffs (optimistic vs pessimistic, polling vs CDC, denormalized balance vs always-derive, etc.). One paragraph each. _Free signal that you think in tradeoffs._ (S)
- [ ] **DOC-2** `FAILURE_MODES.md` — Scenarios A–F from PRD §15, each linked to the test that proves the answer. _This document is your interview, pre-answered._ (S)
- [ ] **L12-2** README has a 60-second "post your first transaction" curl demo and an asciinema/GIF. (S)

### Observability minimums

- [ ] **X-5** Every transaction carries `correlation_id, actor, request_hash, source` in metadata. (S)
- [ ] **X-6** Metrics: posting latency, OL retry count, outbox lag (oldest unpublished age), recon break count, integrity drift count. Micrometer + Prometheus endpoint. (S)
- [ ] **X-7** Structured JSON logs with `correlation_id` propagated through scheduled jobs. (S)

**Tier 3 completion criteria:** README links to BENCHMARKS, RUNBOOK,
DESIGN_DECISIONS, FAILURE_MODES, and an OpenAPI spec. **STOP HERE.** This is
recruiter-ready. Anything below is for personal interest, not CV value.

---

## Tier 4 — Optional depth (pick at most two)

Each of these adds an interview talking point, but none change the
"hireability" needle on top of Tier 3. Pick the one that interests *you*.

- [ ] **L11-6** Ledger-replay tool: rebuild denormalized balances from `ledger_entries` end-to-end. CLI command. _Trivial once correct, but lets you say "I can reconstruct state from the ledger end-to-end."_ (M)
- [ ] **L7-1 + L7-2** Admin adjustments with dual-control approval + separate `admin_audit_log` table. (M)
- [ ] **L10-1** Tamper-evident chain: each entry stores `hash_n = sha256(prev_hash || entry)`. Verifier tool included. (M)
- [ ] **L10-3** Write the GDPR-vs-immutability design note (no code; it's an essay). _Punches above its weight as an interview opener._ (S)
- [ ] **L11-1** Bulk transactions endpoint with all-or-nothing or per-row semantics. (M)
- [ ] **L11-3** Holds support incremental capture (multiple captures up to held amount). (M)
- [ ] **L8-4** Monthly partitioning of `ledger_entries` with archival job. (M)
- [ ] **L9-1** Hot-account sharding for `fee_revenue` with a `RevenueRouter`. (M)
- [ ] **L3-1** FX transactions with rate snapshot and spread-to-revenue. (L)
- [ ] **L5-7 + L5-8 + L5-9** Webhooks with HMAC signing, retries, replay. _Big surface area; only do if a target employer specifically values it (Stripe, Adyen)._ (L)

---

## Design-only (mention in README, do not build)

Naming these in `DESIGN_DECISIONS.md` shows awareness without spending the time:

- **L8-2** CDC-based publisher (Debezium → Kafka) as alternative to polling.
- **L9-1** Hot-account sharding (mentioned above; document the strategy even if unimplemented).
- **L9-2** Read-replica routing for statement reads.
- **L10-5** Multi-tenancy via Postgres RLS.
- **L11-2** Future-dated / scheduled transactions.
- **L11-5** Outbox event schema versioning + JSON-schema registry.
- **PRD §3A** Full wallet product layer (KYC tiers, P2P, deposits/withdrawals, dispute lifecycle). Reference it as "out of scope for the ledger core" — that *strengthens* your positioning by showing scope discipline.

---

## What this looks like on a CV

Lead with outcomes, not features. The Tier 1–3 work supports these bullets:

- "Built an immutable double-entry ledger in Java/Spring Boot/Postgres enforcing zero-sum invariants per currency, with idempotent POST under SHA-256 request hashing."
- "Implemented optimistic concurrency with bounded retry and jitter; verified via 100-way parallel-posting test on real Postgres — no double-spend, no lost updates."
- "Added transactional outbox + polling publisher (`SELECT FOR UPDATE SKIP LOCKED`) eliminating dual-write bugs; verified exactly-once-effective delivery via consumer-side `event_key` dedupe under publisher-crash tests."
- "Built reconciliation pipeline (import → match → resolve via compensating transactions) preserving ledger immutability, modeled after Stripe and Modern Treasury patterns."
- "Documented design tradeoffs, failure modes, and operational runbooks; published reproducible benchmarks (p50/p99 latency, throughput, retry rate)."

Five bullets. That is a fintech-attractive CV entry. Everything in this
backlog exists to support those five sentences.

---

## Estimated calendar

Working evenings/weekends, solo:

- Tier 0: **1–2 days** (mostly done).
- Tier 1: **5–7 days.** This is the hard part. Do not move on until the concurrency test is green.
- Tier 2: **4–5 days.**
- Tier 3: **6–8 days.** ~3 days of code (outbox + recon), ~3 days of docs/benchmarks.
- **Total to recruiter-ready: ~3 weeks of focused work.**

If you find yourself at week 5 still in Tier 1, cut scope: ship Tier 1 + outbox
only, write the docs, and call it done. A finished smaller project beats an
unfinished bigger one every time.
