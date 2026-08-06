package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * ADR-UTXO-009 SP-M6: byte-exact off-chain twin of the on-chain
 * {@code NullifierShardValidator} datum
 * {@code Constr0[version=1, chainId, bridgeEpoch, shardIndex, nullifierRoot]}.
 * The settlement builder writes the continuing shard output with this datum
 * carrying the post-insert MPF root from
 * {@code NullifierShardMirror.planInserts}.
 */
public record EutxoShardDatum(
        int version,
        String chainId,
        long bridgeEpoch,
        int shardIndex,
        byte[] nullifierRoot
) {
    public static final int VERSION = 1;

    public EutxoShardDatum {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported shard datum version");
        }
        chainId = Objects.requireNonNull(chainId, "chainId").trim();
        if (chainId.isEmpty() || chainId.length() > 128) {
            throw new IllegalArgumentException(
                    "chain id must contain 1-128 characters");
        }
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        if (shardIndex < 0 || shardIndex >= 16) {
            throw new IllegalArgumentException("shard index must be 0..15");
        }
        nullifierRoot = Objects.requireNonNull(nullifierRoot, "nullifierRoot").clone();
        if (nullifierRoot.length != 32) {
            throw new IllegalArgumentException("nullifier root must be 32 bytes");
        }
    }

    @Override
    public byte[] nullifierRoot() {
        return nullifierRoot.clone();
    }

    /** The shard thread token name: the single index byte. */
    public byte[] threadTokenName() {
        return new byte[] {(byte) shardIndex};
    }

    public byte[] encode() {
        try {
            return ConstrPlutusData.of(0,
                            BigIntPlutusData.of(version),
                            BytesPlutusData.of(chainId.getBytes(StandardCharsets.UTF_8)),
                            BigIntPlutusData.of(bridgeEpoch),
                            BigIntPlutusData.of(shardIndex),
                            BytesPlutusData.of(nullifierRoot))
                    .serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot encode shard datum", failure);
        }
    }

    public static EutxoShardDatum decode(byte[] cbor) {
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constr)
                    || constr.getAlternative() != 0) {
                throw new IllegalArgumentException("shard datum must be Constr0");
            }
            ListPlutusData fields = constr.getData();
            if (fields.getPlutusDataList().size() != 5) {
                throw new IllegalArgumentException(
                        "shard datum must carry five fields");
            }
            return new EutxoShardDatum(
                    ((BigIntPlutusData) fields.getPlutusDataList().get(0))
                            .getValue().intValueExact(),
                    new String(((BytesPlutusData) fields.getPlutusDataList().get(1))
                            .getValue(), StandardCharsets.UTF_8),
                    ((BigIntPlutusData) fields.getPlutusDataList().get(2))
                            .getValue().longValueExact(),
                    ((BigIntPlutusData) fields.getPlutusDataList().get(3))
                            .getValue().intValueExact(),
                    ((BytesPlutusData) fields.getPlutusDataList().get(4))
                            .getValue());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid shard datum", failure);
        }
    }

    /** The datum after inserting a batch: same identity, new root. */
    public EutxoShardDatum withRoot(byte[] nextRoot) {
        return new EutxoShardDatum(
                version, chainId, bridgeEpoch, shardIndex, nextRoot);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoShardDatum datum
                && version == datum.version
                && chainId.equals(datum.chainId)
                && bridgeEpoch == datum.bridgeEpoch
                && shardIndex == datum.shardIndex
                && java.util.Arrays.equals(nullifierRoot, datum.nullifierRoot);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(version, chainId, bridgeEpoch, shardIndex)
                + java.util.Arrays.hashCode(nullifierRoot);
    }

    @Override
    public String toString() {
        return "EutxoShardDatum[shard=" + shardIndex + ", epoch=" + bridgeEpoch
                + ", root=" + java.util.HexFormat.of().formatHex(nullifierRoot) + "]";
    }
}
