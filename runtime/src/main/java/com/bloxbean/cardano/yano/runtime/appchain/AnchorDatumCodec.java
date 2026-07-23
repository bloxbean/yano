package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-chain codec for the anchor datum (ABI:
 * {@code core-api/src/main/cddl/appchain/anchor-v1.cddl}). Wire format is
 * {@code Constr(0, [version, chain-id, height, block-hash, state-root,
 * member-keys, threshold])} — the same encoding the julc record codec and
 * Aiken types produce, and what the conformance vectors pin down.
 */
final class AnchorDatumCodec {

    static final long ABI_VERSION = AnchorDatumV1.ABI_VERSION;

    /**
     * @param version    ABI version (1)
     * @param chainId    app-chain id (UTF-8 bytes on the wire)
     * @param height     app-block height this anchor attests
     * @param blockHash  blake2b-256 app-block hash at height (32B)
     * @param stateRoot  MPF root bound by that block (32B)
     * @param memberKeys Ed25519 member public keys (32B each), sorted ascending
     * @param threshold  advance threshold (1..len(memberKeys))
     */
    record AnchorDatum(long version,
                       String chainId,
                       long height,
                       byte[] blockHash,
                       byte[] stateRoot,
                       List<byte[]> memberKeys,
                       long threshold) {
    }

    private AnchorDatumCodec() {
    }

    static ConstrPlutusData encode(AnchorDatum datum) {
        if (datum.version() != ABI_VERSION) {
            throw new IllegalArgumentException("Unsupported anchor datum version");
        }
        AnchorDatumV1 publicDatum = new AnchorDatumV1(datum.chainId(), datum.height(),
                datum.blockHash(), datum.stateRoot(), datum.memberKeys(),
                Math.toIntExact(datum.threshold()));
        try {
            return (ConstrPlutusData) PlutusData.deserialize(publicDatum.encode());
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Anchor datum encode failed", malformed);
        }
    }

    static AnchorDatum decode(PlutusData data) {
        if (data == null) {
            throw new IllegalArgumentException("Anchor datum is required");
        }
        return decode(data.serializeToBytes());
    }

    /** Decode untrusted L1 inline-datum bytes through the bounded public codec. */
    static AnchorDatum decode(byte[] cbor) {
        AnchorDatumV1 decoded = AnchorDatumV1.decode(cbor);
        return new AnchorDatum(ABI_VERSION, decoded.chainId(), decoded.height(),
                decoded.blockHash(), decoded.stateRoot(), decoded.memberKeys(),
                decoded.threshold());
    }

    /** Canonical member-key order (bytewise ascending, unsigned) — ABI requirement. */
    static List<byte[]> sortedKeys(List<byte[]> keys) {
        List<byte[]> sorted = new ArrayList<>(keys);
        sorted.sort(java.util.Arrays::compareUnsigned);
        return sorted;
    }

}
