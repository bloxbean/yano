package com.bloxbean.cardano.yano.runtime.appchain;

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
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final String META_ANCHOR_HISTORY = "anchor_confirmation_history_v1";

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
    /** L1 inclusion was observed; local block resolution/atomic persistence may still need retrying. */
    private volatile ObservedConfirmation observedConfirmation;
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
    }

    void wireFees(Supplier<FeeParams> feeParams, Supplier<Long> currentSlot) {
        this.feeParamsSupplier = feeParams;
        this.currentSlotSupplier = currentSlot;
    }

    String anchorAddress() {
        return anchorAddress.getAddress();
    }

    long lastAnchoredHeight() {
        return ledger.metaLong(META_LAST_ANCHORED, 0L);
    }

    /**
     * Anchor the current tip now, ignoring the every-blocks/interval schedule
     * (admin force-anchor, ADR 006 E5.4). No-op if an anchor tx is already
     * pending or there is nothing new to anchor.
     * @return true if an anchor submission was triggered
     */
    boolean forceAnchorNow() {
        synchronized (anchorLock) {
            try {
                if (observedConfirmation != null) {
                    completeObservedConfirmation();
                    return false;
                }
                if (pending != null || submissionInProgress) {
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
    }

    private final Object anchorLock = new Object();

    /** Periodic tick from the subsystem scheduler. */
    void tick() {
        synchronized (anchorLock) {
            try {
                if (observedConfirmation != null) {
                    completeObservedConfirmation();
                    return;
                }
                if (submissionInProgress) {
                    return;
                }
                PendingAnchor current = pending;
                if (current != null) {
                    if (System.currentTimeMillis() - current.submittedAt > RESUBMIT_AFTER_MS) {
                        log.warn("Anchor tx {} not observed on L1 within {}ms — resubmitting",
                                current.txHash, RESUBMIT_AFTER_MS);
                        pending = null;
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
            pending = new PendingAnchor(fromHeight, toHeight, txHash, System.currentTimeMillis());
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
        List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> candidates = usableUtxos(utxoState);
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
            List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> inputs = candidates.subList(0, count);
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

    private Transaction assembleAnchorTx(List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> inputs,
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

    private List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> usableUtxos(UtxoState utxoState) {
        return utxoState.getUtxosByAddress(anchorAddress.getAddress(), 1, 50).stream()
                .filter(u -> u.lovelace() != null && u.lovelace().longValue() >= MIN_INPUT_LOVELACE)
                // pure-lovelace outputs only; the anchor wallet is expected to hold ADA only
                .filter(u -> u.assets() == null || u.assets().isEmpty())
                .toList();
    }

    /**
     * Called for every applied L1 block; marks the pending anchor confirmed
     * when its tx hash appears.
     * @return the confirmed anchor, or null
     */
    ConfirmedAnchor onL1Block(long slot, List<String> txHashes) {
        synchronized (anchorLock) {
            try {
                if (observedConfirmation != null) {
                    return completeObservedConfirmation();
                }
                PendingAnchor current = pending;
                if (current == null || txHashes == null || !txHashes.contains(current.txHash)) {
                    return null;
                }
                if (pending != current || observedConfirmation != null) {
                    return null;
                }
                // Remember the L1 fact BEFORE resolving local callbacks. If
                // block lookup/storage is transiently unavailable, tick/force
                // reconciles this exact observation without an L1 replay and
                // without timing out/resubmitting an already-confirmed tx.
                observedConfirmation = new ObservedConfirmation(current, slot);
                return completeObservedConfirmation();
            } catch (Throwable failure) {
                recordFailure("L1 observation", failure);
                return null;
            }
        }
    }

    private ConfirmedAnchor completeObservedConfirmation() {
        ObservedConfirmation observed = observedConfirmation;
        if (observed == null) {
            return null;
        }
        PendingAnchor current = observed.anchor();
        try {
            AppBlock anchoredBlock = blockByHeight.apply(current.toHeight);
            if (anchoredBlock == null) {
                throw new IllegalStateException("Confirmed app block is not locally available");
            }
            byte[] anchoredBlockHash = AppBlockCodec.blockHash(anchoredBlock);

            // Callback code may re-enter this service even while the monitor
            // is held. Never let a stale resolution clear a replacement tx.
            if (observedConfirmation != observed || pending != current) {
                return null;
            }
            Confirmation confirmation = new Confirmation(current.fromHeight, current.toHeight,
                    current.txHash, observed.l1Slot(), anchoredBlockHash);
            List<Confirmation> history = historyWith(confirmation);
            if (observedConfirmation != observed || pending != current) {
                return null;
            }
            persistConfirmation(confirmation, history);

            pending = null;
            observedConfirmation = null;
            anchoredCount++;
            lastAnchoredL1Slot = observed.l1Slot();
            lastAnchorTxHash = current.txHash;
            lastError = null;
            logInfoSafely("Anchor CONFIRMED on L1: tx={}, app blocks {}..{}, l1Slot={}",
                    current.txHash, current.fromHeight, current.toHeight, observed.l1Slot());
            return new ConfirmedAnchor(current.fromHeight, current.toHeight, current.txHash,
                    observed.l1Slot());
        } catch (Throwable failure) {
            recordFailure("L1 confirmation", failure);
            return null;
        }
    }

    private void persistConfirmation(Confirmation confirmation, List<Confirmation> history) {
        Map<String, byte[]> byteValues = new LinkedHashMap<>();
        byteValues.put(META_ANCHOR_BLOCK_HASH, confirmation.blockHash());
        byteValues.put(META_ANCHOR_TX,
                confirmation.txHash().getBytes(StandardCharsets.UTF_8));
        byteValues.put(META_ANCHOR_HISTORY, ConfirmationHistory.encode(history));
        ledger.metaPutAll(
                Map.of(
                        META_LAST_ANCHORED, confirmation.toHeight(),
                        // Range start of the last confirmed anchor — the
                        // precise rewind target after L1 rollback.
                        META_ANCHOR_FROM, confirmation.fromHeight(),
                        META_ANCHOR_SLOT, confirmation.l1Slot()),
                byteValues);
    }

    /** Called on L1 rollback: a confirmed-but-now-rolled-back anchor goes back to pending. */
    void onL1Rollback(long rollbackToSlot) {
        synchronized (anchorLock) {
            try {
                ObservedConfirmation observed = observedConfirmation;
                if (observed != null && observed.l1Slot() > rollbackToSlot) {
                    observedConfirmation = null;
                }
                if (rollbackConfirmedHistory(rollbackToSlot)) {
                    // Any later in-flight range was derived from the now
                    // invalid confirmation frontier. Drop it so the next tick
                    // covers the full surviving-height+1..tip range.
                    pending = null;
                    observedConfirmation = null;
                }
            } catch (Throwable failure) {
                recordFailure("L1 rollback", failure);
            }
        }
    }

    private boolean rollbackConfirmedHistory(long rollbackToSlot) {
        long persistedSlot = ledger.metaLong(META_ANCHOR_SLOT, 0L);
        if (persistedSlot <= rollbackToSlot) {
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
        ledger.metaPutAll(longs, bytes);
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
        return new Confirmation(ledger.metaLong(META_ANCHOR_FROM, 0L), to, tx, slot, hash);
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
        ObservedConfirmation observed = observedConfirmation;
        if (observed != null) {
            status.put("confirmationObservedAtL1Slot", observed.l1Slot());
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

    private record PendingAnchor(long fromHeight, long toHeight, String txHash, long submittedAt) {
    }

    private record ObservedConfirmation(PendingAnchor anchor, long l1Slot) {
    }

    static record Confirmation(long fromHeight, long toHeight, String txHash, long l1Slot,
                               byte[] blockHash) {
    }

    /** Bounded restart-safe journal used to rewind every anchor above an L1 rollback point. */
    static final class ConfirmationHistory {
        static final int MAX_ENTRIES = 256;
        private static final int MAGIC = 0x59414831; // YAH1
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
                        out.writeLong(entry.fromHeight());
                        out.writeLong(entry.toHeight());
                        out.writeLong(entry.l1Slot());
                        out.writeInt(tx.length);
                        out.write(tx);
                        out.write(hash);
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
                if (in.readInt() != MAGIC) {
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
                    history.add(new Confirmation(from, to,
                            new String(tx, StandardCharsets.UTF_8), slot, hash));
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
