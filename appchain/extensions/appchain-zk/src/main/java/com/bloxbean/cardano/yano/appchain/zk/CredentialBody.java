package com.bloxbean.cardano.yano.appchain.zk;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * On-chain body of a BBS-signed credential (ADR app-layer/006 E7.2). Stored as
 * an opaque app-message; the {@code credential-registry} machine verifies the
 * issuer's BBS signature at admission. A holder later derives a selective
 * disclosure from the same attribute set + signature, verified client-side
 * against the issuer key and the anchored record.
 * <p>
 * CBOR: {@code [issuerId(tstr), credentialId(tstr), signature(bstr),
 * header(bstr), [* attribute(bstr)]]}.
 */
public record CredentialBody(String issuerId,
                             String credentialId,
                             byte[] signature,
                             byte[] header,
                             List<byte[]> attributes) {

    public static CredentialBody decode(byte[] body) {
        ZkCbor.requireBody(body);
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
        if (items.size() != 5) {
            throw new IllegalArgumentException("credential body must have 5 elements, got " + items.size());
        }
        String issuerId = ((UnicodeString) items.get(0)).getString();
        String credentialId = ((UnicodeString) items.get(1)).getString();
        byte[] signature = ((ByteString) items.get(2)).getBytes();
        byte[] header = ((ByteString) items.get(3)).getBytes();
        if (issuerId.isBlank() || credentialId.isBlank() || signature.length == 0) {
            throw new IllegalArgumentException("credential body has empty required field");
        }
        List<byte[]> attributes = new ArrayList<>();
        for (DataItem attr : ((Array) items.get(4)).getDataItems()) {
            attributes.add(((ByteString) attr).getBytes());
        }
        return new CredentialBody(issuerId, credentialId, signature, header, attributes);
    }

    public byte[] encode() {
        Array root = new Array();
        root.add(new UnicodeString(issuerId));
        root.add(new UnicodeString(credentialId));
        root.add(new ByteString(signature));
        root.add(new ByteString(header != null ? header : new byte[0]));
        Array attrs = new Array();
        for (byte[] attribute : attributes) {
            attrs.add(new ByteString(attribute));
        }
        root.add(attrs);
        return CborSerializationUtil.serialize(root);
    }
}
