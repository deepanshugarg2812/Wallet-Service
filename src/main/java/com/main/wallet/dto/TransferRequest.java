package com.main.wallet.dto;

public record TransferRequest(
        long walletFromId,
        long walletToId,
        long amountPaisa,
        String idempotencyKey
) {
}
