package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-width L1 publication for the ordered transaction inventory of a b16
 * proof.
 *
 * <p>The manifest is deliberately small enough to publish as one inline datum.
 * Full finalized-transition bytes remain in the retained data-availability
 * bundle and are reconstructed against these ordered identities.</p>
 */
public record EutxoZkBatchManifest(List<String> transactionIds) {
    private static final int VERSION = 1;
    private static final int MAXIMUM_TRANSACTIONS = 16;
    private static final BigInteger BLS12_381_SCALAR_FIELD =
            new BigInteger(
                    "52435875175126190479447740508185965837690552500527637822603658699938581184513");

    public static final int CANONICAL_BYTES =
            Integer.BYTES + 1 + MAXIMUM_TRANSACTIONS * 32;

    public EutxoZkBatchManifest {
        transactionIds = List.copyOf(Objects.requireNonNull(
                transactionIds, "transactionIds"));
        if (transactionIds.isEmpty()
                || transactionIds.size() > MAXIMUM_TRANSACTIONS
                || transactionIds.stream().anyMatch(id ->
                id == null || !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "batch manifest requires 1-16 transaction ids");
        }
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            output.writeByte(transactionIds.size());
            for (int index = 0;
                 index < MAXIMUM_TRANSACTIONS;
                 index++) {
                byte[] id = index < transactionIds.size()
                        ? HexFormat.of().parseHex(transactionIds.get(index))
                        : new byte[32];
                output.write(id);
            }
        });
    }

    public byte[] commitment() {
        return Blake2bUtil.blake2bHash256(canonicalBytes());
    }

    public BigInteger commitmentScalar() {
        return new BigInteger(1, commitment())
                .mod(BLS12_381_SCALAR_FIELD);
    }

    public static EutxoZkBatchManifest decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length != CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                    "invalid canonical batch-manifest length");
        }
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported batch-manifest version");
            }
            int count = input.readUnsignedByte();
            if (count < 1 || count > MAXIMUM_TRANSACTIONS) {
                throw new IllegalArgumentException(
                        "invalid batch-manifest transaction count");
            }
            List<String> ids = new ArrayList<>(count);
            for (int index = 0;
                 index < MAXIMUM_TRANSACTIONS;
                 index++) {
                byte[] id = input.readNBytes(32);
                if (id.length != 32) {
                    throw new IllegalArgumentException(
                            "truncated batch-manifest transaction id");
                }
                if (index < count) {
                    ids.add(HexFormat.of().formatHex(id));
                } else if (!java.util.Arrays.equals(id, new byte[32])) {
                    throw new IllegalArgumentException(
                            "non-zero batch-manifest padding");
                }
            }
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkBatchManifest(ids);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid canonical batch manifest", exception);
        }
    }
}
