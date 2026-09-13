package com.main.wallet.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "Transaction_Entry", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_transfers_idempotency_key",
                columnNames = "idempotency_key"
        )
})
public class Transaction {
    @Id
    String id;
    long walletFromId;
    long WalletToId;
    long amountPaisa;
    String status;
    ZonedDateTime transactionDateTime;
    String idempotencyKey;
    String requestHash;

    public String getRequestHash() {
        return requestHash;
    }
}
