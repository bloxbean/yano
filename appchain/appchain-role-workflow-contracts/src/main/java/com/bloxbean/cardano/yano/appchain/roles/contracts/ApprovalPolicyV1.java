package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/** Frozen bounded AND-of-clauses policy contract. */
public record ApprovalPolicyV1(String policyId, long revision, List<String> proposerRoles,
                               List<RequiredClause> clauses, RejectionMode rejectionMode,
                               long maximumLifetimeBlocks) {
    public ApprovalPolicyV1 {
        policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
        if (revision < 1 || proposerRoles == null
                || proposerRoles.size() > RoleWorkflowLimits.MAX_PROPOSER_ROLES
                || clauses == null || clauses.isEmpty()
                || clauses.size() > RoleWorkflowLimits.MAX_CLAUSES_PER_POLICY
                || rejectionMode == null || maximumLifetimeBlocks < 1
                || maximumLifetimeBlocks > 1_000_000) {
            throw OrganizationRecordV1.invalid();
        }
        proposerRoles = proposerRoles.stream().map(RoleWorkflowIdentifiers::role).sorted().toList();
        if (proposerRoles.stream().distinct().count() != proposerRoles.size()) {
            throw OrganizationRecordV1.invalid();
        }
        clauses = clauses.stream().sorted(Comparator.comparing(RequiredClause::clauseId)).toList();
        if (clauses.stream().map(RequiredClause::clauseId).distinct().count() != clauses.size()
                || clauses.stream().mapToInt(RequiredClause::minimumCount).sum()
                > RoleWorkflowLimits.MAX_DECISIONS_PER_PROPOSAL) {
            throw OrganizationRecordV1.invalid();
        }
    }

    @Override public List<String> proposerRoles() { return List.copyOf(proposerRoles); }
    @Override public List<RequiredClause> clauses() { return List.copyOf(clauses); }

    public RequiredClause clause(String clauseId) {
        return clauses.stream().filter(clause -> clause.clauseId().equals(clauseId))
                .findFirst().orElse(null);
    }

    public byte[] encode() {
        Array proposerValues = new Array();
        proposerRoles.forEach(role -> proposerValues.add(new UnicodeString(role)));
        Array clauseValues = new Array();
        clauses.forEach(clause -> clauseValues.add(clause.toCbor()));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(policyId));
        value.add(new UnsignedInteger(revision));
        value.add(proposerValues);
        value.add(clauseValues);
        value.add(new UnsignedInteger(rejectionMode.code));
        value.add(new UnsignedInteger(maximumLifetimeBlocks));
        return RoleWorkflowCbor.encode(value);
    }

    public byte[] digest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static ApprovalPolicyV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 7).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array proposers = RoleWorkflowCbor.array(values.get(3),
                RoleWorkflowLimits.MAX_PROPOSER_ROLES);
        Array clauses = RoleWorkflowCbor.array(values.get(4),
                RoleWorkflowLimits.MAX_CLAUSES_PER_POLICY);
        ApprovalPolicyV1 decoded = new ApprovalPolicyV1(RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.uint(values.get(2)),
                proposers.getDataItems().stream().map(RoleWorkflowCbor::text).toList(),
                clauses.getDataItems().stream().map(RequiredClause::fromCbor).toList(),
                RejectionMode.fromCode(RoleWorkflowCbor.uintInt(values.get(5))),
                RoleWorkflowCbor.uint(values.get(6)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    public record RequiredClause(String clauseId, String role, int minimumCount,
                                 DistinctBy distinctBy) {
        public RequiredClause {
            clauseId = RoleWorkflowIdentifiers.id(clauseId, "clauseId");
            role = RoleWorkflowIdentifiers.role(role);
            if (minimumCount < 1 || minimumCount > RoleWorkflowLimits.MAX_DECISIONS_PER_PROPOSAL
                    || distinctBy == null) throw OrganizationRecordV1.invalid();
        }

        Array toCbor() {
            Array value = new Array();
            value.add(new UnicodeString(clauseId));
            value.add(new UnicodeString(role));
            value.add(new UnsignedInteger(minimumCount));
            value.add(new UnsignedInteger(distinctBy.code));
            return value;
        }

        static RequiredClause fromCbor(co.nstant.in.cbor.model.DataItem value) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.array(value, 4).getDataItems();
            if (values.size() != 4) throw OrganizationRecordV1.invalid();
            return new RequiredClause(RoleWorkflowCbor.text(values.get(0)),
                    RoleWorkflowCbor.text(values.get(1)),
                    RoleWorkflowCbor.uintInt(values.get(2)),
                    DistinctBy.fromCode(RoleWorkflowCbor.uintInt(values.get(3))));
        }
    }

    public enum DistinctBy {
        ACTOR(0), ORGANIZATION(1);
        private final int code;
        DistinctBy(int code) { this.code = code; }
        public int code() { return code; }
        static DistinctBy fromCode(int code) {
            for (DistinctBy value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }

    public enum RejectionMode {
        DISABLED(0), ANY_ELIGIBLE(1);
        private final int code;
        RejectionMode(int code) { this.code = code; }
        public int code() { return code; }
        static RejectionMode fromCode(int code) {
            for (RejectionMode value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }
}
