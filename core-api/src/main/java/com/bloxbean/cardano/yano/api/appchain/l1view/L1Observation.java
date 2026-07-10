package com.bloxbean.cardano.yano.api.appchain.l1view;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * One L1 observation (ADR app-layer/008.4 §3.1): a pointer + claim emitted
 * by a configured {@link L1Observer} on the {@code ~l1/<observer-id>} topic
 * and sequenced into app blocks like any message. The pointer names an L1
 * transaction; the claim is observer-specific CBOR that every member can
 * recompute from its own L1 view — follower verification is
 * consensus-critical (mismatch = proposal rejected).
 *
 * <p>Wire format (CBOR, see {@code core-api/src/main/cddl/appchain/
 * l1-observation-v1.cddl}):
 * {@code [1, observer-id, tx-hash, slot, block-hash, claim]}
 *
 * @param observerId configured observer instance id (topic suffix)
 * @param txHash     L1 transaction hash (32B) the claim points at
 * @param slot       L1 slot of the block containing the tx
 * @param blockHash  L1 block hash (32B) at that slot
 * @param claim      observer-specific claim CBOR
 */
public record L1Observation(String observerId,
                            byte[] txHash,
                            long slot,
                            byte[] blockHash,
                            byte[] claim) {

    public static final int WIRE_VERSION = 1;
    /** Reserved topic prefix for observation messages. */
    public static final String TOPIC_PREFIX = "~l1/";

    public L1Observation {
        Objects.requireNonNull(observerId, "observerId");
        Objects.requireNonNull(txHash, "txHash");
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(claim, "claim");
    }

    public String topic() {
        return TOPIC_PREFIX + observerId;
    }

    public byte[] encode() {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(WIRE_VERSION));
            array.add(new UnicodeString(observerId));
            array.add(new ByteString(txHash));
            array.add(new UnsignedInteger(BigInteger.valueOf(slot)));
            array.add(new ByteString(blockHash));
            array.add(new ByteString(claim));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(array);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("L1 observation encoding failed", e);
        }
    }

    /**
     * @return the decoded observation, or null when the body is not a valid
     * v1 observation (callers treat undecodable observation messages as
     * verification failures)
     */
    public static L1Observation decode(byte[] body) {
        try {
            List<DataItem> items = CborDecoder.decode(body);
            Array array = (Array) items.get(0);
            List<DataItem> fields = array.getDataItems();
            long version = ((UnsignedInteger) fields.get(0)).getValue().longValueExact();
            if (version != WIRE_VERSION)
                return null;
            return new L1Observation(
                    ((UnicodeString) fields.get(1)).getString(),
                    ((ByteString) fields.get(2)).getBytes(),
                    ((UnsignedInteger) fields.get(3)).getValue().longValueExact(),
                    ((ByteString) fields.get(4)).getBytes(),
                    ((ByteString) fields.get(5)).getBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable identity for dedup/verification windows. */
    public String key() {
        return observerId + '/' + com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(txHash)
                + '/' + slot;
    }
}
