package com.bloxbean.cardano.yano.appchain.evidence.profile.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Application-owned receipt proving one role approval was consumed by an evidence release. */
public record EvidenceApprovalConsumptionV1(
        String proposalId,
        String releaseId,
        byte[] actionCommitment,
        String policyId,
        long policyRevision,
        long appliedHeight,
        byte[] messageId
) {
    public static final int VERSION = 1;
    private static final int MAX_ENCODED_BYTES = 512;

    public EvidenceApprovalConsumptionV1 {
        proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        releaseId = RoleWorkflowIdentifiers.id(releaseId, "releaseId");
        actionCommitment = exact32(actionCommitment, "actionCommitment");
        policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
        messageId = exact32(messageId, "messageId");
        if (policyRevision < 1 || appliedHeight < 1) {
            throw new IllegalArgumentException("receipt revisions and heights must be positive");
        }
    }

    @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
    @Override public byte[] messageId() { return messageId.clone(); }

    /** Encodes {@code [1, proposal, release, action32, policy, revision, height, messageId32]}. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(VERSION));
        root.add(new UnicodeString(proposalId));
        root.add(new UnicodeString(releaseId));
        root.add(new ByteString(actionCommitment));
        root.add(new UnicodeString(policyId));
        root.add(new UnsignedInteger(policyRevision));
        root.add(new UnsignedInteger(appliedHeight));
        root.add(new ByteString(messageId));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    public static EvidenceApprovalConsumptionV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, MAX_ENCODED_BYTES, 8);
        List<DataItem> fields = CanonicalCbor.items(root);
        EvidenceApprovalConsumptionV1 value = new EvidenceApprovalConsumptionV1(
                CanonicalCbor.text(fields.get(1)), CanonicalCbor.text(fields.get(2)),
                CanonicalCbor.bytes(fields.get(3)), CanonicalCbor.text(fields.get(4)),
                CanonicalCbor.uint(fields.get(5)), CanonicalCbor.uint(fields.get(6)),
                CanonicalCbor.bytes(fields.get(7)));
        if (!Arrays.equals(encoded, value.encode())) {
            throw new IllegalArgumentException("non-canonical evidence approval consumption");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceApprovalConsumptionV1 receipt
                && policyRevision == receipt.policyRevision
                && appliedHeight == receipt.appliedHeight
                && proposalId.equals(receipt.proposalId)
                && releaseId.equals(receipt.releaseId)
                && Arrays.equals(actionCommitment, receipt.actionCommitment)
                && policyId.equals(receipt.policyId)
                && Arrays.equals(messageId, receipt.messageId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(proposalId, releaseId, policyId,
                policyRevision, appliedHeight);
        result = 31 * result + Arrays.hashCode(actionCommitment);
        return 31 * result + Arrays.hashCode(messageId);
    }

    private static byte[] exact32(byte[] value, String field) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(field + " must be 32 bytes");
        }
        return value.clone();
    }
}
