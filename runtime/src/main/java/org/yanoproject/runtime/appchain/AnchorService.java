package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataMap;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.rocksdb.WriteBatch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * L1 anchoring, metadata mode (ADR app-layer/005 D4/A1): periodically commits
 * {@code [chain-id, from-height, to-height, block-hash, state-root]} as tx
 * metadata to Cardano through the node's own tx gateway, and observes its own
 * L1 sync for confirmation. An L1 rollback of the anchor tx simply puts the
 * anchor back in pending — the app chain itself never rolls back.
 */
final class AnchorService {
    private static final long MIN_INPUT_LOVELACE = 1_000_000;
    /** Change output must stay above the L1 min-UTxO for a plain ADA output. */
    private static final long MIN_CHANGE_LOVELACE = 1_000_000;
    private static final int MAX_INPUTS = 10;
    /** Same CBOR uint width class as any realistic final fee (4-byte). */
    private static final long FEE_PLACEHOLDER = 1_000_000;
    private static final long RESUBMIT_AFTER_MS = 120_000;

    private static final String META_LAST_ANCHORED = "anchor_last_height";
    private static final String META_ANCHOR_BLOCK_HASH = "anchor_last_block_hash";
    private static final String META_ANCHOR_TX = "anchor_last_tx";
    private static final String META_ANCHOR_SLOT = "anchor_last_slot";
    private static final String META_ANCHOR_FROM = "anchor_last_from_height";
    static final String META_ANCHOR_HISTORY = "anchor_confirmation_history_v1";
    /** Durable L1 fact awaiting local completion (ADR-038 D4a); empty means none. */
    private static final String META_OBSERVED_CONFIRMATION = "anchor_observed_confirmation_v1";
    /** The submitted anchor awaiting L1 inclusion, so a restart still records it (ADR-038 D4a, F2); empty: none. */
    private static final String META_PENDING_ANCHOR = "anchor_pending_v1";

    /** Linear fee parameters from the node's current protocol params (I1.5). */
    record FeeParams(long minFeeA, long minFeeB) {
    }

    private final String chainId;
    private final AppChainConfig.AnchorConfig anchorConfig;
    private final AppLedgerStore ledger;
    private final Function<byte[], String> txSubmitter;
    private final Supplier<UtxoState> utxoStateSupplier;
    private final LongFunction<AppBlock> blockByHeight;
    private final Supplier<Long> tipHeightSupplier;
    /** Current linear-fee params; null (or null value) = use the configured fallback fee. */
    private volatile Supplier<FeeParams> feeParamsSupplier;
    /** Newest observed L1 slot for the tx validity interval; null/0 = no TTL. */
    private volatile Supplier<Long> currentSlotSupplier;
    private final Network network;
    private final SecretKey anchorKey;
    private final Address anchorAddress;
    private final Logger log;

    // Pending anchor awaiting L1 confirmation
    private volatile PendingAnchor pending;
    /** Local completion runs only while this holds (ADR-038 D8b rule 9: not while reconciling). */
    private volatile BooleanSupplier completionGate = () -> true;
    /** Best-effort notification after each local completion, from any path (ADR-038 D4a). */
    private volatile Consumer<ConfirmedAnchor> confirmationListener = confirmed -> { };
    private volatile boolean submissionInProgress;
    private volatile long lastAnchorAttemptAt;
    private volatile String lastError;
    private volatile long anchoredCount;
    private volatile long lastAnchoredL1Slot;
    private volatile String lastAnchorTxHash;

    AnchorService(String chainId,
                  AppChainConfig.AnchorConfig anchorConfig,
                  AppLedgerStore ledger,
                  Function<byte[], String> txSubmitter,
                  Supplier<UtxoState> utxoStateSupplier,
                  LongFunction<AppBlock> blockByHeight,
                  Supplier<Long> tipHeightSupplier,
                  long protocolMagic,
                  Logger log) {
        this.chainId = chainId;
        this.anchorConfig = Objects.requireNonNull(anchorConfig, "anchorConfig");
        this.ledger = ledger;
        this.txSubmitter = txSubmitter;
        this.utxoStateSupplier = utxoStateSupplier;
        this.blockByHeight = blockByHeight;
        this.tipHeightSupplier = tipHeightSupplier;
        // Anchor address uses testnet network id for non-mainnet magics
        this.network = protocolMagic == 764824073L
                ? new Network(1, protocolMagic)
                : new Network(0, protocolMagic);
        byte[] seed = HexUtil.decodeHexString(anchorConfig.signingKeyHex().trim());
        if (seed.length != 32)
            throw new IllegalArgumentException("Anchor signing key must be a 32-byte Ed25519 seed (hex)");
        try {
            this.anchorKey = SecretKey.create(seed);
            VerificationKey vk = KeyGenUtil.getPublicKeyFromPrivateKey(this.anchorKey);
            this.anchorAddress = AddressProvider.getEntAddress(
                    Credential.fromKey(KeyGenUtil.getKeyHash(vk)), network);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid anchor signing key", e);
        }
        this.log = log;
        log.info("App-chain anchor wallet address: {}", anchorAddress.getAddress());
        this.pending = loadPending();
    }

    void wireFees(Supplier<FeeParams> feeParams, Supplier<Long> currentSlot) {
        this.feeParamsSupplier = feeParams;
        this.currentSlotSupplier = currentSlot;
    }

    void setCompletionGate(BooleanSupplier gate) {
        this.completionGate = Objects.requireNonNull(gate, "gate");
    }

    void setConfirmationListener(Consumer<ConfirmedAnchor> listener) {
        this.confirmationListener = Objects.requireNonNull(listener, "listener");
    }

    String anchorAddress() {
        return anchorAddress.getAddress();
    }

    long lastAnchoredHeight() {
        return ledger.metaLong(META_LAST_ANCHORED, 0L);
    }

    /**
     * The L1_ANCHORED effect gate frontier (ADR-010 F7): the highest app height
     * covered by a confirmed anchor whose L1 inclusion slot is at or below
     * {@code stableSlot}, this node's stability-depth L1 point. Metadata and
     * script anchors keep the same confirmation journal, which L1 rollbacks
     * rewind, so a rolled-back anchor never counts. Legacy entries that record
     * no L1 inclusion block never count either (ADR-038 D8a). 0 when no
     * confirmation is that deep or the journal is unreadable.
     */
    static long stableAnchoredHeight(AppLedgerStore ledger, long stableSlot) {
        byte[] encoded = ledger.metaBytes(META_ANCHOR_HISTORY);
        if (stableSlot <= 0 || encoded == null || encoded.length == 0) {
            return 0L;
        }
        try {
            long height = 0L;
            for (Confirmation confirmation : ConfirmationHistory.decode(encoded)) {
                if (confirmation.l1BlockHash() != null && confirmation.l1Slot() <= stableSlot) {
                    height = Math.max(height, confirmation.toHeight());
                }
            }
            return height;
        } catch (IllegalArgumentException unreadable) {
            return 0L;
        }
    }

    /**
     * Anchor the current tip now, ignoring the every-blocks/interval schedule
     * (admin force-anchor, ADR 006 E5.4). No-op if an anchor tx is already
     * pending or there is nothing new to anchor.
     * @return true if an anchor submission was triggered
     */
    boolean forceAnchorNow() {
        ConfirmedAnchor confirmed;
        boolean submitted;
        synchronized (anchorLock) {
            confirmed = completeIfObserved();
            submitted = confirmed == null && forceAnchorLocked();
        }
        notifyConfirmed(confirmed);
        return submitted;
    }

    private boolean forceAnchorLocked() {
        try {
            if (loadObservedConfirmation() != null || pending != null || submissionInProgress) {
                return false;
            }
            long tip = tipHeightSupplier.get();
            long lastAnchored = lastAnchoredHeight();
            if (tip <= lastAnchored) {
                return false;
            }
            logInfoSafely("Force-anchor requested: anchoring app blocks {}..{}",
                    lastAnchored + 1, tip);
            submitAnchor(lastAnchored + 1, tip);
            return pending != null; // submitAnchor sets pending on success
        } catch (Throwable failure) {
            recordFailure("force-anchor", failure);
            return false;
        }
    }

    private final Object anchorLock = new Object();

    /** Periodic tick from the subsystem scheduler. */
    void tick() {
        ConfirmedAnchor confirmed;
        synchronized (anchorLock) {
            confirmed = completeIfObserved();
            if (confirmed == null) {
                tickLocked();
            }
        }
        notifyConfirmed(confirmed);
    }

    private void tickLocked() {
        try {
            // An observed fact awaiting completion means the tx is already on L1: never resubmit it.
            if (loadObservedConfirmation() != null || submissionInProgress) {
                return;
            }
            PendingAnchor current = pending;
            if (current != null) {
                if (System.currentTimeMillis() - current.submittedAt > RESUBMIT_AFTER_MS) {
                    log.warn("Anchor tx {} not observed on L1 within {}ms — resubmitting",
                            current.txHash, RESUBMIT_AFTER_MS);
                    setPending(null);
                    submitAnchor(current.fromHeight, current.toHeight);
                }
                return;
            }

            long tip = tipHeightSupplier.get();
            long lastAnchored = lastAnchoredHeight();
            if (tip <= lastAnchored) {
                return;
            }
            boolean dueByCount = tip - lastAnchored >= anchorConfig.everyBlocks();
            boolean dueByTime = lastAnchorAttemptAt > 0
                    ? System.currentTimeMillis() - lastAnchorAttemptAt
                            >= anchorConfig.maxIntervalMinutes() * 60_000
                    : true; // first anchor: fire as soon as there is anything to anchor
            if (dueByCount || dueByTime) {
                submitAnchor(lastAnchored + 1, tip);
            }
        } catch (Throwable failure) {
            // A recoverable Error from a callback must not cancel every later
            // ScheduledExecutor invocation. Only process-fatal failures leave
            // the periodic boundary unchanged.
            recordFailure("tick", failure);
        }
    }

    private void submitAnchor(long fromHeight, long toHeight) {
        if (submissionInProgress) {
            return;
        }
        submissionInProgress = true;
        try {
            AppBlock tipBlock = blockByHeight.apply(toHeight);
            if (tipBlock == null) {
                throw new IllegalStateException("App block unavailable for anchor submission");
            }
            byte[] blockHash = AppBlockCodec.blockHash(tipBlock);
            Transaction tx = buildAnchorTx(fromHeight, toHeight, blockHash, tipBlock.stateRoot());
            byte[] cbor = tx.serialize();
            String txHash = txSubmitter.apply(cbor);
            setPending(new PendingAnchor(fromHeight, toHeight, txHash, System.currentTimeMillis()));
            lastAnchorAttemptAt = System.currentTimeMillis();
            lastError = null;
            log.info("Anchor tx submitted: {} (app blocks {}..{}, stateRoot={})",
                    txHash, fromHeight, toHeight, HexUtil.encodeHexString(tipBlock.stateRoot()));
        } catch (Throwable failure) {
            lastAnchorAttemptAt = System.currentTimeMillis();
            recordFailure("build/submit", failure);
        } finally {
            submissionInProgress = false;
        }
    }

    /**
     * Production-grade anchor tx construction (ADR 008.1 I1.5): linear fee from
     * the node's protocol parameters (size-based, two-pass — the configured
     * fallback fee applies only when params are unavailable), multi-input
     * selection until fee + min-change is covered, a min-UTxO guard on the
     * change output, and a validity interval so a resubmitted anchor can never
     * race a late-landing original.
     */
    private Transaction buildAnchorTx(long fromHeight, long toHeight,
                                      byte[] blockHash, byte[] stateRoot) throws Exception {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null) {
            throw new IllegalStateException("UTXO state unavailable — cannot select anchor inputs");
        }
        List<org.yanoproject.api.utxo.model.Utxo> candidates = usableUtxos(utxoState);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No usable UTxO (pure ADA, >= " + MIN_INPUT_LOVELACE
                    + " lovelace) at anchor address " + anchorAddress.getAddress()
                    + " — fund the anchor wallet");
        }

        Supplier<FeeParams> paramsSupplier = feeParamsSupplier;
        FeeParams feeParams = paramsSupplier != null ? paramsSupplier.get() : null;
        Supplier<Long> slotSupplier = currentSlotSupplier;
        Long currentSlot = slotSupplier != null ? slotSupplier.get() : null;
        long ttl = currentSlot != null && currentSlot > 0
                ? currentSlot + anchorConfig.validitySlots() : 0;

        // Grow the input set until (size-based) fee + min-change is covered
        for (int count = 1; count <= Math.min(MAX_INPUTS, candidates.size()); count++) {
            List<org.yanoproject.api.utxo.model.Utxo> inputs = candidates.subList(0, count);
            long sum = inputs.stream().mapToLong(u -> u.lovelace().longValue()).sum();

            long fee;
            if (feeParams != null) {
                // Pass 1: measure the signed tx with a placeholder fee of the
                // same CBOR width class, then price it linearly
                Transaction draft = assembleAnchorTx(inputs, sum, FEE_PLACEHOLDER, ttl,
                        fromHeight, toHeight, blockHash, stateRoot);
                int size = draft.serialize().length;
                fee = feeParams.minFeeA() * size + feeParams.minFeeB();
            } else {
                fee = anchorConfig.fallbackFeeLovelace();
            }

            if (sum >= fee + MIN_CHANGE_LOVELACE) {
                return assembleAnchorTx(inputs, sum, fee, ttl,
                        fromHeight, toHeight, blockHash, stateRoot);
            }
        }
        throw new IllegalStateException("Anchor wallet balance cannot cover the anchor fee plus a "
                + "min-UTxO change output (need fee + " + MIN_CHANGE_LOVELACE + " lovelace across <= "
                + MAX_INPUTS + " inputs) at " + anchorAddress.getAddress() + " — fund the anchor wallet");
    }

    private Transaction assembleAnchorTx(List<org.yanoproject.api.utxo.model.Utxo> inputs,
                                         long inputSum, long fee, long ttl,
                                         long fromHeight, long toHeight,
                                         byte[] blockHash, byte[] stateRoot) throws Exception {
        CBORMetadataMap payload = new CBORMetadataMap();
        payload.put("v", BigInteger.ONE);
        payload.put("chain", chainId);
        payload.put("from", BigInteger.valueOf(fromHeight));
        payload.put("to", BigInteger.valueOf(toHeight));
        payload.put("block_hash", blockHash);
        payload.put("state_root", stateRoot);
        CBORMetadata metadata = new CBORMetadata();
        metadata.put(BigInteger.valueOf(anchorConfig.metadataLabel()), payload);

        AuxiliaryData auxiliaryData = AuxiliaryData.builder().metadata(metadata).build();

        TransactionBody body = TransactionBody.builder()
                .inputs(inputs.stream()
                        .map(u -> new TransactionInput(u.outpoint().txHash(), u.outpoint().index()))
                        .toList())
                .outputs(List.of(TransactionOutput.builder()
                        .address(anchorAddress.getAddress())
                        .value(Value.builder()
                                .coin(BigInteger.valueOf(inputSum - fee))
                                .build())
                        .build()))
                .fee(BigInteger.valueOf(fee))
                .ttl(ttl)
                .auxiliaryDataHash(auxiliaryData.getAuxiliaryDataHash())
                .build();

        Transaction tx = Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .auxiliaryData(auxiliaryData)
                .build();
        return TransactionSigner.INSTANCE.sign(tx, anchorKey);
    }

    private List<org.yanoproject.api.utxo.model.Utxo> usableUtxos(UtxoState utxoState) {
        return utxoState.getUtxosByAddress(anchorAddress.getAddress(), 1, 50).stream()
                .filter(u -> u.lovelace() != null && u.lovelace().longValue() >= MIN_INPUT_LOVELACE)
                // pure-lovelace outputs only; the anchor wallet is expected to hold ADA only
                .filter(u -> u.assets() == null || u.assets().isEmpty())
                .toList();
    }

    /**
     * L1 delivery phase for one block (ADR-038 D4a). When the pending anchor tx is among the block's valid
     * transactions, the L1 fact is written durably before local completion is attempted; a completion that cannot
     * finish yet is retried by {@link #tick()} without an L1 replay. Redelivering the same block is a no-op.
     *
     * @return {@code DURABLE} once the fact is written, {@code NO_OP} when the block does not concern the pending
     *         anchor, or {@code RETRYABLE} when the fact could not be written
     */
    L1PhaseResult onL1Block(long slot, byte[] l1BlockHash, List<String> txHashes) {
        requireBlockHash(l1BlockHash);
        ConfirmedAnchor confirmed;
        synchronized (anchorLock) {
            PendingAnchor current = pending;
            if (current == null || txHashes == null || !txHashes.contains(current.txHash)) {
                return L1PhaseResult.NO_OP;
            }
            ObservedConfirmation fact = new ObservedConfirmation(current.fromHeight, current.toHeight,
                    current.txHash, slot, l1BlockHash.clone());
            try {
                if (!fact.sameAs(loadObservedConfirmation())) {
                    ledger.metaPutAll(Map.of(), Map.of(META_OBSERVED_CONFIRMATION, fact.encode()));
                }
            } catch (Throwable failure) {
                recordFailure("L1 observation", failure);
                return L1PhaseResult.retryable("ANCHOR_OBSERVATION_WRITE_FAILED");
            }
            confirmed = completeIfObserved();
        }
        notifyConfirmed(confirmed);
        return L1PhaseResult.DURABLE;
    }

    /**
     * Local completion of a durable observed fact: resolve the anchored app block and append the confirmation,
     * clearing the fact in the same write. Idempotent, keyed by the fact rather than by the in-memory pending anchor,
     * so it also completes after a restart. Callers hold {@link #anchorLock}.
     *
     * @return the completed anchor, or null when there is nothing to complete or completion must wait
     */
    private ConfirmedAnchor completeIfObserved() {
        try {
            if (!completionGate.getAsBoolean()) {
                return null;
            }
            ObservedConfirmation observed = loadObservedConfirmation();
            if (observed == null) {
                return null;
            }
            AppBlock anchoredBlock = blockByHeight.apply(observed.toHeight());
            if (anchoredBlock == null) {
                throw new IllegalStateException("Confirmed app block is not locally available");
            }
            byte[] anchoredBlockHash = AppBlockCodec.blockHash(anchoredBlock);
            // Callback code may re-enter this service while the monitor is held.
            // Never let a stale resolution complete a replaced or rolled-back fact.
            if (!observed.sameAs(loadObservedConfirmation())) {
                return null;
            }
            Confirmation confirmation = new Confirmation(observed.fromHeight(), observed.toHeight(),
                    observed.txHash(), observed.l1Slot(), anchoredBlockHash, observed.l1BlockHash());
            PendingAnchor current = pending;
            boolean completesPending = current != null && current.txHash.equals(observed.txHash());
            persistConfirmation(confirmation, historyWith(confirmation), completesPending);
            if (completesPending) {
                pending = null;
            }
            anchoredCount++;
            lastAnchoredL1Slot = observed.l1Slot();
            lastAnchorTxHash = observed.txHash();
            lastError = null;
            logInfoSafely("Anchor CONFIRMED on L1: tx={}, app blocks {}..{}, l1Slot={}",
                    observed.txHash(), observed.fromHeight(), observed.toHeight(), observed.l1Slot());
            return new ConfirmedAnchor(observed.fromHeight(), observed.toHeight(), observed.txHash(),
                    observed.l1Slot());
        } catch (Throwable failure) {
            recordFailure("L1 confirmation", failure);
            return null;
        }
    }

    /** Publishes a completion outside {@link #anchorLock}; the notification is best-effort. */
    private void notifyConfirmed(ConfirmedAnchor confirmed) {
        if (confirmed == null) {
            return;
        }
        try {
            confirmationListener.accept(confirmed);
        } catch (Throwable failure) {
            recordFailure("confirmation notification", failure);
        }
    }

    private ObservedConfirmation loadObservedConfirmation() {
        byte[] encoded = ledger.metaBytes(META_OBSERVED_CONFIRMATION);
        return encoded == null || encoded.length == 0 ? null : ObservedConfirmation.decode(encoded);
    }

    private void persistConfirmation(Confirmation confirmation, List<Confirmation> history,
                                     boolean completesPending) {
        Map<String, byte[]> byteValues = new LinkedHashMap<>();
        if (completesPending) {
            byteValues.put(META_PENDING_ANCHOR, new byte[0]);
        }
        byteValues.put(META_ANCHOR_BLOCK_HASH, confirmation.blockHash());
        byteValues.put(META_ANCHOR_TX,
                confirmation.txHash().getBytes(StandardCharsets.UTF_8));
        byteValues.put(META_ANCHOR_HISTORY, ConfirmationHistory.encode(history));
        byteValues.put(META_OBSERVED_CONFIRMATION, new byte[0]);
        ledger.metaPutAll(
                Map.of(
                        META_LAST_ANCHORED, confirmation.toHeight(),
                        // Range start of the last confirmed anchor — the
                        // precise rewind target after L1 rollback.
                        META_ANCHOR_FROM, confirmation.fromHeight(),
                        META_ANCHOR_SLOT, confirmation.l1Slot()),
                byteValues);
    }

    /**
     * L1 rollback phase (ADR-038 D4a): a durable fact or confirmation above {@code rollbackToSlot} is removed, so a
     * rolled-back anchor goes back to pending. {@code -1} rolls back to ORIGIN. Idempotent.
     *
     * @return {@code DURABLE} when state changed, {@code NO_OP} when nothing was above the target, or
     *         {@code RETRYABLE} when the rewind could not be written
     */
    L1PhaseResult onL1Rollback(long rollbackToSlot) {
        synchronized (anchorLock) {
            try {
                boolean changed = false;
                ObservedConfirmation observed = loadObservedConfirmation();
                if (observed != null && observed.l1Slot() > rollbackToSlot) {
                    ledger.metaPutAll(Map.of(), Map.of(META_OBSERVED_CONFIRMATION, new byte[0]));
                    changed = true;
                }
                if (rollbackConfirmedHistory(rollbackToSlot)) {
                    changed = true;
                }
                return changed ? L1PhaseResult.DURABLE : L1PhaseResult.NO_OP;
            } catch (Throwable failure) {
                recordFailure("L1 rollback", failure);
                return L1PhaseResult.retryable("ANCHOR_ROLLBACK_WRITE_FAILED");
            }
        }
    }

    /** The decoded confirmation journal, oldest first; empty when absent. */
    List<Confirmation> confirmationHistory() {
        return loadHistory();
    }

    /**
     * L1 evidence reconciliation (app-layer ADR-038, D8b): a dead observed fact is deleted. Read-only; the returned
     * stager joins the caller's single commit. Completion is gated off meanwhile.
     */
    Consumer<WriteBatch> reconcile(BiPredicate<Long, byte[]> canonicalAtSlot) {
        synchronized (anchorLock) {
            ObservedConfirmation observed = loadObservedConfirmation();
            if (observed == null || canonicalAtSlot.test(observed.l1Slot(), observed.l1BlockHash())) {
                return batch -> { };
            }
            return batch -> ledger.stageMetaBytes(batch, META_OBSERVED_CONFIRMATION, new byte[0]);
        }
    }

    /**
     * The confirmation journal's part of an L1 evidence reconciliation (D8b): a confirmation whose recorded L1 point
     * is no longer canonical is excluded from the frontier individually, by dropping its L1 hash. Metadata and script
     * anchors share the journal, so it is judged once. Read-only. An unreadable journal is left as it is: the
     * frontier already fails closed on it.
     */
    static Consumer<WriteBatch> reconcileHistory(AppLedgerStore ledger, BiPredicate<Long, byte[]> canonicalAtSlot) {
        byte[] encoded = ledger.metaBytes(META_ANCHOR_HISTORY);
        List<Confirmation> history;
        try {
            history = encoded == null || encoded.length == 0 ? List.of() : ConfirmationHistory.decode(encoded);
        } catch (IllegalArgumentException unreadable) {
            return batch -> { };
        }
        List<Confirmation> judged = new ArrayList<>(history.size());
        boolean changed = false;
        for (Confirmation confirmation : history) {
            boolean dead = confirmation.l1BlockHash() != null
                    && !canonicalAtSlot.test(confirmation.l1Slot(), confirmation.l1BlockHash());
            judged.add(dead ? new Confirmation(confirmation.fromHeight(), confirmation.toHeight(),
                    confirmation.txHash(), confirmation.l1Slot(), confirmation.blockHash(), null) : confirmation);
            changed |= dead;
        }
        if (!changed) {
            return batch -> { };
        }
        byte[] reconciled = ConfirmationHistory.encode(judged);
        return batch -> ledger.stageMetaBytes(batch, META_ANCHOR_HISTORY, reconciled);
    }

    private static void requireBlockHash(byte[] l1BlockHash) {
        if (l1BlockHash == null || l1BlockHash.length != 32) {
            throw new IllegalArgumentException("L1 block hash must be 32 bytes");
        }
    }

    private boolean rollbackConfirmedHistory(long rollbackToSlot) {
        long persistedSlot = ledger.metaLong(META_ANCHOR_SLOT, 0L);
        if (persistedSlot <= 0L || persistedSlot <= rollbackToSlot) {
            return false;
        }
        String rolledBackTx = ledger.metaString(META_ANCHOR_TX);
        List<Confirmation> retained = new ArrayList<>();
        for (Confirmation confirmation : loadHistory()) {
            if (confirmation.l1Slot() <= rollbackToSlot) {
                retained.add(confirmation);
            }
        }
        Confirmation survivor = retained.isEmpty() ? null : retained.getLast();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        longs.put(META_LAST_ANCHORED, survivor != null ? survivor.toHeight() : 0L);
        longs.put(META_ANCHOR_FROM, survivor != null ? survivor.fromHeight() : 0L);
        longs.put(META_ANCHOR_SLOT, survivor != null ? survivor.l1Slot() : 0L);
        bytes.put(META_ANCHOR_BLOCK_HASH,
                survivor != null ? survivor.blockHash() : new byte[0]);
        bytes.put(META_ANCHOR_TX, (survivor != null ? survivor.txHash() : "")
                .getBytes(StandardCharsets.UTF_8));
        bytes.put(META_ANCHOR_HISTORY, ConfirmationHistory.encode(retained));
        // Any in-flight range was derived from the now invalid frontier: drop it in the same write, so a retry can
        // never find the frontier rewound but the stale range still persisted.
        bytes.put(META_PENDING_ANCHOR, new byte[0]);
        ledger.metaPutAll(longs, bytes);
        pending = null;
        lastAnchorTxHash = survivor != null ? survivor.txHash() : null;
        lastAnchoredL1Slot = survivor != null ? survivor.l1Slot() : 0L;
        logWarnSafely("L1 rollback to slot {} un-confirmed anchor tx {} — rewound to app height {}",
                rollbackToSlot, rolledBackTx, survivor != null ? survivor.toHeight() : 0L);
        return true;
    }

    private List<Confirmation> historyWith(Confirmation next) {
        List<Confirmation> history = new ArrayList<>(loadHistory());
        if (history.isEmpty()) {
            Confirmation current = persistedSummary();
            if (current != null) {
                history.add(current);
            }
        }
        history.removeIf(item -> item.l1Slot() == next.l1Slot()
                && item.txHash().equals(next.txHash()));
        history.add(next);
        if (history.size() > ConfirmationHistory.MAX_ENTRIES) {
            history = new ArrayList<>(history.subList(
                    history.size() - ConfirmationHistory.MAX_ENTRIES, history.size()));
        }
        return history;
    }

    private List<Confirmation> loadHistory() {
        byte[] encoded = ledger.metaBytes(META_ANCHOR_HISTORY);
        if (encoded == null || encoded.length == 0) {
            return List.of();
        }
        try {
            return ConfirmationHistory.decode(encoded);
        } catch (Throwable failure) {
            recordFailure("confirmation history", failure);
            return List.of();
        }
    }

    private Confirmation persistedSummary() {
        long slot = ledger.metaLong(META_ANCHOR_SLOT, 0L);
        long to = ledger.metaLong(META_LAST_ANCHORED, 0L);
        String tx = ledger.metaString(META_ANCHOR_TX);
        byte[] hash = ledger.metaBytes(META_ANCHOR_BLOCK_HASH);
        if (slot <= 0 || tx == null || tx.isBlank() || hash == null || hash.length != 32) {
            return null;
        }
        return new Confirmation(ledger.metaLong(META_ANCHOR_FROM, 0L), to, tx, slot, hash, null);
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        status.put("address", anchorAddress.getAddress());
        status.put("lastAnchoredHeight", lastAnchoredHeight());
        status.put("anchoredCount", anchoredCount);
        PendingAnchor current = pending;
        if (current != null) {
            status.put("pendingTx", current.txHash);
            status.put("pendingRange", current.fromHeight + ".." + current.toHeight);
        }
        try {
            ObservedConfirmation observed = loadObservedConfirmation();
            if (observed != null) {
                status.put("confirmationObservedAtL1Slot", observed.l1Slot());
            }
        } catch (IllegalArgumentException unreadable) {
            status.put("confirmationObservedAtL1Slot", "unreadable");
        }
        // Prefer the in-memory copy; fall back to the PERSISTED meta so a
        // restart does not blank the last confirmed anchor in status/UI.
        String lastTx = lastAnchorTxHash != null ? lastAnchorTxHash : ledger.metaString(META_ANCHOR_TX);
        if (lastTx != null && !lastTx.isBlank()) {
            status.put("lastAnchorTx", lastTx);
            status.put("lastAnchorL1Slot", lastAnchoredL1Slot != 0
                    ? lastAnchoredL1Slot : ledger.metaLong(META_ANCHOR_SLOT, 0));
        }
        if (lastError != null) {
            status.put("lastError", lastError);
        }
        return status;
    }

    private void recordFailure(String phase, Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatal(failure);
        preserveInterrupt(failure);
        String errorType = failure.getClass().getName();
        lastError = "Anchor " + phase + " failed (errorType=" + errorType + ")";
        Throwable outcome = failure;
        try {
            log.warn("Anchor {} failed (errorType={})", phase, errorType);
        } catch (Throwable diagnosticFailure) {
            preserveInterrupt(diagnosticFailure);
            outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
        }
        LifecycleFailures.rethrowIfProcessFatal(outcome);
    }

    private void logInfoSafely(String format, Object... arguments) {
        guardDiagnostic(() -> log.info(format, arguments));
    }

    private void logWarnSafely(String format, Object... arguments) {
        guardDiagnostic(() -> log.warn(format, arguments));
    }

    private void guardDiagnostic(Runnable diagnostic) {
        try {
            diagnostic.run();
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            preserveInterrupt(failure);
        }
    }

    private static void preserveInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sets the pending anchor in memory first (the tx is already submitted), then persists it. If the write fails,
     * a restart forgets the anchor and resubmits the range after the timeout, as before ADR-038.
     */
    private void setPending(PendingAnchor next) {
        pending = next;
        ledger.metaPutAll(Map.of(), Map.of(META_PENDING_ANCHOR, next != null ? next.encode() : new byte[0]));
    }

    private PendingAnchor loadPending() {
        byte[] encoded = ledger.metaBytes(META_PENDING_ANCHOR);
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        try {
            return PendingAnchor.decode(encoded);
        } catch (IllegalArgumentException unreadable) {
            recordFailure("pending anchor", unreadable);
            return null;
        }
    }

    /** A restored pending anchor's resubmission timeout restarts from the load time. */
    private record PendingAnchor(long fromHeight, long toHeight, String txHash, long submittedAt) {
        private static final int MAGIC = 0x59415031; // YAP1

        byte[] encode() {
            byte[] tx = txHash.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(Integer.BYTES + 2 * Long.BYTES + Integer.BYTES + tx.length)
                    .putInt(MAGIC).putLong(fromHeight).putLong(toHeight).putInt(tx.length).put(tx).array();
        }

        static PendingAnchor decode(byte[] encoded) {
            ByteBuffer in = ByteBuffer.wrap(encoded);
            if (encoded.length < Integer.BYTES + 2 * Long.BYTES + Integer.BYTES || in.getInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid pending anchor");
            }
            long from = in.getLong();
            long to = in.getLong();
            int txLength = in.getInt();
            if (from < 0 || to < from || txLength <= 0 || txLength > ConfirmationHistory.MAX_TX_HASH_BYTES
                    || txLength != in.remaining()) {
                throw new IllegalArgumentException("Invalid pending anchor");
            }
            byte[] tx = new byte[txLength];
            in.get(tx);
            return new PendingAnchor(from, to, new String(tx, StandardCharsets.UTF_8), System.currentTimeMillis());
        }
    }

    /**
     * Durable L1 fact: anchor tx {@code txHash} for app heights {@code fromHeight..toHeight} was seen as a valid
     * transaction in L1 block {@code (l1Slot, l1BlockHash)}. Script mode records observed submits the same way.
     */
    record ObservedConfirmation(long fromHeight, long toHeight, String txHash, long l1Slot, byte[] l1BlockHash) {
        private static final int MAGIC = 0x59414f31; // YAO1

        ObservedConfirmation {
            Objects.requireNonNull(txHash, "txHash");
            requireBlockHash(l1BlockHash);
        }

        boolean sameAs(ObservedConfirmation other) {
            return other != null && fromHeight == other.fromHeight && toHeight == other.toHeight
                    && l1Slot == other.l1Slot && txHash.equals(other.txHash)
                    && Arrays.equals(l1BlockHash, other.l1BlockHash);
        }

        byte[] encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    byte[] tx = txHash.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(MAGIC);
                    out.writeLong(fromHeight);
                    out.writeLong(toHeight);
                    out.writeLong(l1Slot);
                    out.write(l1BlockHash);
                    out.writeInt(tx.length);
                    out.write(tx);
                }
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException("Observed anchor encoding failed", impossible);
            }
        }

        static ObservedConfirmation decode(byte[] encoded) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
                if (in.readInt() != MAGIC) {
                    throw new IllegalArgumentException("Invalid observed anchor magic");
                }
                long from = in.readLong();
                long to = in.readLong();
                long slot = in.readLong();
                byte[] hash = in.readNBytes(32);
                int txLength = in.readInt();
                if (hash.length != 32 || from < 0 || to < from || slot < 0
                        || txLength <= 0 || txLength > ConfirmationHistory.MAX_TX_HASH_BYTES) {
                    throw new IllegalArgumentException("Invalid observed anchor");
                }
                byte[] tx = in.readNBytes(txLength);
                if (tx.length != txLength || in.read() != -1) {
                    throw new IllegalArgumentException("Invalid observed anchor");
                }
                return new ObservedConfirmation(from, to, new String(tx, StandardCharsets.UTF_8), slot, hash);
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid observed anchor", failure);
            }
        }
    }

    /**
     * One confirmed anchor. {@code blockHash} is the anchored app block's hash; {@code l1BlockHash} is the L1
     * inclusion block's hash, or null for a legacy entry that recorded only the inclusion slot.
     */
    static record Confirmation(long fromHeight, long toHeight, String txHash, long l1Slot,
                               byte[] blockHash, byte[] l1BlockHash) {
    }

    /**
     * Bounded restart-safe journal used to rewind every anchor above an L1 rollback point. Version 2 also records
     * each entry's L1 inclusion block hash (ADR-038 D8a); version 1 journals still decode, as legacy entries.
     */
    static final class ConfirmationHistory {
        static final int MAX_ENTRIES = 256;
        private static final int MAGIC_V1 = 0x59414831; // YAH1
        private static final int MAGIC = 0x59414832; // YAH2
        private static final int MAX_TX_HASH_BYTES = 1_024;
        private static final int MAX_ENCODED_BYTES = 512 * 1_024;

        private ConfirmationHistory() {
        }

        static byte[] encode(List<Confirmation> history) {
            Objects.requireNonNull(history, "history");
            if (history.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Anchor confirmation history is too large");
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeInt(MAGIC);
                    out.writeInt(history.size());
                    for (Confirmation entry : history) {
                        byte[] tx = entry.txHash().getBytes(StandardCharsets.UTF_8);
                        byte[] hash = entry.blockHash();
                        if (tx.length == 0 || tx.length > MAX_TX_HASH_BYTES
                                || hash == null || hash.length != 32) {
                            throw new IllegalArgumentException("Invalid anchor confirmation history entry");
                        }
                        byte[] l1Hash = entry.l1BlockHash();
                        if (l1Hash != null && l1Hash.length != 32) {
                            throw new IllegalArgumentException("Invalid anchor confirmation history entry");
                        }
                        out.writeLong(entry.fromHeight());
                        out.writeLong(entry.toHeight());
                        out.writeLong(entry.l1Slot());
                        out.writeInt(tx.length);
                        out.write(tx);
                        out.write(hash);
                        out.writeBoolean(l1Hash != null);
                        if (l1Hash != null) {
                            out.write(l1Hash);
                        }
                    }
                }
                byte[] encoded = bytes.toByteArray();
                if (encoded.length > MAX_ENCODED_BYTES) {
                    throw new IllegalArgumentException("Anchor confirmation history encoding is too large");
                }
                return encoded;
            } catch (IOException impossible) {
                throw new IllegalStateException("Anchor confirmation history encoding failed", impossible);
            }
        }

        static List<Confirmation> decode(byte[] encoded) {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("Anchor confirmation history encoding is too large");
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
                int magic = in.readInt();
                if (magic != MAGIC && magic != MAGIC_V1) {
                    throw new IllegalArgumentException("Invalid anchor confirmation history magic");
                }
                int count = in.readInt();
                if (count < 0 || count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Invalid anchor confirmation history count");
                }
                List<Confirmation> history = new ArrayList<>(count);
                long previousSlot = -1;
                for (int i = 0; i < count; i++) {
                    long from = in.readLong();
                    long to = in.readLong();
                    long slot = in.readLong();
                    int txLength = in.readInt();
                    if (from < 0 || to < from || slot <= 0 || slot < previousSlot
                            || txLength <= 0 || txLength > MAX_TX_HASH_BYTES) {
                        throw new IllegalArgumentException("Invalid anchor confirmation history entry");
                    }
                    byte[] tx = in.readNBytes(txLength);
                    byte[] hash = in.readNBytes(32);
                    if (tx.length != txLength || hash.length != 32) {
                        throw new IllegalArgumentException("Truncated anchor confirmation history");
                    }
                    byte[] l1Hash = null;
                    if (magic == MAGIC && in.readBoolean()) {
                        l1Hash = in.readNBytes(32);
                        if (l1Hash.length != 32) {
                            throw new IllegalArgumentException("Truncated anchor confirmation history");
                        }
                    }
                    history.add(new Confirmation(from, to,
                            new String(tx, StandardCharsets.UTF_8), slot, hash, l1Hash));
                    previousSlot = slot;
                }
                if (in.read() != -1) {
                    throw new IllegalArgumentException("Trailing anchor confirmation history data");
                }
                return List.copyOf(history);
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid anchor confirmation history", failure);
            }
        }
    }

    record ConfirmedAnchor(long fromHeight, long toHeight, String txHash, long l1Slot) {
    }
}
