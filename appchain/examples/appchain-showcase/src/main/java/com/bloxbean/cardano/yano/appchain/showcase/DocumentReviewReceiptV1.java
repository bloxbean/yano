package com.bloxbean.cardano.yano.appchain.showcase;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

/** Application-owned proof that one approval was consumed by one document append. */
public record DocumentReviewReceiptV1(
        String proposalId,
        String documentEntityId,
        byte[] actionCommitment,
        String policyId,
        long policyRevision,
        long appliedHeight,
        byte[] messageId
) {
    public DocumentReviewReceiptV1 {
        actionCommitment = actionCommitment.clone();
        messageId = messageId.clone();
        if (actionCommitment.length != 32 || messageId.length != 32
                || policyRevision < 1 || appliedHeight < 1) {
            throw new IllegalArgumentException("invalid document-review receipt");
        }
    }

    @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
    @Override public byte[] messageId() { return messageId.clone(); }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(proposalId));
        value.add(new UnicodeString(documentEntityId));
        value.add(new ByteString(actionCommitment));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(policyRevision));
        value.add(new UnsignedInteger(appliedHeight));
        value.add(new ByteString(messageId));
        return CborSerializationUtil.serialize(value);
    }
}
