package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Objects;

/** Canonical committed representation of one unspent Cardano-shaped output. */
public record EutxoRecord(
        EutxoOutpoint outpoint,
        String address,
        byte[] outputCbor,
        Origin origin
) {
    public enum Origin {
        GENESIS,
        TRANSACTION,
        L1_DEPOSIT
    }

    public EutxoRecord {
        Objects.requireNonNull(outpoint, "outpoint");
        address = Objects.requireNonNull(address, "address").trim();
        if (address.isEmpty() || address.length() > 256) {
            throw new IllegalArgumentException("address must contain 1-256 characters");
        }
        outputCbor = Objects.requireNonNull(outputCbor, "outputCbor").clone();
        if (outputCbor.length == 0 || outputCbor.length > EutxoProfile.V1.maxOutputCborBytes()) {
            throw new IllegalArgumentException("output CBOR is empty or exceeds the profile bound");
        }
        Objects.requireNonNull(origin, "origin");
    }

    @Override
    public byte[] outputCbor() {
        return outputCbor.clone();
    }

    public byte[] encode() {
        return EutxoCbor.encodeRecord(this);
    }

    public static EutxoRecord decode(byte[] bytes) {
        return EutxoCbor.decodeRecord(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoRecord record
                && outpoint.equals(record.outpoint)
                && address.equals(record.address)
                && java.util.Arrays.equals(outputCbor, record.outputCbor)
                && origin == record.origin;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(outpoint, address, origin);
        return 31 * result + java.util.Arrays.hashCode(outputCbor);
    }
}
