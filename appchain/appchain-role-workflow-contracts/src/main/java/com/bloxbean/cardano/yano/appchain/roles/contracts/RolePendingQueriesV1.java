package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.Comparator;
import java.util.List;

/** Canonical bounded pending-governance and pending-approval query contracts. */
public final class RolePendingQueriesV1 {
    private RolePendingQueriesV1() {
    }

    public record PageQuery(String afterId, int limit) {
        public PageQuery {
            afterId = afterId == null ? "" : afterId;
            if (!afterId.isEmpty()) RoleWorkflowIdentifiers.id(afterId, "afterId");
            if (limit < 1 || limit > RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE) {
                throw OrganizationRecordV1.invalid();
            }
        }

        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(1));
            value.add(new UnicodeString(afterId));
            value.add(new UnsignedInteger(limit));
            return RoleWorkflowCbor.encode(value);
        }

        public static PageQuery decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
            OrganizationRecordV1.requireVersion(values.get(0));
            PageQuery decoded = new PageQuery(RoleWorkflowCbor.text(values.get(1)),
                    RoleWorkflowCbor.uintInt(values.get(2)));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record ApprovalEntry(
            String proposalId,
            long deadlineHeight,
            String policyId,
            String proposerActorId
    ) {
        public ApprovalEntry {
            proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            proposerActorId = RoleWorkflowIdentifiers.id(
                    proposerActorId, "proposerActorId");
            if (deadlineHeight < 1) throw OrganizationRecordV1.invalid();
        }

        private byte[] encode() {
            Array value = new Array();
            value.add(new UnicodeString(proposalId));
            value.add(new UnsignedInteger(deadlineHeight));
            value.add(new UnicodeString(policyId));
            value.add(new UnicodeString(proposerActorId));
            return RoleWorkflowCbor.encode(value);
        }

        private static ApprovalEntry decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 4).getDataItems();
            ApprovalEntry decoded = new ApprovalEntry(
                    RoleWorkflowCbor.text(values.get(0)),
                    RoleWorkflowCbor.uint(values.get(1)),
                    RoleWorkflowCbor.text(values.get(2)),
                    RoleWorkflowCbor.text(values.get(3)));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record ApprovalPage(List<ApprovalEntry> entries, String nextAfterId) {
        public ApprovalPage {
            entries = entries == null ? null : entries.stream()
                    .sorted(Comparator.comparing(ApprovalEntry::proposalId)).toList();
            nextAfterId = nextAfterId == null ? "" : nextAfterId;
            if (entries == null || entries.size() > RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE) {
                throw OrganizationRecordV1.invalid();
            }
            if (entries.stream().map(ApprovalEntry::proposalId).distinct().count()
                    != entries.size()) {
                throw OrganizationRecordV1.invalid();
            }
            if (!nextAfterId.isEmpty()) {
                RoleWorkflowIdentifiers.id(nextAfterId, "nextAfterId");
                if (entries.isEmpty()
                        || !entries.getLast().proposalId().equals(nextAfterId)) {
                    throw OrganizationRecordV1.invalid();
                }
            }
        }

        @Override public List<ApprovalEntry> entries() { return List.copyOf(entries); }

        public byte[] encode() {
            Array items = new Array();
            entries.forEach(entry -> items.add(new ByteString(entry.encode())));
            Array value = new Array();
            value.add(new UnsignedInteger(1));
            value.add(items);
            value.add(new UnicodeString(nextAfterId));
            return RoleWorkflowCbor.encode(value);
        }

        public static ApprovalPage decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
            OrganizationRecordV1.requireVersion(values.get(0));
            Array items = RoleWorkflowCbor.array(
                    values.get(1), RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE);
            ApprovalPage decoded = new ApprovalPage(items.getDataItems().stream()
                    .map(RoleWorkflowCbor::bytes).map(ApprovalEntry::decode).toList(),
                    RoleWorkflowCbor.text(values.get(2)));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record GovernanceEntry(
            String mutationId,
            long expiryHeight,
            String authorityId,
            long authorityRevision,
            String proposerActorId
    ) {
        public GovernanceEntry {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            authorityId = RoleWorkflowIdentifiers.id(authorityId, "authorityId");
            proposerActorId = RoleWorkflowIdentifiers.id(
                    proposerActorId, "proposerActorId");
            if (expiryHeight < 1 || authorityRevision < 1) {
                throw OrganizationRecordV1.invalid();
            }
        }

        private byte[] encode() {
            Array value = new Array();
            value.add(new UnicodeString(mutationId));
            value.add(new UnsignedInteger(expiryHeight));
            value.add(new UnicodeString(authorityId));
            value.add(new UnsignedInteger(authorityRevision));
            value.add(new UnicodeString(proposerActorId));
            return RoleWorkflowCbor.encode(value);
        }

        private static GovernanceEntry decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 5).getDataItems();
            GovernanceEntry decoded = new GovernanceEntry(
                    RoleWorkflowCbor.text(values.get(0)),
                    RoleWorkflowCbor.uint(values.get(1)),
                    RoleWorkflowCbor.text(values.get(2)),
                    RoleWorkflowCbor.uint(values.get(3)),
                    RoleWorkflowCbor.text(values.get(4)));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record GovernancePage(
            List<GovernanceEntry> entries,
            String nextAfterId
    ) {
        public GovernancePage {
            entries = entries == null ? null : entries.stream()
                    .sorted(Comparator.comparing(GovernanceEntry::mutationId)).toList();
            nextAfterId = nextAfterId == null ? "" : nextAfterId;
            if (entries == null || entries.size() > RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE) {
                throw OrganizationRecordV1.invalid();
            }
            if (entries.stream().map(GovernanceEntry::mutationId).distinct().count()
                    != entries.size()) {
                throw OrganizationRecordV1.invalid();
            }
            if (!nextAfterId.isEmpty()) {
                RoleWorkflowIdentifiers.id(nextAfterId, "nextAfterId");
                if (entries.isEmpty()
                        || !entries.getLast().mutationId().equals(nextAfterId)) {
                    throw OrganizationRecordV1.invalid();
                }
            }
        }

        @Override public List<GovernanceEntry> entries() { return List.copyOf(entries); }

        public byte[] encode() {
            Array items = new Array();
            entries.forEach(entry -> items.add(new ByteString(entry.encode())));
            Array value = new Array();
            value.add(new UnsignedInteger(1));
            value.add(items);
            value.add(new UnicodeString(nextAfterId));
            return RoleWorkflowCbor.encode(value);
        }

        public static GovernancePage decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 3).getDataItems();
            OrganizationRecordV1.requireVersion(values.get(0));
            Array items = RoleWorkflowCbor.array(
                    values.get(1), RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE);
            GovernancePage decoded = new GovernancePage(items.getDataItems().stream()
                    .map(RoleWorkflowCbor::bytes).map(GovernanceEntry::decode).toList(),
                    RoleWorkflowCbor.text(values.get(2)));
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }
}
