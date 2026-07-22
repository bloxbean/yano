package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Version 1 commands and authenticated state projections for {@code approvals}. */
public final class ApprovalsContract {
    public static final String STATE_MACHINE_ID = "approvals";
    public static final String DEFAULT_TOPIC = "approvals.command.v1";
    public static final int OP_PROPOSE = 0;
    public static final int OP_APPROVE = 1;
    public static final int OP_REJECT = 2;

    private ApprovalsContract() {
    }

    public static byte[] propose(String itemId, byte[] payload, int required, long deadlineMillis) {
        requireItemId(itemId);
        if (required <= 0 || deadlineMillis < 0) throw new IllegalArgumentException("invalid proposal bounds");
        Array array = new Array();
        array.add(new UnsignedInteger(OP_PROPOSE));
        array.add(new UnicodeString(itemId));
        array.add(new ByteString(payload != null ? payload : new byte[0]));
        array.add(new UnsignedInteger(required));
        array.add(new UnsignedInteger(deadlineMillis));
        return StdlibContractCbor.encode(array);
    }

    public static byte[] approve(String itemId) { return decision(OP_APPROVE, itemId); }
    public static byte[] reject(String itemId) { return decision(OP_REJECT, itemId); }

    public static Command decodeCommand(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 2, 5).getDataItems();
        int op = StdlibContractCbor.uintInt(values.get(0));
        String itemId = StdlibContractCbor.text(values.get(1));
        requireItemId(itemId);
        if (op == OP_PROPOSE && values.size() == 5) {
            int required = StdlibContractCbor.uintInt(values.get(3));
            if (required <= 0) throw StdlibContractCbor.malformed();
            return new Command(op, itemId, StdlibContractCbor.bytes(values.get(2)), required,
                    StdlibContractCbor.uint(values.get(4)));
        }
        if ((op == OP_APPROVE || op == OP_REJECT) && values.size() == 2) {
            return new Command(op, itemId, new byte[0], 0, 0);
        }
        throw StdlibContractCbor.malformed();
    }

    public static byte[] itemKey(String itemId) { return key("i/", itemId); }
    public static byte[] stagedEffectPayloadKey(String itemId) { return key("ae/p/", itemId); }
    public static byte[] effectStateKey(String itemId) { return key("ae/s/", itemId); }

    public static Item decodeItem(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 7).getDataItems();
        Array approvalValues = StdlibContractCbor.array(values.get(5), 64);
        List<byte[]> approvers = new ArrayList<>();
        for (var value : approvalValues.getDataItems()) {
            approvers.add(StdlibContractCbor.bytes(value, 32));
        }
        int status = StdlibContractCbor.uintInt(values.get(0));
        int required = StdlibContractCbor.uintInt(values.get(3));
        byte[] rejecter = StdlibContractCbor.bytes(values.get(6));
        if (status < 0 || status > 3 || required <= 0
                || (rejecter.length != 0 && rejecter.length != 32)
                || hasDuplicateApprovers(approvers)
                || (status == 0 && approvers.size() >= required)
                || (status == 1 && approvers.size() < required)
                || (status == 2 && rejecter.length != 32)
                || (status != 2 && rejecter.length != 0)) {
            throw StdlibContractCbor.malformed();
        }
        return new Item(status, StdlibContractCbor.bytes(values.get(1), 32),
                StdlibContractCbor.bytes(values.get(2), 32), required,
                StdlibContractCbor.uint(values.get(4)), approvers, rejecter);
    }

    public static EffectState decodeEffectState(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 6).getDataItems();
        byte[] detail = StdlibContractCbor.bytes(values.get(5));
        if (detail.length != 0 && detail.length != 32) throw StdlibContractCbor.malformed();
        int version = StdlibContractCbor.uintInt(values.get(0));
        int status = StdlibContractCbor.uintInt(values.get(1));
        String effectId = StdlibContractCbor.text(values.get(2));
        int outcome = StdlibContractCbor.uintInt(values.get(3));
        if (version != 1 || status < 0 || status > 2 || !canonicalEffectId(effectId)
                || (status == 0 && outcome != 0)
                || (status == 1 && outcome != 1)
                || (status == 2 && (outcome < 2 || outcome > 4))) {
            throw StdlibContractCbor.malformed();
        }
        return new EffectState(version, status, effectId, outcome,
                StdlibContractCbor.bytes(values.get(4)), detail.length == 0 ? null : detail);
    }

    private static byte[] decision(int op, String itemId) {
        requireItemId(itemId);
        Array array = new Array();
        array.add(new UnsignedInteger(op));
        array.add(new UnicodeString(itemId));
        return StdlibContractCbor.encode(array);
    }

    private static byte[] key(String prefix, String itemId) {
        StdlibContractCbor.requireText(itemId, "itemId");
        byte[] key = (prefix + itemId).getBytes(StandardCharsets.UTF_8);
        StdlibContractCbor.requireStateKey(key, "approval state key");
        return key;
    }

    private static void requireItemId(String itemId) {
        key("ae/s/", itemId);
    }

    private static boolean hasDuplicateApprovers(List<byte[]> approvers) {
        for (int left = 0; left < approvers.size(); left++) {
            for (int right = left + 1; right < approvers.size(); right++) {
                if (Arrays.equals(approvers.get(left), approvers.get(right))) return true;
            }
        }
        return false;
    }

    private static boolean canonicalEffectId(String value) {
        if (value == null || value.isBlank()) return false;
        int last = value.lastIndexOf('/');
        int middle = value.lastIndexOf('/', last - 1);
        if (middle <= 0 || last <= middle + 1 || last == value.length() - 1) return false;
        try {
            long height = Long.parseLong(value.substring(middle + 1, last));
            int ordinal = Integer.parseInt(value.substring(last + 1));
            return height > 0 && ordinal >= 0
                    && value.equals(value.substring(0, middle) + "/" + height + "/" + ordinal);
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    public record Command(int operation, String itemId, byte[] payload,
                          int required, long deadlineMillis) {
        public Command { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    public record Item(int status, byte[] proposer, byte[] payloadHash, int required,
                       long deadlineMillis, List<byte[]> approvers, byte[] rejecter) {
        public Item {
            proposer = proposer.clone(); payloadHash = payloadHash.clone(); rejecter = rejecter.clone();
            approvers = approvers.stream().map(byte[]::clone).toList();
        }
        @Override public byte[] proposer() { return proposer.clone(); }
        @Override public byte[] payloadHash() { return payloadHash.clone(); }
        @Override public List<byte[]> approvers() { return approvers.stream().map(byte[]::clone).toList(); }
        @Override public byte[] rejecter() { return rejecter.clone(); }
    }

    public record EffectState(int version, int status, String effectId, int outcomeCode,
                              byte[] externalReference, byte[] detailHash) {
        public EffectState {
            externalReference = externalReference.clone();
            detailHash = detailHash != null ? detailHash.clone() : null;
        }
        @Override public byte[] externalReference() { return externalReference.clone(); }
        @Override public byte[] detailHash() { return detailHash != null ? detailHash.clone() : null; }
    }
}
