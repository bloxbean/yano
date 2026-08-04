package com.bloxbean.cardano.yano.appchain.roles.internal;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Canonical bounded enumeration of pending role-approval proposals. */
record ApprovalPendingIndexV1(List<Entry> entries) {
    ApprovalPendingIndexV1 {
        if (entries == null || entries.size() > RoleWorkflowLimits.MAX_PENDING_PROPOSALS) {
            throw new IllegalArgumentException("invalid approval pending index");
        }
        entries = entries.stream().sorted(Comparator.comparing(Entry::proposalId)).toList();
        if (entries.stream().map(Entry::proposalId).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("duplicate approval pending index entry");
        }
    }

    static ApprovalPendingIndexV1 empty() {
        return new ApprovalPendingIndexV1(List.of());
    }

    ApprovalPendingIndexV1 add(Entry entry) {
        if (entries.stream().anyMatch(existing ->
                existing.proposalId().equals(entry.proposalId()))) {
            throw new IllegalStateException("approval pending index already contains proposal");
        }
        List<Entry> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new ApprovalPendingIndexV1(updated);
    }

    ApprovalPendingIndexV1 remove(String proposalId) {
        List<Entry> updated = entries.stream()
                .filter(entry -> !entry.proposalId().equals(proposalId)).toList();
        if (updated.size() == entries.size()) {
            throw new IllegalStateException("approval pending index is missing proposal");
        }
        return new ApprovalPendingIndexV1(updated);
    }

    byte[] encode() {
        Array items = new Array();
        for (Entry entry : entries) {
            Array item = new Array();
            item.add(new UnicodeString(entry.proposalId()));
            item.add(new UnsignedInteger(entry.deadlineHeight()));
            item.add(new UnicodeString(entry.policyId()));
            item.add(new UnicodeString(entry.proposerActorId()));
            items.add(item);
        }
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(items);
        return RoleWorkflowCbor.encode(value);
    }

    static ApprovalPendingIndexV1 decode(byte[] bytes) {
        try {
            List<co.nstant.in.cbor.model.DataItem> values =
                    RoleWorkflowCbor.decodeArray(bytes, 2,
                            RoleWorkflowLimits.MAX_PENDING_INDEX_BYTES,
                            RoleWorkflowLimits.MAX_PENDING_INDEX_CBOR_ITEMS)
                            .getDataItems();
            if (RoleWorkflowCbor.uint(values.get(0)) != 1) {
                throw new IllegalArgumentException();
            }
            Array items = RoleWorkflowCbor.array(
                    values.get(1), RoleWorkflowLimits.MAX_PENDING_PROPOSALS);
            List<Entry> entries = items.getDataItems().stream().map(item -> {
                List<co.nstant.in.cbor.model.DataItem> fields =
                        RoleWorkflowCbor.array(item, 4).getDataItems();
                return new Entry(RoleWorkflowCbor.text(fields.get(0)),
                        RoleWorkflowCbor.uint(fields.get(1)),
                        RoleWorkflowCbor.text(fields.get(2)),
                        RoleWorkflowCbor.text(fields.get(3)));
            }).toList();
            ApprovalPendingIndexV1 decoded = new ApprovalPendingIndexV1(entries);
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt approval pending index", corrupt);
        }
    }

    record Entry(
            String proposalId,
            long deadlineHeight,
            String policyId,
            String proposerActorId
    ) {
        Entry {
            proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            proposerActorId = RoleWorkflowIdentifiers.id(
                    proposerActorId, "proposerActorId");
            if (deadlineHeight < 1) {
                throw new IllegalArgumentException("invalid approval pending entry");
            }
        }
    }
}
