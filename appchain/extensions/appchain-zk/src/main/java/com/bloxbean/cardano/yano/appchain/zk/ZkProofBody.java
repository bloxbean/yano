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
        ZkCbor.requireBody(body);
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
            inputs.add(new ByteString(ZkBytes.toUnsigned(input)));
        }
        root.add(inputs);
        return CborSerializationUtil.serialize(root);
    }
}
