package com.main.wallet.service;

import com.main.wallet.dto.TransferRequest;
import com.main.wallet.entity.Transaction;
import com.main.wallet.exception.IdempotencyConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.main.wallet.repository.TransactionRepository;
import com.main.wallet.repository.WalletRepository;

import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            WalletRepository walletRepository) {

        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Transaction createTransfer(TransferRequest request) {

        validate(request);

        String requestHash = generateRequestHash(request);

        String transactionId = UUID.randomUUID().toString();

        /*
         * Try to claim the idempotency key.
         *
         * UNIQUE(idempotency_key) is the actual concurrency
         * control mechanism.
         */
        int inserted = transactionRepository.createIfAbsent(
                transactionId,
                request.walletFromId(),
                request.walletToId(),
                request.amountPaisa(),
                request.idempotencyKey(),
                requestHash
        );

        /*
         * Another request already owns this idempotency key.
         */
        if (inserted == 0) {

            Transaction existing =
                    transactionRepository
                            .findByIdempotencyKey(
                                    request.idempotencyKey()
                            )
                            .orElseThrow(() ->
                                    new RuntimeException(
                                            "Unable to find existing transaction"
                                    )
                            );

            /*
             * Same key + different request = 409.
             */
            if (!existing.getRequestHash().equals(requestHash)) {

                throw new IdempotencyConflictException(
                        "Idempotency key already used with different request"
                );
            }

            /*
             * Same key + same request.
             * Return original result.
             */
            return existing;
        }

        /*
         * We successfully claimed the idempotency key.
         *
         * Only THIS request is allowed to move money.
         */
        int debited = walletRepository.debitIfSufficientBalance(
                request.walletFromId(),
                request.amountPaisa()
        );

        if (debited == 0) {

            transactionRepository.updateStatus(
                    transactionId,
                    "DECLINED"
            );

            return transactionRepository
                    .findTransaction(transactionId)
                    .orElseThrow();
        }

        /*
         * Credit receiver.
         */
        int credited = walletRepository.credit(
                request.walletToId(),
                request.amountPaisa()
        );

        if (credited == 0) {

            /*
             * Throwing causes the entire DB transaction to rollback,
             * including the sender debit and PENDING transaction.
             */
            throw new RuntimeException(
                    "Receiver wallet not found"
            );
        }

        /*
         * Money movement succeeded.
         */
        transactionRepository.updateStatus(
                transactionId,
                "COMPLETED"
        );

        return transactionRepository
                .findTransaction(transactionId)
                .orElseThrow();
    }

    public Transaction getTransfer(String transactionId) {

        return transactionRepository
                .findTransaction(transactionId)
                .orElseThrow(() ->
                        new RuntimeException("Transfer not found")
                );
    }

    private void validate(TransferRequest request) {

        if (request.amountPaisa() <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be greater than zero"
            );
        }

        if (request.walletFromId() == request.walletToId()) {
            throw new IllegalArgumentException(
                    "Sender and receiver cannot be same"
            );
        }

        if (request.idempotencyKey() == null ||
                request.idempotencyKey().isBlank()) {

            throw new IllegalArgumentException(
                    "Idempotency key is required"
            );
        }
    }

    private String generateRequestHash(
            TransferRequest request) {

        return request.walletFromId() + ":" +
                request.walletToId() + ":" +
                request.amountPaisa();
    }
}