package com.moiz.ledgerr.repository;

import com.moiz.ledgerr.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByTransactionId(Long transactionId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<LedgerEntry> findByAccountIdAndCurrencyOrderByCreatedAtDesc(Long accountId, String currency);
}
