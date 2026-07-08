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
    private static final long ANCHOR_FEE_LOVELACE = 300_000;
    private static final long MIN_INPUT_LOVELACE = 1_500_000;
    private static final long RESUBMIT_AFTER_MS = 120_000;

    private static final String META_LAST_ANCHORED = "anchor_last_height";

    private final String chainId;
    private final AppChainConfig.AnchorConfig anchorConfig;
    private final AppLedgerStore ledger;
    private final Function<byte[], String> txSubmitter;
    private final Supplier<UtxoState> utxoStateSupplier;
    private final LongFunction<AppBlock> blockByHeight;
    private final Supplier<Long> tipHeightSupplier;
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

    String anchorAddress() {
        return anchorAddress.getAddress();
    }

    long lastAnchoredHeight() {
        return ledger.metaLong(META_LAST_ANCHORED, 0L);
    }

    /** Periodic tick from the subsystem scheduler. */
    void tick() {
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

    private Transaction buildAnchorTx(long fromHeight, long toHeight,
                                      byte[] blockHash, byte[] stateRoot) throws Exception {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null) {
            throw new IllegalStateException("UTXO state unavailable — cannot select anchor inputs");
        }
        com.bloxbean.cardano.yano.api.utxo.model.Utxo input = selectInput(utxoState);
        long inputLovelace = input.lovelace().longValue();

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
                .inputs(List.of(new TransactionInput(input.outpoint().txHash(), input.outpoint().index())))
                .outputs(List.of(TransactionOutput.builder()
                        .address(anchorAddress.getAddress())
                        .value(Value.builder()
                                .coin(BigInteger.valueOf(inputLovelace - ANCHOR_FEE_LOVELACE))
                                .build())
                        .build()))
                .fee(BigInteger.valueOf(ANCHOR_FEE_LOVELACE))
                .auxiliaryDataHash(auxiliaryData.getAuxiliaryDataHash())
                .build();

        Transaction tx = Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .auxiliaryData(auxiliaryData)
                .build();
        return TransactionSigner.INSTANCE.sign(tx, anchorKey);
    }

    private com.bloxbean.cardano.yano.api.utxo.model.Utxo selectInput(UtxoState utxoState) {
        List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos =
                utxoState.getUtxosByAddress(anchorAddress.getAddress(), 1, 50);
        return utxos.stream()
                .filter(u -> u.lovelace() != null && u.lovelace().longValue() >= MIN_INPUT_LOVELACE)
                // pure-lovelace outputs only; the anchor wallet is expected to hold ADA only
                .filter(u -> u.assets() == null || u.assets().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No usable UTxO (>= " + MIN_INPUT_LOVELACE + " lovelace) at anchor address "
                                + anchorAddress.getAddress() + " — fund the anchor wallet"));
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
            // Roll the marker back so the range re-anchors on the next tick
            long from = ledger.metaLong(META_LAST_ANCHORED, 0L);
            ledger.metaPutLong(META_LAST_ANCHORED, Math.max(0, from - anchorConfig.everyBlocks()));
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
