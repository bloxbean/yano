package com.bloxbean.cardano.yano.api.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable, effective effects in transaction order, before optional storage filtering. */
public record UtxoChanges(ChainPoint previous, ChainPoint point, String era,
                          List<Transaction> transactions, List<Input> protocolConsumed) {
    public UtxoChanges(ChainPoint previous, ChainPoint point, String era, List<Transaction> transactions) {
        this(previous, point, era, transactions, List.of());
    }

    public UtxoChanges {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(point);
        transactions = List.copyOf(transactions);
        protocolConsumed = List.copyOf(protocolConsumed);
    }

    /** CBOR is optional for synthetic transactions; subjects cover wallet-relevant ledger events. */
    public record Transaction(String hash, boolean valid, List<Input> consumed,
                              List<Output> created, String bodyCbor,
                              List<Subject> subjects, String subjectError) {
        public Transaction {
            consumed = List.copyOf(consumed);
            created = List.copyOf(created);
            subjects = List.copyOf(subjects);
        }
    }

    public enum Resolution { NOT_REQUESTED, RESOLVED, UNRESOLVED }

    public record Input(Outpoint outpoint, Resolution resolution, String address) {
        public Input {
            Objects.requireNonNull(outpoint);
            Objects.requireNonNull(resolution);
            if ((resolution == Resolution.RESOLVED) != (address != null)) {
                throw new IllegalArgumentException("Input resolution/address mismatch");
            }
        }
    }

    /** Focused output view; no mutable transaction/amount objects cross the SPI. */
    public record Output(Outpoint outpoint, String address, BigInteger lovelace,
                         boolean collateralReturn) { }

    /** Ledger credential subject (stake or DRep), or reward-account address. */
    public sealed interface Subject permits CredentialSubject, RewardAccountSubject { }
    public record CredentialSubject(String role, String type, String hash) implements Subject { }
    public record RewardAccountSubject(String address) implements Subject { }
}
