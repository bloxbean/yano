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
 * In-body payload for E7.3 anonymous membership submissions (ADR app-layer/006).
 * The author proves "I am one of the registered members" (a membership circuit
 * over the member-key Merkle root) and binds a one-time {@code nullifier} for a
 * {@code context}, without revealing which member. The {@code payload} is the
 * anonymous message content.
 * <p>
 * CBOR: {@code [circuitId(tstr), proofSystem(tstr), curve(tstr), proof(bstr),
 * [* bstr publicInput], nullifier(bstr), context(bstr), payload(bstr)]}.
 */
public record MembershipProofBody(String circuitId,
                                  String proofSystem,
                                  String curve,
                                  byte[] proof,
                                  List<BigInteger> publicInputs,
                                  byte[] nullifier,
                                  byte[] context,
                                  byte[] payload) {

    /** The proof portion, reusing the E7.1 verification path. */
    ZkProofBody proofBody() {
        return new ZkProofBody(circuitId, proofSystem, curve, proof, publicInputs);
    }

    public static MembershipProofBody decode(byte[] body) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
        if (items.size() != 8) {
            throw new IllegalArgumentException("membership body must have 8 elements, got " + items.size());
        }
        String circuitId = ((UnicodeString) items.get(0)).getString();
        String proofSystem = ((UnicodeString) items.get(1)).getString();
        String curve = ((UnicodeString) items.get(2)).getString();
        byte[] proof = ((ByteString) items.get(3)).getBytes();
        List<BigInteger> publicInputs = new ArrayList<>();
        for (DataItem input : ((Array) items.get(4)).getDataItems()) {
            publicInputs.add(new BigInteger(1, ((ByteString) input).getBytes()));
        }
        byte[] nullifier = ((ByteString) items.get(5)).getBytes();
        byte[] context = ((ByteString) items.get(6)).getBytes();
        byte[] payload = ((ByteString) items.get(7)).getBytes();
        if (circuitId.isBlank() || proof.length == 0 || nullifier.length == 0) {
            throw new IllegalArgumentException("membership body has empty required field");
        }
        return new MembershipProofBody(circuitId, proofSystem, curve, proof, publicInputs,
                nullifier, context, payload);
    }

    public byte[] encode() {
        Array root = new Array();
        root.add(new UnicodeString(circuitId));
        root.add(new UnicodeString(proofSystem));
        root.add(new UnicodeString(curve));
        root.add(new ByteString(proof));
        Array inputs = new Array();
        for (BigInteger input : publicInputs) {
            inputs.add(new ByteString(toUnsigned(input)));
        }
        root.add(inputs);
        root.add(new ByteString(nullifier));
        root.add(new ByteString(context != null ? context : new byte[0]));
        root.add(new ByteString(payload != null ? payload : new byte[0]));
        return CborSerializationUtil.serialize(root);
    }

    private static byte[] toUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
