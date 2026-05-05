package com.moiz.ledgerr.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
@Getter
@Setter
@NoArgsConstructor
public class LedgerTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_transactions_id_seq" )
    @SequenceGenerator(name = "ledger_transactions_id_seq", sequenceName = "ledger_transactions_id_seq", allocationSize = 50)
    Long id;

    // Caller-generated idempotency key. Format: source:operation_type:operation_id,
    // e.g. wallet:deposit:550e8400-e29b-41d4-a716-446655440000.
    // Retries of the same business operation must reuse the same referenceId.
    @NotBlank
    @Column(name = "reference_id", nullable = false, unique = true)
    private String referenceId;

    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    // Hash of the original posting request. If referenceId already exists and
    // this hash differs, the caller reused an idempotency key for different data.
    @Column(name = "request_hash")
    private String requestHash;

    @Column
    private String metadata = "{}";

    @Column(name = "reversed_transaction_id")
    private Long reversedTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public LedgerTransaction(String referenceId, String description, TransactionStatus status) {
        this.referenceId = referenceId;
        this.description = description;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (metadata == null) {
            metadata = "{}";
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
