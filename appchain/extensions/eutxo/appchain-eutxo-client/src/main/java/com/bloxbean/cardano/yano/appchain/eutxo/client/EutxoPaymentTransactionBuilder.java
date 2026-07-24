package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Reusable builder for the bounded key-controlled, ADA-only L2 subset. */
public final class EutxoPaymentTransactionBuilder {
    private EutxoPaymentTransactionBuilder() {
    }

    public static Transaction signedPayment(
            EutxoOutpoint input,
            EutxoKeyWallet signer,
            List<Payment> outputs,
            long validityStart,
            long ttl) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(signer, "signer");
        if (outputs == null || outputs.isEmpty() || outputs.size() > 64) {
            throw new IllegalArgumentException("one to 64 outputs are required");
        }
        if (validityStart < 0 || ttl < validityStart) {
            throw new IllegalArgumentException("invalid L2 validity interval");
        }
        Transaction transaction = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(input.transactionId())
                                .index(input.index())
                                .build()))
                        .outputs(outputs.stream()
                                .map(payment -> TransactionOutput.builder()
                                        .address(payment.address())
                                        .value(Value.fromCoin(payment.lovelace()))
                                        .build())
                                .toList())
                        .fee(BigInteger.ZERO)
                        .validityStartInterval(validityStart)
                        .ttl(ttl)
                        .networkId(NetworkId.TESTNET)
                        .build())
                .isValid(true)
                .build();
        return TransactionSigner.INSTANCE.sign(transaction, signer.signingKey());
    }

    public record Payment(String address, BigInteger lovelace) {
        public Payment {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("payment address is required");
            }
            if (lovelace == null || lovelace.signum() <= 0) {
                throw new IllegalArgumentException("payment lovelace must be positive");
            }
        }
    }
}
