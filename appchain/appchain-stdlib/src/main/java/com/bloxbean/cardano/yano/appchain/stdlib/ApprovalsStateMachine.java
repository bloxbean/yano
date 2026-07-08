package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Standard-library state machine {@code approvals} (ADR app-layer/006 E2.2):
 * k-of-n approval workflows with a fully provable decision trail.
 * <p>
 * Commands (CBOR body):
 * <pre>
 *   [0, itemId(tstr), payload(bstr), required(uint), deadlineMillis(uint)]  PROPOSE
 *   [1, itemId(tstr)]                                                       APPROVE
 *   [2, itemId(tstr)]                                                       REJECT
 * </pre>
 * Rules (all deterministic — deadlines use the block's consensus timestamp):
 * <ul>
 *   <li>PROPOSE creates the item (idempotent: later proposes for an existing
 *       id are no-ops). {@code required} = approvals needed; deadline 0 = none.</li>
 *   <li>APPROVE adds the sender (deduplicated). When distinct approvers reach
 *       {@code required}, status becomes APPROVED (terminal).</li>
 *   <li>A single REJECT from any member marks the item REJECTED (terminal),
 *       unless it is already APPROVED.</li>
 *   <li>A command touching a PENDING item past its deadline marks it EXPIRED.</li>
 * </ul>
 * State entry (CBOR): {@code "i/" + itemId →
 * [status(uint), proposer(bstr), payloadHash(bstr32), required(uint),
 *  deadline(uint), approvers([bstr...]), rejecter(bstr|empty)]}
 * — provable per item against the anchored root.
 * <p>
 * Use cases: release gates, payment authorization, cross-org sign-off,
 * credential issuance approval.
 */
public final class ApprovalsStateMachine implements AppStateMachine {

    public static final String ID = "approvals";

    public static final int OP_PROPOSE = 0;
    public static final int OP_APPROVE = 1;
    public static final int OP_REJECT = 2;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_EXPIRED = 3;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            Command.decode(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed approvals command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            Command command;
            try {
                command = Command.decode(message.getBody());
            } catch (Exception e) {
                continue; // filtered at admission; deterministic skip
            }
            byte[] itemKey = itemKey(command.itemId());
            Optional<byte[]> existing = writer.get(itemKey);

            if (command.op() == OP_PROPOSE) {
                if (existing.isEmpty()) {
                    Item item = new Item(STATUS_PENDING, message.getSender(),
                            Blake2bUtil.blake2bHash256(command.payload()),
                            command.required(), command.deadlineMillis(),
                            List.of(), new byte[0]);
                    writer.put(itemKey, item.encode());
                }
                continue;
            }

            if (existing.isEmpty()) {
                continue; // approve/reject for unknown item — deterministic no-op
            }
            Item item = Item.decode(existing.get());
            if (item.status() != STATUS_PENDING) {
                continue; // terminal states are immutable
            }
            if (item.deadline() > 0 && block.timestamp() > item.deadline()) {
                writer.put(itemKey, item.withStatus(STATUS_EXPIRED).encode());
                continue;
            }

            if (command.op() == OP_APPROVE) {
                if (containsKey(item.approvers(), message.getSender())) {
                    continue; // duplicate approval
                }
                List<byte[]> approvers = new ArrayList<>(item.approvers());
                approvers.add(message.getSender());
                int status = approvers.size() >= item.required() ? STATUS_APPROVED : STATUS_PENDING;
                writer.put(itemKey, new Item(status, item.proposer(), item.payloadHash(),
                        item.required(), item.deadline(), approvers, item.rejecter()).encode());
            } else if (command.op() == OP_REJECT) {
                writer.put(itemKey, new Item(STATUS_REJECTED, item.proposer(), item.payloadHash(),
                        item.required(), item.deadline(), item.approvers(), message.getSender()).encode());
            }
        }
    }

    // ------------------------------------------------------------------
    // Client/helper encoding
    // ------------------------------------------------------------------

    public static byte[] propose(String itemId, byte[] payload, int required, long deadlineMillis) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(OP_PROPOSE));
        arr.add(new UnicodeString(itemId));
        arr.add(new ByteString(payload != null ? payload : new byte[0]));
        arr.add(new UnsignedInteger(required));
        arr.add(new UnsignedInteger(deadlineMillis));
        return CborSerializationUtil.serialize(arr);
    }

    public static byte[] approve(String itemId) {
        return simpleCommand(OP_APPROVE, itemId);
    }

    public static byte[] reject(String itemId) {
        return simpleCommand(OP_REJECT, itemId);
    }

    public static byte[] itemKey(String itemId) {
        return ("i/" + itemId).getBytes(StandardCharsets.UTF_8);
    }

    /** Decode a state entry for assertions/queries. */
    public static Item decodeItem(byte[] entry) {
        return Item.decode(entry);
    }

    private static byte[] simpleCommand(int op, String itemId) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(op));
        arr.add(new UnicodeString(itemId));
        return CborSerializationUtil.serialize(arr);
    }

    private static boolean containsKey(List<byte[]> keys, byte[] key) {
        for (byte[] candidate : keys) {
            if (java.util.Arrays.equals(candidate, key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    record Command(int op, String itemId, byte[] payload, int required, long deadlineMillis) {
        static Command decode(byte[] body) {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
            int op = ((UnsignedInteger) items.get(0)).getValue().intValue();
            String itemId = ((UnicodeString) items.get(1)).getString();
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("Empty itemId");
            }
            if (op == OP_PROPOSE) {
                byte[] payload = ((ByteString) items.get(2)).getBytes();
                int required = ((UnsignedInteger) items.get(3)).getValue().intValue();
                long deadline = ((UnsignedInteger) items.get(4)).getValue().longValue();
                if (required <= 0) {
                    throw new IllegalArgumentException("required must be positive");
                }
                return new Command(op, itemId, payload, required, deadline);
            }
            if (op == OP_APPROVE || op == OP_REJECT) {
                return new Command(op, itemId, new byte[0], 0, 0);
            }
            throw new IllegalArgumentException("Unknown op: " + op);
        }
    }

    /** Per-item workflow state. */
    public record Item(int status, byte[] proposer, byte[] payloadHash, int required,
                       long deadline, List<byte[]> approvers, byte[] rejecter) {

        Item withStatus(int newStatus) {
            return new Item(newStatus, proposer, payloadHash, required, deadline, approvers, rejecter);
        }

        byte[] encode() {
            Array arr = new Array();
            arr.add(new UnsignedInteger(status));
            arr.add(new ByteString(proposer));
            arr.add(new ByteString(payloadHash));
            arr.add(new UnsignedInteger(required));
            arr.add(new UnsignedInteger(deadline));
            Array approversArr = new Array();
            for (byte[] approver : approvers) {
                approversArr.add(new ByteString(approver));
            }
            arr.add(approversArr);
            arr.add(new ByteString(rejecter != null ? rejecter : new byte[0]));
            return CborSerializationUtil.serialize(arr);
        }

        static Item decode(byte[] entry) {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(entry)).getDataItems();
            List<byte[]> approvers = new ArrayList<>();
            for (DataItem approverDI : ((Array) items.get(5)).getDataItems()) {
                approvers.add(((ByteString) approverDI).getBytes());
            }
            return new Item(
                    ((UnsignedInteger) items.get(0)).getValue().intValue(),
                    ((ByteString) items.get(1)).getBytes(),
                    ((ByteString) items.get(2)).getBytes(),
                    ((UnsignedInteger) items.get(3)).getValue().intValue(),
                    ((UnsignedInteger) items.get(4)).getValue().longValue(),
                    approvers,
                    ((ByteString) items.get(6)).getBytes());
        }
    }
}
