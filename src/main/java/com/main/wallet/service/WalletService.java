package com.main.wallet.service;

import com.main.wallet.entity.Wallet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.main.wallet.repository.WalletRepository;

import java.util.Optional;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet createWallet(long userId) {

        long walletId = UUID.randomUUID().getMostSignificantBits()
                & Long.MAX_VALUE;

        walletRepository.createWalletIfNotExists(
                walletId,
                userId
        );

        return walletRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new RuntimeException("Unable to create/find wallet")
                );
    }

    public Wallet getWallet(long walletId) {

        return walletRepository.findWallet(walletId)
                .orElseThrow(() ->
                        new RuntimeException("Wallet not found")
                );
    }
}
