package com.main.wallet.controller;

import com.main.wallet.entity.Wallet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.main.wallet.service.WalletService;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<Wallet> createWallet(
            @RequestParam long userId) {

        Wallet wallet = walletService.createWallet(userId);

        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<Wallet> getWallet(
            @PathVariable long walletId) {

        return ResponseEntity.ok(
                walletService.getWallet(walletId)
        );
    }
}