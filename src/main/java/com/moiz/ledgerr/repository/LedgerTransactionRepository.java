package com.moiz.ledgerr.repository;

import com.moiz.ledgerr.model.LedgerTransaction;
import com.moiz.ledgerr.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {
    Optional<LedgerTransaction> findByReferenceId(String referenceId);

    boolean existsByReferenceId(String referenceId);

    List<LedgerTransaction> findByStatus(TransactionStatus status);
}
