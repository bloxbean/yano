package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chain-governed membership (ADR app-layer/008.3): membership changes are
 * finalized app messages on {@code ~governance/membership}. A command
 * ACTIVATES once threshold-many distinct members' identical commands are
 * finalized within the approval window; activation appends a membership epoch
 * effective {@code activation-lag} blocks later. Everything here is a
 * deterministic function of finalized history: every node derives identical
 * pending state and epochs, catch-up included.
 *
 * <pre>
 *   membership-command = [ version(1), op, subject, activation-lag ]
 *     op 0 = add-member    subject = member key (bstr, 32)
 *     op 1 = remove-member subject = member key (bstr, 32)
 *     op 2 = set-threshold subject = k (uint)
 * </pre>
 *
 * Guard rails (evaluated at activation, deterministic): a change never drops
 * the member count below the active threshold; thresholds stay in [1, n];
 * removing the configured FIXED proposer is void. A violating activation is a
 * deterministic no-op, never a stall.
 */
final class GovernedMembership {

    static final String TOPIC = "~governance/membership";
    static final int OP_ADD = 0;
    static final int OP_REMOVE = 1;
    static final int OP_SET_THRESHOLD = 2;
    static final long DEFAULT_APPROVAL_WINDOW_BLOCKS = 600;
    static final long DEFAULT_ACTIVATION_LAG = 10;

    private static final String META_PENDING = "gov_pending";

    private final MemberGroup group;
    private final String fixedProposerHex;
    private final long approvalWindowBlocks;
    private final Logger log;

    /** command bytes (hex) → approval state; rebuilt from meta on restart. */
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

    record Command(int op, byte[] memberKey, int threshold, long activationLag) {
    }

    private static final class Pending {
        final long firstSeenHeight;
        final Set<String> approvers = new TreeSet<>();

        Pending(long firstSeenHeight) {
            this.firstSeenHeight = firstSeenHeight;
        }
    }

    /** One meta write to include in the block's atomic commit batch. */
    record MetaWrite(String key, byte[] value) {
    }

    /** Result of processing one block: batch writes + post-commit epoch effects. */
    record Result(List<MetaWrite> writes, List<EpochEffect> effects) {
        static final Result EMPTY = new Result(List.of(), List.of());
    }

    record EpochEffect(long fromHeight, Set<String> members, int threshold) {
    }

    GovernedMembership(MemberGroup group, String fixedProposerHex,
                       long approvalWindowBlocks, Logger log) {
        this.group = Objects.requireNonNull(group, "group");
        this.fixedProposerHex = fixedProposerHex != null
                ? fixedProposerHex.toLowerCase(Locale.ROOT) : "";
        this.approvalWindowBlocks = approvalWindowBlocks > 0
                ? approvalWindowBlocks : DEFAULT_APPROVAL_WINDOW_BLOCKS;
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Restore pending-approval state persisted with the last committed block. */
    void restore(AppLedgerStore ledger) {
        byte[] stored = ledger.metaBytes(META_PENDING);
        pending.clear();
        if (stored == null || stored.length == 0) {
            return;
        }
        Map cbor = (Map) CborSerializationUtil.deserializeOne(stored);
        for (DataItem keyItem : cbor.getKeys()) {
            String commandHex = ((UnicodeString) keyItem).getString();
            Array value = (Array) cbor.get(keyItem);
            Pending entry = new Pending(
                    ((UnsignedInteger) value.getDataItems().get(0)).getValue().longValue());
            Array approvers = (Array) value.getDataItems().get(1);
            for (DataItem approver : approvers.getDataItems()) {
                entry.approvers.add(((UnicodeString) approver).getString());
            }
            pending.put(commandHex, entry);
        }
    }

    /**
     * Deterministically process one finalized block's governance messages.
     * Returns the meta writes for the SAME atomic commit batch; activated
     * epochs are applied to the in-memory group HERE (so later commands in
     * the same block see them — and the returned {@code member_epochs} write
     * reflects them). If the subsequent commit fails (fatal disk-class error),
     * the node aborts the round and a restart re-derives the group from the
     * ledger — no divergence survives. The effects list is informational.
     */
    Result processBlock(AppBlock block) {
        boolean touched = false;
        List<EpochEffect> effects = new ArrayList<>();
        long height = block.height();

        // Expire stale half-approved commands (window is in blocks)
        touched |= pending.entrySet().removeIf(
                e -> height - e.getValue().firstSeenHeight > approvalWindowBlocks);

        for (AppMessage message : block.messages()) {
            if (!TOPIC.equals(message.getTopic())) {
                continue;
            }
            String approver = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
            if (!group.containsAt(approver, height)) {
                continue; // not a member at this height — deterministic skip
            }
            Command command;
            try {
                command = decodeCommand(message.getBody());
            } catch (Exception e) {
                log.warn("Malformed governance command in block {} — ignored ({})",
                        height, e.toString());
                continue;
            }
            String commandHex = HexUtil.encodeHexString(message.getBody());
            Pending entry = pending.computeIfAbsent(commandHex, k -> new Pending(height));
            touched |= entry.approvers.add(approver);

            int required = group.thresholdAt(height);
            if (entry.approvers.size() >= required) {
                pending.remove(commandHex);
                touched = true;
                EpochEffect effect = activate(command, height);
                if (effect != null) {
                    effects.add(effect);
                    // Later commands in the SAME block see the new epoch
                    group.appendEpoch(effect.fromHeight(), effect.members(), effect.threshold());
                }
            }
        }

        if (!touched && effects.isEmpty()) {
            return Result.EMPTY;
        }
        List<MetaWrite> writes = new ArrayList<>();
        writes.add(new MetaWrite(META_PENDING, encodePending()));
        if (!effects.isEmpty()) {
            writes.add(new MetaWrite("member_epochs",
                    group.encode().getBytes(StandardCharsets.UTF_8)));
        }
        return new Result(writes, effects);
    }

    /** Apply guard rails; null = void activation (deterministic no-op). */
    private EpochEffect activate(Command command, long height) {
        Set<String> members = new LinkedHashSet<>(group.membersAt(height));
        int threshold = group.thresholdAt(height);
        long fromHeight = height + Math.max(1, command.activationLag());

        switch (command.op()) {
            case OP_ADD -> {
                String key = HexUtil.encodeHexString(command.memberKey()).toLowerCase(Locale.ROOT);
                if (!members.add(key)) {
                    log.info("Governance: add of existing member {} at height {} — no-op", key, height);
                    return null;
                }
                log.info("Governance ACTIVATED: add member {} from height {}", key, fromHeight);
            }
            case OP_REMOVE -> {
                String key = HexUtil.encodeHexString(command.memberKey()).toLowerCase(Locale.ROOT);
                if (key.equals(fixedProposerHex)) {
                    log.warn("Governance: removal of the FIXED proposer at height {} is void", height);
                    return null;
                }
                if (!members.remove(key)) {
                    log.info("Governance: removal of non-member {} at height {} — no-op", key, height);
                    return null;
                }
                if (members.size() < threshold) {
                    log.warn("Governance: removing {} would leave {} member(s) below threshold {} "
                            + "at height {} — void", key, members.size(), threshold, height);
                    return null;
                }
                log.info("Governance ACTIVATED: remove member {} from height {}", key, fromHeight);
            }
            case OP_SET_THRESHOLD -> {
                if (command.threshold() < 1 || command.threshold() > members.size()) {
                    log.warn("Governance: threshold {} outside [1, {}] at height {} — void",
                            command.threshold(), members.size(), height);
                    return null;
                }
                threshold = command.threshold();
                log.info("Governance ACTIVATED: threshold {} from height {}", threshold, fromHeight);
            }
            default -> {
                return null;
            }
        }
        return new EpochEffect(fromHeight, members, threshold);
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    static byte[] encodeCommand(int op, byte[] memberKeyOrNull, int threshold, long activationLag) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(1)); // version
        arr.add(new UnsignedInteger(op));
        if (op == OP_SET_THRESHOLD) {
            arr.add(new UnsignedInteger(threshold));
        } else {
            arr.add(new ByteString(memberKeyOrNull));
        }
        arr.add(new UnsignedInteger(activationLag));
        return CborSerializationUtil.serialize(arr);
    }

    static Command decodeCommand(byte[] body) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
        long version = ((UnsignedInteger) items.get(0)).getValue().longValue();
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported governance command version: " + version);
        }
        int op = ((UnsignedInteger) items.get(1)).getValue().intValue();
        long lag = ((UnsignedInteger) items.get(3)).getValue().longValue();
        if (op == OP_SET_THRESHOLD) {
            int threshold = ((UnsignedInteger) items.get(2)).getValue().intValue();
            return new Command(op, null, threshold, lag);
        }
        if (op != OP_ADD && op != OP_REMOVE) {
            throw new IllegalArgumentException("Unknown governance op: " + op);
        }
        byte[] key = ((ByteString) items.get(2)).getBytes();
        if (key.length != 32) {
            throw new IllegalArgumentException("Member key must be 32 bytes");
        }
        return new Command(op, key, 0, lag);
    }

    private byte[] encodePending() {
        Map cbor = new Map();
        for (var entry : pending.entrySet()) {
            Array value = new Array();
            value.add(new UnsignedInteger(entry.getValue().firstSeenHeight));
            Array approvers = new Array();
            for (String approver : entry.getValue().approvers) {
                approvers.add(new UnicodeString(approver));
            }
            value.add(approvers);
            cbor.put(new UnicodeString(entry.getKey()), value);
        }
        return CborSerializationUtil.serialize(cbor);
    }
}
