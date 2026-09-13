package com.main.wallet.repository;

import com.main.wallet.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, String> {

    @Query(value = """
            SELECT *
            FROM Transaction_Entry
            WHERE id = :id
            """, nativeQuery = true)
    Optional<Transaction> findTransaction(
            @Param("id") String id
    );

    @Query(value = """
            SELECT *
            FROM Transaction_Entry
            WHERE idempotency_key = :idempotencyKey
            """, nativeQuery = true)
    Optional<Transaction> findByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO Transaction_Entry
            (
                id,
                wallet_from_id,
                wallet_to_id,
                amount_paisa,
                status,
                transaction_date_time,
                idempotency_key,
                request_hash
            )
            VALUES
            (
                :id,
                :walletFromId,
                :walletToId,
                :amountPaisa,
                'PENDING',
                NOW(),
                :idempotencyKey,
                :requestHash
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int createIfAbsent(
            @Param("id") String id,
            @Param("walletFromId") long walletFromId,
            @Param("walletToId") long walletToId,
            @Param("amountPaisa") long amountPaisa,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );

    @Modifying
    @Query(value = """
            UPDATE Transaction_Entry
            SET status = :status
            WHERE id = :id
            """, nativeQuery = true)
    int updateStatus(
            @Param("id") String id,
            @Param("status") String status
    );
}