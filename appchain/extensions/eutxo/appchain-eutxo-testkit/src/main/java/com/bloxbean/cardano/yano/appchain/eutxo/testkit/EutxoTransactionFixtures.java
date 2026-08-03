package com.bloxbean.cardano.yano.appchain.eutxo.testkit;

import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionDomain;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Small Cardano transaction builder for deterministic EUTxO conformance tests. */
public final class EutxoTransactionFixtures {
    private EutxoTransactionFixtures() {
    }

    public static Transaction signedPayment(
            EutxoOutpoint input,
            EutxoTestWallet signer,
            List<Payment> outputs,
            long validityStart,
            long ttl
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(signer, "signer");
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
        }
        return signedOutputs(
                input,
                signer,
                outputs.stream()
                        .map(payment -> TransactionOutput.builder()
                                .address(payment.address())
                                .value(Value.fromCoin(payment.lovelace()))
                                .build())
                        .toList(),
                validityStart,
                ttl);
    }

    public static Transaction signedOutputs(
            EutxoOutpoint input,
            EutxoTestWallet signer,
            List<TransactionOutput> outputs,
            long validityStart,
            long ttl
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(signer, "signer");
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
        }
        Transaction transaction = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(input.transactionId())
                                .index(input.index())
                                .build()))
                        .outputs(List.copyOf(outputs))
                        .fee(BigInteger.ZERO)
                        .validityStartInterval(validityStart)
                        .ttl(ttl)
                        .networkId(NetworkId.TESTNET)
                        .build())
                .isValid(true)
                .build();
        return TransactionSigner.INSTANCE.sign(transaction, signer.signingKey());
    }

    public static Transaction signedPayment(
            EutxoOutpoint input,
            EutxoTestWallet signer,
            List<Payment> outputs,
            long validityStart,
            long ttl,
            EutxoTransactionDomain domain
    ) {
        Objects.requireNonNull(domain, "domain");
        if (domain.expiry() != ttl) {
            throw new IllegalArgumentException(
                    "domain expiry must equal transaction TTL");
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(signer, "signer");
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
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
                                        .value(Value.fromCoin(
                                                payment.lovelace()))
                                        .build())
                                .toList())
                        .fee(BigInteger.ZERO)
                        .validityStartInterval(validityStart)
                        .ttl(ttl)
                        .networkId(NetworkId.TESTNET)
                        .build())
                .isValid(true)
                .build();
        domain.attach(transaction);
        return TransactionSigner.INSTANCE.sign(
                transaction, signer.signingKey());
    }

    public static byte[] serialize(Transaction transaction) {
        try {
            return transaction.serialize();
        } catch (Exception failure) {
            throw new IllegalArgumentException("transaction cannot be encoded", failure);
        }
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
