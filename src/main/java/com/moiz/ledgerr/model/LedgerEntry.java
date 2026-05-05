package com.moiz.ledgerr.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entries_id_seq")
    @SequenceGenerator(name = "ledger_entries_id_seq", sequenceName = "ledger_entries_id_seq", allocationSize = 50)
    private Long id;

    @NotNull
    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @NotNull
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Positive
    @Column(nullable = false)
    private long amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    @NotNull
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
