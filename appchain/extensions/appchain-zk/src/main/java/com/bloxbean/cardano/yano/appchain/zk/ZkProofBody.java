package com.bloxbean.cardano.yano.appchain.zk;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * In-body proof envelope for E7.1 (ADR app-layer/006). The framework stays
 * blob-first — the ZK proof rides inside the opaque message body as an
 * application convention, parsed only by this plugin. See
 * {@code src/main/cddl/zk-proof-body.cddl}.
 * <p>
 * CBOR: {@code [circuitId(tstr), proofSystem(tstr), curve(tstr), proof(bstr),
 * [* bstr]]} where each trailing byte string is a big-endian unsigned public
 * input (a field element). Public inputs are byte strings (not integers) to
 * avoid CBOR bignum ambiguity for values wider than 64 bits.
 */
public record ZkProofBody(String circuitId,
                          String proofSystem,
                          String curve,
                          byte[] proof,
                          List<BigInteger> publicInputs) {

    public static ZkProofBody decode(byte[] body) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
        if (items.size() != 5) {
            throw new IllegalArgumentException("zk proof body must have 5 elements, got " + items.size());
        }
        String circuitId = ((UnicodeString) items.get(0)).getString();
        String proofSystem = ((UnicodeString) items.get(1)).getString();
        String curve = ((UnicodeString) items.get(2)).getString();
        byte[] proof = ((ByteString) items.get(3)).getBytes();
        if (circuitId.isBlank() || proofSystem.isBlank() || curve.isBlank() || proof.length == 0) {
            throw new IllegalArgumentException("zk proof body has empty required field");
        }
        List<BigInteger> publicInputs = new ArrayList<>();
        for (DataItem input : ((Array) items.get(4)).getDataItems()) {
            publicInputs.add(new BigInteger(1, ((ByteString) input).getBytes()));
        }
        return new ZkProofBody(circuitId, proofSystem, curve, proof, publicInputs);
    }

    public byte[] encode() {
        Array root = new Array();
        root.add(new UnicodeString(circuitId));
        root.add(new UnicodeString(proofSystem));
        root.add(new UnicodeString(curve));
        root.add(new ByteString(proof));
        Array inputs = new Array();
        for (BigInteger input : publicInputs) {
            inputs.add(new ByteString(toUnsignedBytes(input)));
        }
        root.add(inputs);
        return CborSerializationUtil.serialize(root);
    }

    /** True if the body plausibly is a zk proof envelope (cheap pre-check). */
    public static boolean looksLikeProof(byte[] body) {
        try {
            decode(body);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // strip a leading sign byte if present
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
