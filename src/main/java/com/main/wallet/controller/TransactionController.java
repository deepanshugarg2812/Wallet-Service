package com.main.wallet.controller;

import com.main.wallet.dto.TransferRequest;
import com.main.wallet.entity.Transaction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.main.wallet.service.TransactionService;

@RestController
@RequestMapping("/transfers")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<Transaction> createTransfer(
            @RequestBody TransferRequest request) {

        Transaction transaction =
                transactionService.createTransfer(request);

        return ResponseEntity.ok(transaction);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<Transaction> getTransfer(
            @PathVariable String transactionId) {

        return ResponseEntity.ok(
                transactionService.getTransfer(transactionId)
        );
    }
}
