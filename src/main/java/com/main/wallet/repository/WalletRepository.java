package com.main.wallet.repository;

import com.main.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    @Query(value = """
            SELECT *
            FROM wallet
            WHERE user_id = :userId
            """, nativeQuery = true)
    Optional<Wallet> findByUserId(@Param("userId") long userId);

    @Modifying
    @Query(value = """
            INSERT INTO wallet
                (id, user_id, amount_paisa, status, created_at, updated_at)
            VALUES
                (:id, :userId, 0, 'ACTIVE', NOW(), NOW())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int createWalletIfNotExists(
            @Param("id") long id,
            @Param("userId") long userId
    );

    @Modifying
    @Query(value = """
            UPDATE wallet
            SET amount_paisa = amount_paisa - :amount,
                updated_at = NOW()
            WHERE id = :walletId
              AND amount_paisa >= :amount
            """, nativeQuery = true)
    int debitIfSufficientBalance(
            @Param("walletId") long walletId,
            @Param("amount") long amount
    );

    @Modifying
    @Query(value = """
            UPDATE wallet
            SET amount_paisa = amount_paisa + :amount,
                updated_at = NOW()
            WHERE id = :walletId
            """, nativeQuery = true)
    int credit(
            @Param("walletId") long walletId,
            @Param("amount") long amount
    );

    @Query(value = """
            SELECT *
            FROM wallet
            WHERE id = :walletId
            """, nativeQuery = true)
    Optional<Wallet> findWallet(@Param("walletId") long walletId);
}