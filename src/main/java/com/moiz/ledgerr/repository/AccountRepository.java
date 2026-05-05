package com.moiz.ledgerr.repository;

import com.moiz.ledgerr.model.Account;
import com.moiz.ledgerr.model.AssetClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCurrency(String currency);

    List<Account> findByAssetClass(AssetClass assetClass);

    List<Account> findByOwnerReference(String ownerReference);

    Optional<Account> findByOwnerReferenceAndAccountTypeAndCurrency(
            String ownerReference,
            String accountType,
            String currency
    );
    Optional<Account> findByOwnerReferenceAndAssetClassAndCurrency(
            String ownerReference,
            AssetClass assetClass,
            String currency
    );

    boolean existsByName(String name);
}
