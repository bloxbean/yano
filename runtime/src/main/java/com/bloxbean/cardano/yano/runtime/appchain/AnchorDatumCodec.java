package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.nio.charset.StandardCharsets;
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

    static final long ABI_VERSION = 1;

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
        ListPlutusData members = ListPlutusData.builder().build();
        for (byte[] key : sortedKeys(datum.memberKeys())) {
            members.add(BytesPlutusData.of(key));
        }
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(datum.version()),
                        BytesPlutusData.of(datum.chainId().getBytes(StandardCharsets.UTF_8)),
                        BigIntPlutusData.of(datum.height()),
                        BytesPlutusData.of(datum.blockHash()),
                        BytesPlutusData.of(datum.stateRoot()),
                        members,
                        BigIntPlutusData.of(datum.threshold())))
                .build();
    }

    static AnchorDatum decode(PlutusData data) {
        if (!(data instanceof ConstrPlutusData constr) || constr.getAlternative() != 0)
            throw new IllegalArgumentException("Anchor datum must be Constr(0, [...])");
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        if (fields.size() != 7)
            throw new IllegalArgumentException("Anchor datum must have 7 fields, got " + fields.size());
        List<byte[]> memberKeys = new ArrayList<>();
        for (PlutusData member : asList(fields.get(5)).getPlutusDataList()) {
            memberKeys.add(asBytes(member, "member-key"));
        }
        return new AnchorDatum(
                asLong(fields.get(0), "version"),
                new String(asBytes(fields.get(1), "chain-id"), StandardCharsets.UTF_8),
                asLong(fields.get(2), "height"),
                asBytes(fields.get(3), "block-hash"),
                asBytes(fields.get(4), "state-root"),
                memberKeys,
                asLong(fields.get(6), "threshold"));
    }

    /** Canonical member-key order (bytewise ascending, unsigned) — ABI requirement. */
    static List<byte[]> sortedKeys(List<byte[]> keys) {
        List<byte[]> sorted = new ArrayList<>(keys);
        sorted.sort(java.util.Arrays::compareUnsigned);
        return sorted;
    }

    private static long asLong(PlutusData data, String field) {
        if (data instanceof BigIntPlutusData intData)
            return intData.getValue().longValueExact();
        throw new IllegalArgumentException("Anchor datum field " + field + " must be an integer");
    }

    private static byte[] asBytes(PlutusData data, String field) {
        if (data instanceof BytesPlutusData bytesData)
            return bytesData.getValue();
        throw new IllegalArgumentException("Anchor datum field " + field + " must be bytes");
    }

    private static ListPlutusData asList(PlutusData data) {
        if (data instanceof ListPlutusData listData)
            return listData;
        throw new IllegalArgumentException("Anchor datum member-keys must be a list");
    }
}
