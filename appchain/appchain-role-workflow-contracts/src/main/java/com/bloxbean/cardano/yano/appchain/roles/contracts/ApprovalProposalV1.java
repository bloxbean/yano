package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.Comparator;
import java.util.List;

/** Authenticated proposal and decision trail returned by exact queries/proofs. */
public record ApprovalProposalV1(String proposalId, String policyId, long policyRevision,
                                 byte[] policyDigest, String payloadDomain, byte[] payloadHash,
                                 long deadlineHeight, ProposalStatus status,
                                 String proposerActorId, String proposerOrganizationId,
                                 long proposerOrganizationRevision, String proposerRole,
                                 long proposerActorRevision,
                                 String proposerKeyId, long createdHeight,
                                 List<AcceptedDecisionV1> decisions) {
    public ApprovalProposalV1 {
        proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
        payloadDomain = RoleWorkflowIdentifiers.payloadDomain(payloadDomain);
        proposerActorId = RoleWorkflowIdentifiers.id(proposerActorId, "proposerActorId");
        proposerOrganizationId = RoleWorkflowIdentifiers.id(
                proposerOrganizationId, "proposerOrganizationId");
        proposerRole = RoleWorkflowIdentifiers.role(proposerRole);
        proposerKeyId = RoleWorkflowIdentifiers.id(proposerKeyId, "proposerKeyId");
        policyDigest = hash(policyDigest);
        payloadHash = hash(payloadHash);
        if (policyRevision < 1 || deadlineHeight < 1 || status == null
                || proposerOrganizationRevision < 1 || proposerActorRevision < 1
                || createdHeight < 1 || decisions == null
                || decisions.size() > RoleWorkflowLimits.MAX_DECISIONS_PER_PROPOSAL) {
            throw OrganizationRecordV1.invalid();
        }
        decisions = decisions.stream().sorted(Comparator
                .comparingLong(AcceptedDecisionV1::acceptedHeight)
                .thenComparing(AcceptedDecisionV1::actorId)).toList();
        if (decisions.stream().map(AcceptedDecisionV1::actorId).distinct().count()
                != decisions.size()) throw OrganizationRecordV1.invalid();
    }

    @Override public byte[] policyDigest() { return policyDigest.clone(); }
    @Override public byte[] payloadHash() { return payloadHash.clone(); }
    @Override public List<AcceptedDecisionV1> decisions() { return List.copyOf(decisions); }

    public byte[] encode() {
        Array decisionValues = new Array();
        decisions.forEach(decision -> decisionValues.add(decision.toCbor()));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(proposalId));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(policyRevision));
        value.add(new ByteString(policyDigest));
        value.add(new UnicodeString(payloadDomain));
        value.add(new ByteString(payloadHash));
        value.add(new UnsignedInteger(deadlineHeight));
        value.add(new UnsignedInteger(status.code));
        value.add(new UnicodeString(proposerActorId));
        value.add(new UnicodeString(proposerOrganizationId));
        value.add(new UnsignedInteger(proposerOrganizationRevision));
        value.add(new UnicodeString(proposerRole));
        value.add(new UnsignedInteger(proposerActorRevision));
        value.add(new UnicodeString(proposerKeyId));
        value.add(new UnsignedInteger(createdHeight));
        value.add(decisionValues);
        return RoleWorkflowCbor.encode(value);
    }

    public static ApprovalProposalV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 17).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array decisions = RoleWorkflowCbor.array(values.get(16),
                RoleWorkflowLimits.MAX_DECISIONS_PER_PROPOSAL);
        ApprovalProposalV1 decoded = new ApprovalProposalV1(RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.text(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                RoleWorkflowCbor.bytes(values.get(4), 32), RoleWorkflowCbor.text(values.get(5)),
                RoleWorkflowCbor.bytes(values.get(6), 32), RoleWorkflowCbor.uint(values.get(7)),
                ProposalStatus.fromCode(RoleWorkflowCbor.uintInt(values.get(8))),
                RoleWorkflowCbor.text(values.get(9)), RoleWorkflowCbor.text(values.get(10)),
                RoleWorkflowCbor.uint(values.get(11)), RoleWorkflowCbor.text(values.get(12)),
                RoleWorkflowCbor.uint(values.get(13)), RoleWorkflowCbor.text(values.get(14)),
                RoleWorkflowCbor.uint(values.get(15)),
                decisions.getDataItems().stream().map(AcceptedDecisionV1::fromCbor).toList());
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    public enum ProposalStatus {
        PENDING(0), APPROVED(1), REJECTED(2), CANCELLED(3), EXPIRED(4);
        private final int code;
        ProposalStatus(int code) { this.code = code; }
        public int code() { return code; }
        static ProposalStatus fromCode(int code) {
            for (ProposalStatus value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }

    public record AcceptedDecisionV1(ActorStatementV1.Action action, String actorId,
                                     String organizationId, long organizationRevision,
                                     String role, long actorRevision,
                                     String keyId, String clauseId, byte[] statementDigest,
                                     byte[] signature, long acceptedHeight) {
        public AcceptedDecisionV1 {
            if (action != ActorStatementV1.Action.APPROVE
                    && action != ActorStatementV1.Action.REJECT) {
                throw OrganizationRecordV1.invalid();
            }
            actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
            organizationId = RoleWorkflowIdentifiers.id(organizationId, "organizationId");
            role = RoleWorkflowIdentifiers.role(role);
            keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
            clauseId = RoleWorkflowIdentifiers.id(clauseId, "clauseId");
            statementDigest = hash(statementDigest);
            signature = signature != null ? signature.clone() : null;
            if (organizationRevision < 1 || actorRevision < 1
                    || signature == null || signature.length != 64
                    || acceptedHeight < 1) throw OrganizationRecordV1.invalid();
        }
        @Override public byte[] statementDigest() { return statementDigest.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
        Array toCbor() {
            Array value = new Array();
            value.add(new UnsignedInteger(action.code()));
            value.add(new UnicodeString(actorId));
            value.add(new UnicodeString(organizationId));
            value.add(new UnsignedInteger(organizationRevision));
            value.add(new UnicodeString(role));
            value.add(new UnsignedInteger(actorRevision));
            value.add(new UnicodeString(keyId));
            value.add(new UnicodeString(clauseId));
            value.add(new ByteString(statementDigest));
            value.add(new ByteString(signature));
            value.add(new UnsignedInteger(acceptedHeight));
            return value;
        }
        static AcceptedDecisionV1 fromCbor(co.nstant.in.cbor.model.DataItem value) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.array(value, 11).getDataItems();
            if (values.size() != 11) throw OrganizationRecordV1.invalid();
            return new AcceptedDecisionV1(ActorStatementV1.Action.fromCode(
                    RoleWorkflowCbor.uintInt(values.get(0))), RoleWorkflowCbor.text(values.get(1)),
                    RoleWorkflowCbor.text(values.get(2)), RoleWorkflowCbor.uint(values.get(3)),
                    RoleWorkflowCbor.text(values.get(4)), RoleWorkflowCbor.uint(values.get(5)),
                    RoleWorkflowCbor.text(values.get(6)), RoleWorkflowCbor.text(values.get(7)),
                    RoleWorkflowCbor.bytes(values.get(8), 32),
                    RoleWorkflowCbor.bytes(values.get(9), 64), RoleWorkflowCbor.uint(values.get(10)));
        }
    }

    private static byte[] hash(byte[] value) {
        if (value == null || value.length != 32) throw OrganizationRecordV1.invalid();
        return value.clone();
    }
}
