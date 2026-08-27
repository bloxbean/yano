package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.core.address.Outpoint;
import com.bloxbean.cardano.yano.archive.core.address.ResolvedOutput;

import java.util.Arrays;
import java.util.Objects;

/** Semantic changes applied atomically with one canonical hot block. */
public sealed interface HotHistoryOperation permits HotHistoryOperation.Fact,
        HotHistoryOperation.OutputCreated, HotHistoryOperation.OutputConsumed,
        HotHistoryOperation.PointerRegistered, HotHistoryOperation.PointerDeregistered {

    record Fact(ArchiveRow row) implements HotHistoryOperation {
        public Fact { Objects.requireNonNull(row, "row"); }
    }

    record OutputCreated(Outpoint outpoint, ResolvedOutput output)
            implements HotHistoryOperation {
        public OutputCreated {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(output, "output");
        }
    }

    record OutputConsumed(Outpoint outpoint, byte[] spendingTxHash,
                          String inputRole) implements HotHistoryOperation {
        public OutputConsumed {
            Objects.requireNonNull(outpoint, "outpoint");
            spendingTxHash = Objects.requireNonNull(spendingTxHash, "spendingTxHash").clone();
            if (spendingTxHash.length != 32) throw new IllegalArgumentException("spending tx hash must be 32 bytes");
            if (!"ordinary".equals(inputRole) && !"collateral".equals(inputRole)) {
                throw new IllegalArgumentException("unsupported resolver input role");
            }
        }

        @Override public byte[] spendingTxHash() { return spendingTxHash.clone(); }
    }

    record PointerRegistered(long slot, int txIndex, int certIndex,
                             String credentialType, byte[] credential) implements HotHistoryOperation {
        public PointerRegistered {
            requirePointer(slot, txIndex, certIndex);
            requireCredential(credentialType, credential);
            credential = credential.clone();
        }

        @Override public byte[] credential() { return credential.clone(); }
    }

    record PointerDeregistered(long slot, int txIndex, int certIndex,
                               String credentialType, byte[] credential)
            implements HotHistoryOperation {
        public PointerDeregistered {
            requirePointer(slot, txIndex, certIndex);
            requireCredential(credentialType, credential);
            credential = credential.clone();
        }

        @Override public byte[] credential() { return credential.clone(); }
    }

    private static void requirePointer(long slot, int txIndex, int certIndex) {
        if (slot < 0 || txIndex < 0 || certIndex < 0) throw new IllegalArgumentException("negative pointer");
    }

    private static void requireCredential(String type, byte[] hash) {
        if (!"key".equals(type) && !"script".equals(type)) {
            throw new IllegalArgumentException("unsupported stake credential type: " + type);
        }
        if (hash == null || hash.length != 28) throw new IllegalArgumentException("stake credential must be 28 bytes");
    }
}
