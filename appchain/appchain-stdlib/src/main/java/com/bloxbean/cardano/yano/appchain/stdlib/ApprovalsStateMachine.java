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
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;

import java.util.ArrayList;
import java.util.Arrays;
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

    public static final int EFFECT_STATUS_PENDING = 0;
    public static final int EFFECT_STATUS_CONFIRMED = 1;
    public static final int EFFECT_STATUS_FAILED = 2;

    private static final String ON_APPROVED_SCOPE_PREFIX = "approvals/on-approved/";

    private final AppChainApprovalsConfig onApprovedEffect;
    private final ActivationSchedule activations;

    public ApprovalsStateMachine() {
        this(AppChainApprovalsConfig.DISABLED, ActivationSchedule.empty());
    }

    public ApprovalsStateMachine(AppChainApprovalsConfig onApprovedEffect,
                                 ActivationSchedule activations) {
        this.onApprovedEffect = onApprovedEffect;
        this.activations = activations;
    }

    /** Generic effect active at this height; missing activation means inactive. */
    private boolean onApprovedEffectActiveAt(long height) {
        return onApprovedEffect.enabled()
                && activations.isActive(AppChainApprovalsConfig.FEATURE, height);
    }

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
    public AdmissionResult validateForBlock(AppMessage message, long candidateHeight,
                                            AppStateReader committedState) {
        try {
            decodeCommandForHeight(message.getBody(), candidateHeight);
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed approvals command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        apply(block, writer, AppEffectEmitter
                .rejecting("Effects unavailable on the legacy 2-arg apply path"));
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer,
                      AppEffectEmitter effects) {
        boolean effectActive = onApprovedEffectActiveAt(block.height());
        for (AppMessage message : block.messages()) {
            Command command;
            try {
                command = decodeCommandForHeight(message.getBody(), block.height());
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
                    if (effectActive) {
                        // The item retains only the payload hash. Keep a CBOR-wrapped
                        // copy until decision so an empty payload is representable.
                        writer.put(stagedEffectPayloadKey(command.itemId()),
                                encodeStagedPayload(command.payload()));
                    }
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
                writer.delete(stagedEffectPayloadKey(command.itemId()));
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
                if (status == STATUS_APPROVED && effectActive) {
                    emitOnApprovedEffect(command.itemId(), writer, effects, message);
                }
            } else if (command.op() == OP_REJECT) {
                writer.put(itemKey, new Item(STATUS_REJECTED, item.proposer(), item.payloadHash(),
                        item.required(), item.deadline(), item.approvers(), message.getSender()).encode());
                writer.delete(stagedEffectPayloadKey(command.itemId()));
            }
        }
    }

    private Command decodeCommandForHeight(byte[] body, long height) {
        Command command = Command.decode(body);
        if (onApprovedEffectActiveAt(height)
                && command.op() == OP_PROPOSE
                && command.payload().length > onApprovedEffect.maxPayloadBytes()) {
            throw new IllegalArgumentException("proposal payload exceeds effects.max-payload-bytes ("
                    + onApprovedEffect.maxPayloadBytes() + ")");
        }
        return command;
    }

    /** Final approval reached: turn the staged opaque payload into one CHAIN effect. */
    private void emitOnApprovedEffect(String itemId, AppStateWriter writer,
                                      AppEffectEmitter effects, AppMessage trigger) {
        Optional<byte[]> staged = writer.get(stagedEffectPayloadKey(itemId));
        if (staged.isEmpty()) {
            return; // proposal was finalized before activation
        }
        byte[] payload = decodeStagedPayload(staged.orElseThrow());
        EffectId effectId = effects.emit(
                EffectIntent.of(onApprovedEffect.type(), payload)
                        .scope(ON_APPROVED_SCOPE_PREFIX + itemId)
                        .gate(onApprovedEffect.gate())
                        .result(ResultPolicy.CHAIN)
                        .expiryBlocks(onApprovedEffect.expiryBlocks())
                        .sourceMessageId(trigger.getMessageId())
                        .build());
        writer.put(effectStateKey(itemId), ApprovalEffectState.pending(effectId).encode());
        writer.delete(stagedEffectPayloadKey(itemId));
    }

    @Override
    public void onEffectResult(AppBlock block, EffectResult result,
                               AppStateWriter writer) {
        if (!onApprovedEffect.enabled()
                || !result.scope().startsWith(ON_APPROVED_SCOPE_PREFIX)
                || !onApprovedEffect.type().equals(result.type())) {
            return;
        }
        String itemId = result.scope().substring(ON_APPROVED_SCOPE_PREFIX.length());
        if (itemId.isEmpty()) {
            return;
        }
        Optional<byte[]> entry = writer.get(itemKey(itemId));
        if (entry.isEmpty()) {
            return;
        }
        Item item = Item.decode(entry.get());
        if (item.status() != STATUS_APPROVED) {
            return;
        }
        Optional<byte[]> effectEntry = writer.get(effectStateKey(itemId));
        if (effectEntry.isEmpty()) {
            return;
        }
        ApprovalEffectState current = ApprovalEffectState.decode(effectEntry.orElseThrow());
        if (current.status() != EFFECT_STATUS_PENDING
                || !current.effectId().equals(result.effectId().canonical())) {
            return;
        }
        writer.put(effectStateKey(itemId), current.terminal(result).encode());
    }

    // ------------------------------------------------------------------
    // Client/helper encoding
    // ------------------------------------------------------------------

    public static byte[] propose(String itemId, byte[] payload, int required, long deadlineMillis) {
        return ApprovalsContract.propose(itemId, payload, required, deadlineMillis);
    }

    public static byte[] approve(String itemId) {
        return ApprovalsContract.approve(itemId);
    }

    public static byte[] reject(String itemId) {
        return ApprovalsContract.reject(itemId);
    }

    public static byte[] itemKey(String itemId) {
        return ApprovalsContract.itemKey(itemId);
    }

    /** CBOR-wrapped PROPOSE payload awaiting the approval decision. */
    public static byte[] stagedEffectPayloadKey(String itemId) {
        return ApprovalsContract.stagedEffectPayloadKey(itemId);
    }

    /** Independently provable generic effect lifecycle record. */
    public static byte[] effectStateKey(String itemId) {
        return ApprovalsContract.effectStateKey(itemId);
    }

    /** Decode a generic effect state entry for assertions and queries. */
    public static ApprovalEffectState decodeEffectState(byte[] entry) {
        return ApprovalEffectState.decode(entry);
    }

    /** Decode a state entry for assertions/queries. */
    public static Item decodeItem(byte[] entry) {
        return Item.decode(entry);
    }

    private static byte[] encodeStagedPayload(byte[] payload) {
        return CborSerializationUtil.serialize(new ByteString(
                payload != null ? payload : new byte[0]));
    }

    private static byte[] decodeStagedPayload(byte[] entry) {
        StdlibCbor.requirePersistedEntry(entry);
        DataItem decoded = CborSerializationUtil.deserializeOne(entry);
        if (!(decoded instanceof ByteString bytes)) {
            throw new IllegalArgumentException("invalid staged approval effect payload");
        }
        return bytes.getBytes();
    }

    private static boolean containsKey(List<byte[]> keys, byte[] key) {
        for (byte[] candidate : keys) {
            if (Arrays.equals(candidate, key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /**
     * Authenticated projection of the generic on-approved effect lifecycle.
     * The approval decision remains in {@link Item}; this record never changes
     * it to an execution-shaped status.
     */
    public record ApprovalEffectState(int version,
                                      int status,
                                      String effectId,
                                      EffectOutcome outcome,
                                      byte[] externalRef,
                                      byte[] detailHash) {
        public static final int SCHEMA_VERSION = 1;

        public ApprovalEffectState {
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException("approval effect state version must be 1");
            }
            if (status < EFFECT_STATUS_PENDING || status > EFFECT_STATUS_FAILED) {
                throw new IllegalArgumentException("unknown approval effect status: " + status);
            }
            if (effectId == null || effectId.isBlank()) {
                throw new IllegalArgumentException("approval effect id is required");
            }
            EffectId parsed = EffectId.parse(effectId);
            if (!parsed.canonical().equals(effectId)) {
                throw new IllegalArgumentException("approval effect id must be canonical");
            }
            externalRef = externalRef != null ? externalRef.clone() : new byte[0];
            detailHash = detailHash != null && detailHash.length > 0 ? detailHash.clone() : null;
            if (detailHash != null && detailHash.length != 32) {
                throw new IllegalArgumentException("approval effect detailHash must be 32 bytes");
            }
            if (status == EFFECT_STATUS_PENDING && outcome != null) {
                throw new IllegalArgumentException("pending approval effect cannot have an outcome");
            }
            if (status != EFFECT_STATUS_PENDING && outcome == null) {
                throw new IllegalArgumentException("terminal approval effect requires an outcome");
            }
        }

        @Override
        public byte[] externalRef() {
            return externalRef.clone();
        }

        @Override
        public byte[] detailHash() {
            return detailHash != null ? detailHash.clone() : null;
        }

        static ApprovalEffectState pending(EffectId effectId) {
            return new ApprovalEffectState(SCHEMA_VERSION, EFFECT_STATUS_PENDING,
                    effectId.canonical(), null, new byte[0], null);
        }

        ApprovalEffectState terminal(EffectResult result) {
            int terminalStatus = result.outcome() == EffectOutcome.CONFIRMED
                    ? EFFECT_STATUS_CONFIRMED : EFFECT_STATUS_FAILED;
            return new ApprovalEffectState(SCHEMA_VERSION, terminalStatus, effectId,
                    result.outcome(), result.externalRef(), result.detailHash());
        }

        byte[] encode() {
            Array arr = new Array();
            arr.add(new UnsignedInteger(version));
            arr.add(new UnsignedInteger(status));
            arr.add(new UnicodeString(effectId));
            arr.add(new UnsignedInteger(outcome != null ? outcome.code() : 0));
            arr.add(new ByteString(externalRef));
            arr.add(new ByteString(detailHash != null ? detailHash : new byte[0]));
            return CborSerializationUtil.serialize(arr);
        }

        static ApprovalEffectState decode(byte[] entry) {
            StdlibCbor.requirePersistedEntry(entry);
            DataItem decoded = CborSerializationUtil.deserializeOne(entry);
            if (!(decoded instanceof Array array) || array.getDataItems().size() != 6) {
                throw new IllegalArgumentException("invalid approval effect state");
            }
            List<DataItem> items = array.getDataItems();
            int outcomeCode = ((UnsignedInteger) items.get(3)).getValue().intValue();
            byte[] detail = ((ByteString) items.get(5)).getBytes();
            return new ApprovalEffectState(
                    ((UnsignedInteger) items.get(0)).getValue().intValue(),
                    ((UnsignedInteger) items.get(1)).getValue().intValue(),
                    ((UnicodeString) items.get(2)).getString(),
                    outcomeCode == 0 ? null : EffectOutcome.fromCode(outcomeCode),
                    ((ByteString) items.get(4)).getBytes(),
                    detail.length == 0 ? null : detail);
        }
    }

    record Command(int op, String itemId, byte[] payload, int required, long deadlineMillis) {
        static Command decode(byte[] body) {
            ApprovalsContract.Command decoded = ApprovalsContract.decodeCommand(body);
            return new Command(decoded.operation(), decoded.itemId(), decoded.payload(),
                    decoded.required(), decoded.deadlineMillis());
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
            StdlibCbor.requirePersistedEntry(entry);
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
