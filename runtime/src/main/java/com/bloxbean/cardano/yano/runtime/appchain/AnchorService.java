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
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
        if (pending != null) {
            return false;
        }
        long tip = tipHeightSupplier.get();
        long lastAnchored = lastAnchoredHeight();
        if (tip <= lastAnchored) {
            return false;
        }
        log.info("Force-anchor requested: anchoring app blocks {}..{}", lastAnchored + 1, tip);
        submitAnchor(lastAnchored + 1, tip);
        return pending != null; // submitAnchor sets pending on success
        }
    }

    private final Object anchorLock = new Object();

    /** Periodic tick from the subsystem scheduler. */
    void tick() {
        synchronized (anchorLock) {
        try {
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
                    ? System.currentTimeMillis() - lastAnchorAttemptAt >= anchorConfig.maxIntervalMinutes() * 60_000
                    : true; // first anchor: fire as soon as there is anything to anchor
            if (dueByCount || dueByTime) {
                submitAnchor(lastAnchored + 1, tip);
            }
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("Anchor tick failed: {}", e.toString());
        }
        }
    }

    private void submitAnchor(long fromHeight, long toHeight) {
        AppBlock tipBlock = blockByHeight.apply(toHeight);
        if (tipBlock == null) {
            return;
        }
        byte[] blockHash = AppBlockCodec.blockHash(tipBlock);

        try {
            Transaction tx = buildAnchorTx(fromHeight, toHeight, blockHash, tipBlock.stateRoot());
            byte[] cbor = tx.serialize();
            String txHash = txSubmitter.apply(cbor);
            pending = new PendingAnchor(fromHeight, toHeight, txHash, System.currentTimeMillis());
            lastAnchorAttemptAt = System.currentTimeMillis();
            lastError = null;
            log.info("Anchor tx submitted: {} (app blocks {}..{}, stateRoot={})",
                    txHash, fromHeight, toHeight, HexUtil.encodeHexString(tipBlock.stateRoot()));
        } catch (Exception e) {
            lastError = e.toString();
            lastAnchorAttemptAt = System.currentTimeMillis();
            log.warn("Anchor tx build/submit failed for app blocks {}..{}: {}",
                    fromHeight, toHeight, e.toString());
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
        PendingAnchor current = pending;
        if (current == null || txHashes == null || !txHashes.contains(current.txHash)) {
            return null;
        }
        pending = null;
        ledger.metaPutLong(META_LAST_ANCHORED, current.toHeight);
        // Range start of the last confirmed anchor — the precise rewind target
        // when an L1 rollback un-confirms it (008.1 I1.5)
        ledger.metaPutLong(META_ANCHOR_FROM, current.fromHeight);
        // Persist the confirmed-anchor record so evidence bundles can reference
        // the anchored block hash + L1 tx (audit export, ADR 006 E3.4).
        AppBlock anchoredBlock = blockByHeight.apply(current.toHeight);
        if (anchoredBlock != null) {
            ledger.metaPutBytes(META_ANCHOR_BLOCK_HASH, AppBlockCodec.blockHash(anchoredBlock));
        }
        ledger.metaPutString(META_ANCHOR_TX, current.txHash);
        ledger.metaPutLong(META_ANCHOR_SLOT, slot);
        anchoredCount++;
        lastAnchoredL1Slot = slot;
        lastAnchorTxHash = current.txHash;
        log.info("Anchor CONFIRMED on L1: tx={}, app blocks {}..{}, l1Slot={}",
                current.txHash, current.fromHeight, current.toHeight, slot);
        return new ConfirmedAnchor(current.fromHeight, current.toHeight, current.txHash, slot);
    }

    /** Called on L1 rollback: a confirmed-but-now-rolled-back anchor goes back to pending. */
    void onL1Rollback(long rollbackToSlot) {
        if (lastAnchoredL1Slot > rollbackToSlot && lastAnchorTxHash != null) {
            log.warn("L1 rollback to slot {} un-confirmed anchor tx {} — will re-anchor",
                    rollbackToSlot, lastAnchorTxHash);
            // Rewind precisely to the start of the rolled-back anchor's range so
            // the FULL range re-anchors, however many intervals it spanned
            long anchorFrom = ledger.metaLong(META_ANCHOR_FROM, 0L);
            ledger.metaPutLong(META_LAST_ANCHORED, Math.max(0, anchorFrom - 1));
            // Clear the confirmed-anchor record too — otherwise evidence() would
            // emit an AnchorRef pointing at the rolled-back (now-invalid) block
            // hash, which an offline auditor would reject. Evidence stays valid
            // as finality-only until the next anchor confirms.
            ledger.metaPutBytes(META_ANCHOR_BLOCK_HASH, new byte[0]);
            ledger.metaPutString(META_ANCHOR_TX, "");
            lastAnchorTxHash = null;
            lastAnchoredL1Slot = 0;
        }
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
        if (lastAnchorTxHash != null) {
            status.put("lastAnchorTx", lastAnchorTxHash);
            status.put("lastAnchorL1Slot", lastAnchoredL1Slot);
        }
        if (lastError != null) {
            status.put("lastError", lastError);
        }
        return status;
    }

    private record PendingAnchor(long fromHeight, long toHeight, String txHash, long submittedAt) {
    }

    record ConfirmedAnchor(long fromHeight, long toHeight, String txHash, long l1Slot) {
    }
}
