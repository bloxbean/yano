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
    /** Payments extension (ADR-010 §8.1): the emitted payment effect CONFIRMED. */
    public static final int STATUS_PAID = 4;
    /** Payments extension: the payment effect FAILED / CANCELLED / EXPIRED. */
    public static final int STATUS_PAY_FAILED = 5;

    /**
     * Payments extension config (ADR app-layer/010 §8.1), off by default:
     * on final approval the PROPOSE payload (kept under {@code p/<itemId>}
     * until decision) is emitted as a CHAIN-result effect whose outcome
     * flows back through {@code onEffectResult} into STATUS_PAID /
     * STATUS_PAY_FAILED with the external ref under {@code t/<itemId>}.
     * <p>
     * Enabling payments is a transition-logic change: gate it with
     * {@code machines.approvals.activations.payments=<height>} (ADR 010.1).
     * Missing activation is always inactive; use height {@code 1} when a new
     * chain intentionally enables payments from genesis.
     *
     * @param enabled      machines.approvals.payments
     * @param type         machines.approvals.payment-type (executor routing)
     * @param expiryBlocks machines.approvals.payment-expiry-blocks (0 = none)
     * @param gate         machines.approvals.payment-gate: chain-default|app-final|l1-anchored
     */
    public record PaymentsConfig(boolean enabled,
                                 String type,
                                 long expiryBlocks,
                                 com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate gate) {

        public static final PaymentsConfig DISABLED = new PaymentsConfig(false, "", 0,
                com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate.CHAIN_DEFAULT);

        public static PaymentsConfig from(java.util.Map<String, String> settings) {
            if (!Boolean.parseBoolean(settings.getOrDefault("machines.approvals.payments", "false"))) {
                return DISABLED;
            }
            String gateValue = settings.getOrDefault("machines.approvals.payment-gate", "chain-default")
                    .trim().toLowerCase(java.util.Locale.ROOT);
            var gate = switch (gateValue) {
                case "chain-default" -> com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate.CHAIN_DEFAULT;
                case "app-final" -> com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate.APP_FINAL;
                case "l1-anchored" -> com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate.L1_ANCHORED;
                default -> throw new IllegalArgumentException(
                        "machines.approvals.payment-gate must be chain-default|app-final|l1-anchored");
            };
            return new PaymentsConfig(true,
                    settings.getOrDefault("machines.approvals.payment-type", "cardano.payment"),
                    Long.parseLong(settings.getOrDefault(
                            "machines.approvals.payment-expiry-blocks", "1000").trim()),
                    gate);
        }
    }

    private final PaymentsConfig payments;
    private final com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule activations;

    public ApprovalsStateMachine() {
        this(PaymentsConfig.DISABLED,
                com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule.empty());
    }

    public ApprovalsStateMachine(PaymentsConfig payments,
                                 com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule activations) {
        this.payments = payments;
        this.activations = activations;
    }

    /** Payments active at this height (010.1: missing activation means inactive). */
    private boolean paymentsActiveAt(long height) {
        return payments.enabled() && activations.isActive("payments", height);
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
    public void apply(AppBlock block, AppStateWriter writer) {
        apply(block, writer, com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter
                .rejecting("Effects unavailable on the legacy 2-arg apply path"));
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer,
                      com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter effects) {
        boolean paymentsActive = paymentsActiveAt(block.height());
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
                    if (paymentsActive && command.payload().length > 0) {
                        // The payment body is needed at approval time; the item
                        // holds only its hash. Kept until decision.
                        writer.put(paymentKey(command.itemId()), command.payload());
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
                writer.delete(paymentKey(command.itemId()));
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
                if (status == STATUS_APPROVED && paymentsActive) {
                    emitPayment(command.itemId(), block, writer, effects, message);
                }
            } else if (command.op() == OP_REJECT) {
                writer.put(itemKey, new Item(STATUS_REJECTED, item.proposer(), item.payloadHash(),
                        item.required(), item.deadline(), item.approvers(), message.getSender()).encode());
                writer.delete(paymentKey(command.itemId()));
            }
        }
    }

    /** Final approval reached: turn the parked payment body into a CHAIN effect (ADR-010 §8.1). */
    private void emitPayment(String itemId, AppBlock block, AppStateWriter writer,
                             com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter effects,
                             AppMessage trigger) {
        Optional<byte[]> payment = writer.get(paymentKey(itemId));
        if (payment.isEmpty()) {
            return; // proposed without a payload, or proposed before payments activated
        }
        var effectId = effects.emit(
                com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent
                        .of(payments.type(), payment.get())
                        .scope(PAYMENT_SCOPE_PREFIX + itemId)   // one payment per item, ever
                        .gate(payments.gate())
                        .result(com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN)
                        .expiryBlocks(payments.expiryBlocks())
                        .sourceMessageId(trigger.getMessageId())
                        .build());
        writer.put(effectLinkKey(itemId),
                effectId.canonical().getBytes(StandardCharsets.UTF_8));
        writer.delete(paymentKey(itemId));
    }

    @Override
    public void onEffectResult(AppBlock block,
                               com.bloxbean.cardano.yano.api.appchain.effects.EffectResult result,
                               AppStateWriter writer) {
        if (!result.scope().startsWith(PAYMENT_SCOPE_PREFIX)
                || !payments.type().equals(result.type())) {
            return; // not this machine's payment effect — deterministic skip
        }
        String itemId = result.scope().substring(PAYMENT_SCOPE_PREFIX.length());
        Optional<byte[]> entry = writer.get(itemKey(itemId));
        if (entry.isEmpty()) {
            return;
        }
        Item item = Item.decode(entry.get());
        if (item.status() != STATUS_APPROVED) {
            return; // only an approved item awaits its payment outcome
        }
        writer.put(itemKey(itemId), item.withStatus(
                result.confirmed() ? STATUS_PAID : STATUS_PAY_FAILED).encode());
        if (result.externalRef().length > 0) {
            writer.put(paymentRefKey(itemId), result.externalRef());
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

    private static final String PAYMENT_SCOPE_PREFIX = "approvals/";

    /** Parked PROPOSE payload awaiting the approval decision (payments extension). */
    public static byte[] paymentKey(String itemId) {
        return ("p/" + itemId).getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical effect id of the emitted payment (payments extension). */
    public static byte[] effectLinkKey(String itemId) {
        return ("f/" + itemId).getBytes(StandardCharsets.UTF_8);
    }

    /** External ref (e.g. L1 txHash) of the incorporated payment outcome. */
    public static byte[] paymentRefKey(String itemId) {
        return ("t/" + itemId).getBytes(StandardCharsets.UTF_8);
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
