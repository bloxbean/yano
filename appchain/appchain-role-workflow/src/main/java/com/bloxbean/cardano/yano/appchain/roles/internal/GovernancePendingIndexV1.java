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

/** Canonical bounded enumeration of pending actor-governed mutations. */
record GovernancePendingIndexV1(List<Entry> entries) {
    GovernancePendingIndexV1 {
        if (entries == null || entries.size() > RoleWorkflowLimits.MAX_PENDING_MUTATIONS) {
            throw new IllegalArgumentException("invalid governance pending index");
        }
        entries = entries.stream().sorted(Comparator.comparing(Entry::mutationId)).toList();
        if (entries.stream().map(Entry::mutationId).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("duplicate governance pending index entry");
        }
    }

    static GovernancePendingIndexV1 empty() {
        return new GovernancePendingIndexV1(List.of());
    }

    GovernancePendingIndexV1 add(Entry entry) {
        if (entries.stream().anyMatch(existing ->
                existing.mutationId().equals(entry.mutationId()))) {
            throw new IllegalStateException("governance pending index already contains mutation");
        }
        List<Entry> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new GovernancePendingIndexV1(updated);
    }

    GovernancePendingIndexV1 remove(String mutationId) {
        List<Entry> updated = entries.stream()
                .filter(entry -> !entry.mutationId().equals(mutationId)).toList();
        if (updated.size() == entries.size()) {
            throw new IllegalStateException("governance pending index is missing mutation");
        }
        return new GovernancePendingIndexV1(updated);
    }

    byte[] encode() {
        Array items = new Array();
        for (Entry entry : entries) {
            Array item = new Array();
            item.add(new UnicodeString(entry.mutationId()));
            item.add(new UnsignedInteger(entry.expiryHeight()));
            item.add(new UnicodeString(entry.authorityId()));
            item.add(new UnsignedInteger(entry.authorityRevision()));
            item.add(new UnicodeString(entry.proposerActorId()));
            items.add(item);
        }
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(items);
        return RoleWorkflowCbor.encode(value);
    }

    static GovernancePendingIndexV1 decode(byte[] bytes) {
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
                    values.get(1), RoleWorkflowLimits.MAX_PENDING_MUTATIONS);
            List<Entry> entries = items.getDataItems().stream().map(item -> {
                List<co.nstant.in.cbor.model.DataItem> fields =
                        RoleWorkflowCbor.array(item, 5).getDataItems();
                return new Entry(RoleWorkflowCbor.text(fields.get(0)),
                        RoleWorkflowCbor.uint(fields.get(1)),
                        RoleWorkflowCbor.text(fields.get(2)),
                        RoleWorkflowCbor.uint(fields.get(3)),
                        RoleWorkflowCbor.text(fields.get(4)));
            }).toList();
            GovernancePendingIndexV1 decoded = new GovernancePendingIndexV1(entries);
            RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
            return decoded;
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt governance pending index", corrupt);
        }
    }

    record Entry(
            String mutationId,
            long expiryHeight,
            String authorityId,
            long authorityRevision,
            String proposerActorId
    ) {
        Entry {
            mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
            authorityId = RoleWorkflowIdentifiers.id(authorityId, "authorityId");
            proposerActorId = RoleWorkflowIdentifiers.id(
                    proposerActorId, "proposerActorId");
            if (expiryHeight < 1 || authorityRevision < 1) {
                throw new IllegalArgumentException("invalid governance pending entry");
            }
        }
    }
}
