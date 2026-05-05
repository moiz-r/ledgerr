package com.moiz.ledgerr.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 16)
    private AssetClass assetClass;

    @NotBlank
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_posted", nullable = false)
    private long balancePosted;

    @Column(name = "balance_pending", nullable = false)
    private long balancePending;

    @Column(name = "account_type")
    private String accountType;

    @Column(name = "owner_reference", nullable = false)
    private String ownerReference;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Account(String name, AssetClass assetClass, String currency) {
        this.name = name;
        this.assetClass = assetClass;
        this.currency = currency;
    }

    public void applyPosted(Direction direction, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        int sign = direction == normalIncreaseDirection() ? 1 : -1;
        balancePosted += sign * amount;
    }

    private Direction normalIncreaseDirection() {
        return switch (assetClass) {
            case ASSET, EXPENSE -> Direction.DEBIT;
            case LIABILITY, EQUITY, REVENUE -> Direction.CREDIT;
        };
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
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
