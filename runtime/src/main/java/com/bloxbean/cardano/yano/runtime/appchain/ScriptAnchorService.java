package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * L1 anchoring, script mode A2 (ADR app-layer/008.4): the app chain's state
 * commitments advance a single script-locked UTxO (identified by a one-shot
 * thread NFT) whose validator enforces monotonic height and a member
 * signature threshold. Anchor txs are co-signed over the app channel:
 *
 * <ol>
 *   <li>the anchor leader (the node with {@code anchor.enabled}) builds the
 *       unsigned spend and broadcasts the body on {@code ~anchor/sign}</li>
 *   <li>each member independently verifies the advance against its OWN
 *       ledger and replies with a body-hash witness on {@code ~anchor/sig}</li>
 *   <li>the leader assembles the required witnesses and submits through the
 *       node's own tx path; confirmation/rollback tracking mirrors the
 *       metadata mode (008.1 I1.5)</li>
 * </ol>
 *
 * <p>The body lists the exact signer set as required-signers (an L1 rule:
 * every listed key must witness), so round 1 targets ALL current members;
 * if some don't respond before the round deadline, the leader rebuilds the
 * body with the responsive subset — provided it still meets the on-chain
 * threshold — and re-collects (signatures are body-specific).
 */
final class ScriptAnchorService {

    static final String TOPIC_SIGN = "~anchor/sign";
    static final String TOPIC_SIG = "~anchor/sig";

    private static final long MIN_INPUT_LOVELACE = 1_000_000;
    private static final long RESUBMIT_AFTER_MS = 120_000;
    /** Co-sign round deadline before falling back to the responsive subset. */
    private static final long COSIGN_ROUND_TIMEOUT_MS = 30_000;

    /** Tx construction never submits through cardano-client-lib. */
    private static final TransactionProcessor NO_SUBMIT = new TransactionProcessor() {
        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            throw new UnsupportedOperationException("Anchor txs submit through the node's own tx path");
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor,
                java.util.Set<com.bloxbean.cardano.client.api.model.Utxo> inputUtxos) {
            throw new UnsupportedOperationException("Ex-units come from the julc evaluator");
        }
    };

    // Confirmation meta (same keys as metadata mode — one anchor mode per chain)
    private static final String META_LAST_ANCHORED = "anchor_last_height";
    private static final String META_ANCHOR_BLOCK_HASH = "anchor_last_block_hash";
    private static final String META_ANCHOR_TX = "anchor_last_tx";
    private static final String META_ANCHOR_SLOT = "anchor_last_slot";
    private static final String META_ANCHOR_FROM = "anchor_last_from_height";
    // Script-anchor identity meta (008.4)
    private static final String META_SCRIPT_POLICY_ID = "anchor_script_policy_id";
    private static final String META_SCRIPT_HASH = "anchor_script_hash";
    private static final String META_SCRIPT_BOOTSTRAP_TX = "anchor_script_bootstrap_tx";
    private static final String META_SCRIPT_BOOTSTRAP_SLOT = "anchor_script_bootstrap_slot";

    private final String chainId;
    private final AppChainConfig.AnchorConfig anchorConfig;
    private final AppLedgerStore ledger;
    private final Function<byte[], String> txSubmitter;
    private final Supplier<UtxoState> utxoStateSupplier;
    private final LongFunction<AppBlock> blockByHeight;
    private final Supplier<Long> tipHeightSupplier;
    private final AnchorScriptArtifacts artifacts;
    /** This node's member identity — signs co-sign witnesses. */
    private final SignerProvider memberSigner;
    /** Current members (hex Ed25519 keys) + threshold, live from the group. */
    private final Supplier<Set<String>> membersSupplier;
    private final IntSupplier thresholdSupplier;
    /** Diffuses a framework message on a system topic (subsystem transport). */
    private final BiConsumer<String, byte[]> diffuser;
    private final boolean leader;
    private final Network network;
    private final SecretKey walletKey;
    private final Address walletAddress;
    private final Logger log;

    /** Node-tracked protocol params (CCL model); null values → Conway defaults. */
    private volatile Supplier<ProtocolParams> protocolParamsSupplier;
    private volatile Supplier<Long> currentSlotSupplier;
    /** QuickTx sees the node's own UTxO store — we ARE the backend. */
    private final NodeUtxoSupplier cclUtxoSupplier;

    private final Object anchorLock = new Object();
    private volatile PendingBootstrap pendingBootstrap;
    private volatile PendingCosign pendingCosign;
    private volatile PendingSubmit pendingSubmit;
    private volatile long lastAnchorAttemptAt;
    private volatile String lastError;
    private volatile long anchoredCount;
    private volatile long lastAnchoredL1Slot;
    private volatile String lastAnchorTxHash;

    ScriptAnchorService(String chainId,
                        AppChainConfig.AnchorConfig anchorConfig,
                        AppLedgerStore ledger,
                        Function<byte[], String> txSubmitter,
                        Supplier<UtxoState> utxoStateSupplier,
                        LongFunction<AppBlock> blockByHeight,
                        Supplier<Long> tipHeightSupplier,
                        AnchorScriptArtifacts artifacts,
                        SignerProvider memberSigner,
                        Supplier<Set<String>> membersSupplier,
                        IntSupplier thresholdSupplier,
                        BiConsumer<String, byte[]> diffuser,
                        boolean leader,
                        long protocolMagic,
                        Logger log) {
        this.chainId = chainId;
        this.anchorConfig = Objects.requireNonNull(anchorConfig, "anchorConfig");
        this.ledger = ledger;
        this.txSubmitter = txSubmitter;
        this.utxoStateSupplier = utxoStateSupplier;
        this.blockByHeight = blockByHeight;
        this.tipHeightSupplier = tipHeightSupplier;
        this.artifacts = artifacts;
        this.memberSigner = memberSigner;
        this.membersSupplier = membersSupplier;
        this.thresholdSupplier = thresholdSupplier;
        this.diffuser = diffuser;
        this.leader = leader;
        this.network = protocolMagic == 764824073L
                ? new Network(1, protocolMagic)
                : new Network(0, protocolMagic);
        this.cclUtxoSupplier = new NodeUtxoSupplier(utxoStateSupplier);
        this.log = log;
        if (leader) {
            byte[] seed = HexUtil.decodeHexString(anchorConfig.signingKeyHex().trim());
            if (seed.length != 32)
                throw new IllegalArgumentException("Anchor signing key must be a 32-byte Ed25519 seed (hex)");
            try {
                this.walletKey = SecretKey.create(seed);
                VerificationKey vk = KeyGenUtil.getPublicKeyFromPrivateKey(this.walletKey);
                this.walletAddress = AddressProvider.getEntAddress(
                        Credential.fromKey(KeyGenUtil.getKeyHash(vk)), network);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid anchor signing key", e);
            }
            log.info("App-chain script-anchor wallet address: {}", walletAddress.getAddress());
        } else {
            this.walletKey = null;
            this.walletAddress = null;
        }
    }

    void wireTxPricing(Supplier<ProtocolParams> protocolParams, Supplier<Long> currentSlot) {
        this.protocolParamsSupplier = protocolParams;
        this.currentSlotSupplier = currentSlot;
    }

    String anchorAddress() {
        byte[] scriptHash = ledger.metaBytes(META_SCRIPT_HASH);
        if (scriptHash != null && scriptHash.length == 28) {
            return AddressProvider.getEntAddress(Credential.fromScript(scriptHash), network).getAddress();
        }
        return walletAddress != null ? walletAddress.getAddress() : "";
    }

    long lastAnchoredHeight() {
        return ledger.metaLong(META_LAST_ANCHORED, 0L);
    }

    boolean bootstrapped() {
        byte[] policyId = ledger.metaBytes(META_SCRIPT_POLICY_ID);
        return policyId != null && policyId.length == 28;
    }

    // ------------------------------------------------------------------
    // Bootstrap (admin action, leader only)
    // ------------------------------------------------------------------

    /**
     * Mint the thread NFT and lock the initial datum at the anchor validator
     * (ADR 008.4 §2.5). Identity — thread policy id + validator hash — derives
     * from the seed UTxO consumed here; it is persisted on L1 confirmation.
     * Needs no member signatures: identity starts at the minted token.
     */
    Map<String, Object> bootstrap() {
        if (!leader)
            throw new IllegalStateException("Script-anchor bootstrap must run on the anchor node "
                    + "(anchor.enabled: true)");
        synchronized (anchorLock) {
            if (bootstrapped()) {
                Map<String, Object> existing = identityStatus();
                existing.put("alreadyBootstrapped", true);
                return existing;
            }
            if (pendingBootstrap != null) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("pendingTx", pendingBootstrap.txHash());
                return pending;
            }
            try {
                BuiltBootstrap built = buildBootstrapTx();
                String txHash = txSubmitter.apply(built.txCbor());
                pendingBootstrap = new PendingBootstrap(txHash, built.policyId(), built.scriptHash(),
                        System.currentTimeMillis());
                lastError = null;
                log.info("Script-anchor bootstrap tx submitted: {} (policyId={}, scriptAddress={})",
                        txHash, HexUtil.encodeHexString(built.policyId()), built.scriptAddress());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("txHash", txHash);
                result.put("threadPolicyId", HexUtil.encodeHexString(built.policyId()));
                result.put("scriptHash", HexUtil.encodeHexString(built.scriptHash()));
                result.put("scriptAddress", built.scriptAddress());
                return result;
            } catch (Exception e) {
                lastError = e.toString();
                throw new IllegalStateException("Script-anchor bootstrap failed: " + e.getMessage(), e);
            }
        }
    }

    private record BuiltBootstrap(byte[] txCbor, byte[] policyId, byte[] scriptHash, String scriptAddress) {
    }

    private BuiltBootstrap buildBootstrapTx() throws Exception {
        List<Utxo> candidates = usableWalletUtxos();
        if (candidates.isEmpty())
            throw new IllegalStateException("No usable UTxO (pure ADA, >= " + MIN_INPUT_LOVELACE
                    + " lovelace) at anchor wallet " + walletAddress.getAddress() + " — fund the wallet");

        Utxo seed = candidates.get(0);
        byte[] seedTxId = HexUtil.decodeHexString(seed.outpoint().txHash());
        PlutusV3Script policy = artifacts.threadPolicy(seedTxId, seed.outpoint().index());
        byte[] policyId = AnchorScriptArtifacts.scriptHash(policy);
        PlutusV3Script validator = artifacts.validator(policyId);
        byte[] scriptHash = AnchorScriptArtifacts.scriptHash(validator);
        Address scriptAddress = AnchorScriptArtifacts.scriptAddress(validator, network);

        AnchorDatumCodec.AnchorDatum datum = datumAtTip();

        // Consume the seed (one-shot identity), mint the thread NFT straight
        // to the validator with the inline genesis datum; QuickTx balances
        // fees/collateral/change and the julc evaluator prices exact ex-units
        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(NodeUtxoSupplier.toCcl(seed))
                .mintAsset(policy,
                        List.of(Asset.builder().name("0x").value(BigInteger.ONE).build()),
                        BigIntPlutusData.of(0),
                        scriptAddress.getAddress(),
                        AnchorDatumCodec.encode(datum));
        Transaction tx = txContext(scriptTx, 0).buildAndSign();
        return new BuiltBootstrap(tx.serialize(), policyId, scriptHash, scriptAddress.getAddress());
    }

    // ------------------------------------------------------------------
    // Leader: periodic tick, co-sign rounds, assembly
    // ------------------------------------------------------------------

    void tick() {
        if (!leader)
            return;
        synchronized (anchorLock) {
            try {
                PendingBootstrap bootstrap = pendingBootstrap;
                if (bootstrap != null
                        && System.currentTimeMillis() - bootstrap.submittedAt() > RESUBMIT_AFTER_MS) {
                    log.warn("Script-anchor bootstrap tx {} not observed on L1 within {}ms — clearing "
                            + "(re-run bootstrap; the seed UTxO may have been spent)",
                            bootstrap.txHash(), RESUBMIT_AFTER_MS);
                    pendingBootstrap = null;
                }
                if (!bootstrapped())
                    return;

                PendingSubmit submit = pendingSubmit;
                if (submit != null) {
                    if (System.currentTimeMillis() - submit.submittedAt() > RESUBMIT_AFTER_MS) {
                        log.warn("Script-anchor tx {} not observed on L1 within {}ms — restarting co-sign",
                                submit.txHash(), RESUBMIT_AFTER_MS);
                        pendingSubmit = null;
                        startCosignRound(null);
                    }
                    return;
                }

                PendingCosign cosign = pendingCosign;
                if (cosign != null) {
                    if (cosignComplete(cosign)) {
                        assembleAndSubmit(cosign);
                    } else if (System.currentTimeMillis() - cosign.startedAt() > COSIGN_ROUND_TIMEOUT_MS) {
                        retryWithResponsiveSubset(cosign);
                    } else {
                        // Nudge: re-diffuse the same request for members that missed it
                        diffuser.accept(TOPIC_SIGN, cosign.requestBody());
                    }
                    return;
                }

                long tip = tipHeightSupplier.get();
                long lastAnchored = lastAnchoredHeight();
                if (tip <= lastAnchored)
                    return;
                boolean dueByCount = tip - lastAnchored >= anchorConfig.everyBlocks();
                boolean dueByTime = lastAnchorAttemptAt > 0
                        ? System.currentTimeMillis() - lastAnchorAttemptAt
                                >= anchorConfig.maxIntervalMinutes() * 60_000
                        : true;
                if (dueByCount || dueByTime) {
                    startCosignRound(null);
                }
            } catch (Exception e) {
                lastError = e.toString();
                log.warn("Script-anchor tick failed: {}", e.toString());
            }
        }
    }

    boolean forceAnchorNow() {
        if (!leader)
            return false;
        synchronized (anchorLock) {
            if (!bootstrapped() || pendingSubmit != null || pendingCosign != null)
                return false;
            if (tipHeightSupplier.get() <= lastAnchoredHeight())
                return false;
            startCosignRound(null);
            return pendingCosign != null || pendingSubmit != null;
        }
    }

    /**
     * Build the advance body for the given signer set (null = all current
     * members), self-sign, and broadcast for co-signing. With a single member
     * (solo chain) the round completes immediately.
     */
    private void startCosignRound(Set<String> signerSubsetHex) {
        try {
            Set<String> signers = signerSubsetHex != null
                    ? signerSubsetHex
                    : new TreeSet<>(membersSupplier.get());
            int threshold = thresholdSupplier.getAsInt();
            if (signers.size() < threshold) {
                lastError = "Co-sign signer set (" + signers.size()
                        + ") below on-chain threshold (" + threshold + ") — anchoring paused";
                log.warn("Script-anchor: {}", lastError);
                return;
            }
            BuiltAdvance built = buildAdvanceTx(signers);
            if (built == null)
                return; // nothing to anchor / anchor UTxO not visible yet

            PendingCosign cosign = new PendingCosign(built.tx(), built.bodyBytes(), built.bodyHash(),
                    built.requestBody(), signers, new ConcurrentHashMap<>(),
                    built.fromHeight(), built.toHeight(), System.currentTimeMillis());
            // Leader is a member too — its witness comes first
            String selfHex = memberSigner.publicKeyHex().toLowerCase(Locale.ROOT);
            if (signers.contains(selfHex)) {
                cosign.signatures().put(selfHex, memberSigner.sign(built.bodyHash()));
            }
            pendingCosign = cosign;
            lastAnchorAttemptAt = System.currentTimeMillis();
            diffuser.accept(TOPIC_SIGN, built.requestBody());
            log.info("Script-anchor co-sign round started: app blocks {}..{}, signers={}, bodyHash={}",
                    built.fromHeight(), built.toHeight(), signers.size(),
                    HexUtil.encodeHexString(built.bodyHash()));
            if (cosignComplete(cosign)) {
                assembleAndSubmit(cosign);
            }
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("Script-anchor co-sign round failed to start: {}", e.toString());
        }
    }

    private boolean cosignComplete(PendingCosign cosign) {
        return cosign.signatures().keySet().containsAll(cosign.signerSetHex());
    }

    private void retryWithResponsiveSubset(PendingCosign cosign) {
        if (cosignComplete(cosign)) {
            assembleAndSubmit(cosign);
            return;
        }
        Set<String> responsive = new TreeSet<>(cosign.signatures().keySet());
        pendingCosign = null;
        int threshold = thresholdSupplier.getAsInt();
        if (responsive.size() >= threshold) {
            log.warn("Script-anchor co-sign round timed out with {}/{} signatures — retrying with "
                    + "the responsive subset", responsive.size(), cosign.signerSetHex().size());
            startCosignRound(responsive);
        } else {
            lastError = "Co-sign round timed out below threshold (" + responsive.size()
                    + "/" + threshold + ") — will retry next tick";
            log.warn("Script-anchor: {}", lastError);
        }
    }

    private void assembleAndSubmit(PendingCosign cosign) {
        // A witness can complete the round WHILE startCosignRound/tick still
        // hold a reference (the lock is reentrant) — only the current pending
        // round may submit, exactly once
        if (pendingCosign != cosign)
            return;
        try {
            Transaction tx = cosign.tx();
            List<VkeyWitness> vkeyWitnesses = tx.getWitnessSet().getVkeyWitnesses();
            if (vkeyWitnesses == null) {
                vkeyWitnesses = new ArrayList<>();
                tx.getWitnessSet().setVkeyWitnesses(vkeyWitnesses);
            }
            for (Map.Entry<String, byte[]> entry : cosign.signatures().entrySet()) {
                vkeyWitnesses.add(VkeyWitness.builder()
                        .vkey(HexUtil.decodeHexString(entry.getKey()))
                        .signature(entry.getValue())
                        .build());
            }
            // The witnesses signed cosign.bodyHash — refuse to submit if the
            // assembled tx would hash differently (serialization drift guard)
            String assembledHash = TransactionUtil.getTxHash(tx);
            if (!assembledHash.equals(HexUtil.encodeHexString(cosign.bodyHash()))) {
                pendingCosign = null;
                lastError = "Assembled tx hash differs from co-signed body hash — round aborted";
                log.error("Script-anchor: {} (assembled={}, signed={})", lastError,
                        assembledHash, HexUtil.encodeHexString(cosign.bodyHash()));
                return;
            }
            String txHash = txSubmitter.apply(tx.serialize());
            pendingCosign = null;
            pendingSubmit = new PendingSubmit(cosign.fromHeight(), cosign.toHeight(), txHash,
                    System.currentTimeMillis());
            lastError = null;
            log.info("Script-anchor tx submitted: {} (app blocks {}..{}, {} member witnesses)",
                    txHash, cosign.fromHeight(), cosign.toHeight(), cosign.signatures().size());
        } catch (Exception e) {
            pendingCosign = null;
            lastError = e.toString();
            log.warn("Script-anchor assemble/submit failed: {}", e.toString());
        }
    }

    private record BuiltAdvance(Transaction tx, byte[] bodyBytes, byte[] bodyHash, byte[] requestBody,
                                long fromHeight, long toHeight) {
    }

    private BuiltAdvance buildAdvanceTx(Set<String> signersHex) throws Exception {
        byte[] policyId = ledger.metaBytes(META_SCRIPT_POLICY_ID);
        byte[] scriptHash = ledger.metaBytes(META_SCRIPT_HASH);
        Utxo anchorUtxo = findAnchorUtxo(policyId, scriptHash);
        if (anchorUtxo == null) {
            log.debug("Script-anchor: anchor UTxO not visible on the local L1 view yet");
            return null;
        }
        AnchorDatumCodec.AnchorDatum prev = AnchorDatumCodec.decode(
                com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(anchorUtxo.inlineDatum()));
        long tip = tipHeightSupplier.get();
        if (tip <= prev.height())
            return null;
        AnchorDatumCodec.AnchorDatum next = datumAtTip();
        if (next == null)
            return null;

        PlutusV3Script validator = artifacts.validator(policyId);
        Address scriptAddress = AddressProvider.getEntAddress(
                Credential.fromScript(scriptHash), network);

        // Required signers are body-fixed BEFORE co-signing: the members'
        // witnesses attach later, so additionalSignersCount prices them in
        byte[][] requiredSigners = signersHex.stream()
                .map(hex -> Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(hex)))
                .sorted(Comparator.comparing(HexUtil::encodeHexString))
                .toArray(byte[][]::new);

        // Continuing output: same lovelace + thread NFT, next inline datum
        List<Amount> continuing = new ArrayList<>();
        continuing.add(Amount.lovelace(anchorUtxo.lovelace()));
        continuing.add(Amount.asset(HexUtil.encodeHexString(policyId), BigInteger.ONE));

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(NodeUtxoSupplier.toCcl(anchorUtxo), BigIntPlutusData.of(0))
                .attachSpendingValidator(validator)
                .payToContract(scriptAddress.getAddress(), continuing, AnchorDatumCodec.encode(next));
        Transaction tx = txContext(scriptTx, signersHex.size())
                .withRequiredSigners(requiredSigners)
                .buildAndSign();

        byte[] bodyBytes = CborSerializationUtil.serialize(tx.getBody().serialize(Era.Conway));
        byte[] bodyHash = Blake2bUtil.blake2bHash256(bodyBytes);
        byte[] requestBody = encodeSignRequest(bodyBytes, policyId, scriptHash);
        return new BuiltAdvance(tx, bodyBytes, bodyHash, requestBody,
                prev.height() + 1, next.height());
    }

    /**
     * Common QuickTx context: the anchor wallet pays fees and collateral,
     * ex-units come from the local julc evaluator, and witnesses that attach
     * AFTER the body is fixed (co-signed members) are priced via
     * {@code additionalSignersCount} — the exact mechanism the hand-rolled
     * fee sizing got wrong before the Iteration-3 gate caught it.
     */
    private QuickTxBuilder.TxContext txContext(ScriptTx scriptTx, int pendingCosigners) {
        QuickTxBuilder builder = new QuickTxBuilder(
                cclUtxoSupplier, this::effectiveProtocolParams, NO_SUBMIT);
        QuickTxBuilder.TxContext context = builder.compose(scriptTx)
                .feePayer(walletAddress.getAddress())
                .collateralPayer(walletAddress.getAddress())
                .additionalSignersCount(pendingCosigners)
                .withTxEvaluator(new JulcTransactionEvaluator(
                        cclUtxoSupplier, this::effectiveProtocolParams, scriptHash -> java.util.Optional.empty()))
                .withSigner(SignerProviders.signerFrom(walletKey));
        long ttl = txTtl();
        if (ttl > 0) {
            context = context.validTo(ttl);
        }
        return context;
    }

    /**
     * The protocol parameters that price this anchor tx: the node's tracked /
     * L1-derived params, or the configured static {@code protocol-param.json}
     * fallback ({@code yano.genesis.protocol-parameters-file}) — both carry the
     * full PlutusV3 cost model. Fails closed if neither source is available:
     * an anchor tx must be priced against the SAME cost model the L1 ledger
     * enforces, so a hardcoded default would risk a wrong script-integrity hash.
     */
    private ProtocolParams effectiveProtocolParams() {
        Supplier<ProtocolParams> supplier = protocolParamsSupplier;
        ProtocolParams params = supplier != null ? supplier.get() : null;
        if (params == null) {
            throw new IllegalStateException("Script anchoring requires protocol parameters — enable "
                    + "epoch-param tracking (yano.epoch-params.tracking-enabled) or set "
                    + "yano.genesis.protocol-parameters-file");
        }
        return params;
    }

    // ------------------------------------------------------------------
    // Member: sign-request verification + witness replies
    // ------------------------------------------------------------------

    /** Inbound framework message on an {@code ~anchor/*} topic. */
    void onAnchorMessage(AppMessage message) {
        try {
            String topic = message.getTopic() != null ? message.getTopic() : "";
            if (TOPIC_SIGN.equals(topic)) {
                onSignRequest(message);
            } else if (TOPIC_SIG.equals(topic)) {
                onSignature(message);
            }
        } catch (Exception e) {
            log.warn("Script-anchor message handling failed ({}): {}",
                    message.getTopic(), e.toString());
        }
    }

    private void onSignRequest(AppMessage message) throws Exception {
        // Envelope authenticity is transport-verified; only members may ask
        String senderHex = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        if (!membersSupplier.get().contains(senderHex)) {
            log.debug("Script-anchor sign request from non-member {} — ignored", senderHex);
            return;
        }
        SignRequest request = decodeSignRequest(message.getBody());
        if (request == null)
            return;
        String selfHex = memberSigner.publicKeyHex().toLowerCase(Locale.ROOT);
        byte[] selfPkh = Blake2bUtil.blake2bHash224(memberSigner.publicKey());

        TransactionBody body = deserializeBody(request.bodyBytes());
        if (body == null)
            return;
        // Only sign bodies that list US as a required signer
        boolean listed = body.getRequiredSigners() != null && body.getRequiredSigners().stream()
                .anyMatch(pkh -> java.util.Arrays.equals(pkh, selfPkh));
        if (!listed)
            return;

        if (!verifyAdvance(body, request.policyId(), request.scriptHash()))
            return;

        byte[] bodyHash = Blake2bUtil.blake2bHash256(request.bodyBytes());
        byte[] witnessSig = memberSigner.sign(bodyHash);
        diffuser.accept(TOPIC_SIG, encodeSignature(bodyHash, witnessSig));
        log.info("Script-anchor: verified advance body {} — witness sent (member {})",
                HexUtil.encodeHexString(bodyHash), selfHex.substring(0, 8));
    }

    /**
     * Deterministic member verification (ADR 008.4 §2.5): everything is
     * checked against THIS node's own ledger and L1 view — a compromised
     * leader cannot obtain witnesses for a bogus advance.
     */
    private boolean verifyAdvance(TransactionBody body, byte[] policyId, byte[] scriptHash) {
        // 0. Adopt (or match) the anchor identity
        if (!adoptOrMatchIdentity(policyId, scriptHash))
            return false;
        Address scriptAddress = AddressProvider.getEntAddress(Credential.fromScript(scriptHash), network);

        // 1. The tx spends MY view of the anchor UTxO
        Utxo anchorUtxo = findAnchorUtxo(policyId, scriptHash);
        if (anchorUtxo == null) {
            log.debug("Script-anchor: cannot verify sign request — anchor UTxO not in local L1 view");
            return false;
        }
        boolean spendsAnchor = body.getInputs() != null && body.getInputs().stream()
                .anyMatch(in -> in.getTransactionId().equalsIgnoreCase(anchorUtxo.outpoint().txHash())
                        && in.getIndex() == anchorUtxo.outpoint().index());
        if (!spendsAnchor) {
            log.warn("Script-anchor: sign request does not spend my anchor UTxO — refused");
            return false;
        }

        // 2. Exactly one continuing output: script address, thread NFT, inline datum
        List<TransactionOutput> continuing = body.getOutputs() == null ? List.of()
                : body.getOutputs().stream()
                        .filter(o -> scriptAddress.getAddress().equals(o.getAddress()))
                        .toList();
        if (continuing.size() != 1) {
            log.warn("Script-anchor: sign request has {} continuing outputs — refused", continuing.size());
            return false;
        }
        TransactionOutput next = continuing.get(0);
        if (!carriesThreadToken(next.getValue(), policyId)
                || next.getValue().getCoin().longValue() < anchorUtxo.lovelace().longValue()) {
            log.warn("Script-anchor: continuing output drops the thread token or value — refused");
            return false;
        }
        if (next.getInlineDatum() == null) {
            log.warn("Script-anchor: continuing output has no inline datum — refused");
            return false;
        }

        // 3. The next datum matches MY chain history and membership
        AnchorDatumCodec.AnchorDatum datum;
        try {
            datum = AnchorDatumCodec.decode(next.getInlineDatum());
        } catch (Exception e) {
            log.warn("Script-anchor: continuing datum does not decode ({}) — refused", e.toString());
            return false;
        }
        if (datum.version() != AnchorDatumCodec.ABI_VERSION || !chainId.equals(datum.chainId())) {
            log.warn("Script-anchor: datum version/chain-id mismatch — refused");
            return false;
        }
        long myTip = tipHeightSupplier.get();
        if (datum.height() <= 0 || datum.height() > myTip) {
            log.debug("Script-anchor: datum height {} beyond my tip {} — not signing yet",
                    datum.height(), myTip);
            return false;
        }
        AppBlock myBlock = blockByHeight.apply(datum.height());
        if (myBlock == null
                || !java.util.Arrays.equals(AppBlockCodec.blockHash(myBlock), datum.blockHash())
                || !java.util.Arrays.equals(myBlock.stateRoot(), datum.stateRoot())) {
            log.warn("Script-anchor: datum block-hash/state-root differ from MY block {} — refused",
                    datum.height());
            return false;
        }
        Set<String> myMembers = new TreeSet<>(membersSupplier.get());
        Set<String> datumMembers = new TreeSet<>();
        for (byte[] key : datum.memberKeys()) {
            datumMembers.add(HexUtil.encodeHexString(key).toLowerCase(Locale.ROOT));
        }
        if (!myMembers.equals(datumMembers) || datum.threshold() != thresholdSupplier.getAsInt()) {
            log.warn("Script-anchor: datum membership/threshold differ from my current epoch — refused");
            return false;
        }
        return true;
    }

    private boolean adoptOrMatchIdentity(byte[] policyId, byte[] scriptHash) {
        if (policyId == null || policyId.length != 28 || scriptHash == null || scriptHash.length != 28)
            return false;
        byte[] knownPolicy = ledger.metaBytes(META_SCRIPT_POLICY_ID);
        byte[] knownScript = ledger.metaBytes(META_SCRIPT_HASH);
        if (knownPolicy != null && knownPolicy.length == 28) {
            boolean matches = java.util.Arrays.equals(knownPolicy, policyId)
                    && java.util.Arrays.equals(knownScript, scriptHash);
            if (!matches)
                log.warn("Script-anchor: sign request identity differs from the persisted anchor "
                        + "identity — refused");
            return matches;
        }
        // First sighting: adopt only if the claimed anchor exists on MY L1 view
        // with a datum that matches MY chain history (verified by the caller)
        Utxo utxo = findAnchorUtxo(policyId, scriptHash);
        if (utxo == null) {
            log.debug("Script-anchor: claimed anchor identity not found on local L1 view — not adopting");
            return false;
        }
        ledger.metaPutBytes(META_SCRIPT_POLICY_ID, policyId);
        ledger.metaPutBytes(META_SCRIPT_HASH, scriptHash);
        log.info("Script-anchor identity adopted from member sign request: policyId={}, scriptHash={}",
                HexUtil.encodeHexString(policyId), HexUtil.encodeHexString(scriptHash));
        return true;
    }

    private void onSignature(AppMessage message) {
        PendingCosign cosign = pendingCosign;
        if (!leader || cosign == null)
            return;
        String senderHex = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        if (!cosign.signerSetHex().contains(senderHex))
            return;
        SignatureReply reply = decodeSignature(message.getBody());
        if (reply == null || !java.util.Arrays.equals(reply.bodyHash(), cosign.bodyHash()))
            return;
        if (!AppMessageSigner.verify(reply.signature(), cosign.bodyHash(), message.getSender())) {
            log.warn("Script-anchor: invalid witness signature from {} — ignored", senderHex);
            return;
        }
        cosign.signatures().put(senderHex, reply.signature());
        log.info("Script-anchor: witness {}/{} collected (member {})",
                cosign.signatures().size(), cosign.signerSetHex().size(), senderHex.substring(0, 8));
        synchronized (anchorLock) {
            PendingCosign current = pendingCosign;
            if (current != null && cosignComplete(current)) {
                assembleAndSubmit(current);
            }
        }
    }

    // ------------------------------------------------------------------
    // L1 confirmation / rollback (mirrors metadata mode)
    // ------------------------------------------------------------------

    AnchorService.ConfirmedAnchor onL1Block(long slot, List<String> txHashes) {
        if (txHashes == null)
            return null;
        PendingBootstrap bootstrap = pendingBootstrap;
        if (bootstrap != null && txHashes.contains(bootstrap.txHash())) {
            pendingBootstrap = null;
            ledger.metaPutBytes(META_SCRIPT_POLICY_ID, bootstrap.policyId());
            ledger.metaPutBytes(META_SCRIPT_HASH, bootstrap.scriptHash());
            ledger.metaPutString(META_SCRIPT_BOOTSTRAP_TX, bootstrap.txHash());
            ledger.metaPutLong(META_SCRIPT_BOOTSTRAP_SLOT, slot);
            log.info("Script-anchor bootstrap CONFIRMED on L1: tx={}, policyId={}, l1Slot={}",
                    bootstrap.txHash(), HexUtil.encodeHexString(bootstrap.policyId()), slot);
            return null;
        }
        PendingSubmit submit = pendingSubmit;
        if (submit == null || !txHashes.contains(submit.txHash()))
            return null;
        pendingSubmit = null;
        ledger.metaPutLong(META_LAST_ANCHORED, submit.toHeight());
        ledger.metaPutLong(META_ANCHOR_FROM, submit.fromHeight());
        AppBlock anchoredBlock = blockByHeight.apply(submit.toHeight());
        if (anchoredBlock != null) {
            ledger.metaPutBytes(META_ANCHOR_BLOCK_HASH, AppBlockCodec.blockHash(anchoredBlock));
        }
        ledger.metaPutString(META_ANCHOR_TX, submit.txHash());
        ledger.metaPutLong(META_ANCHOR_SLOT, slot);
        anchoredCount++;
        lastAnchoredL1Slot = slot;
        lastAnchorTxHash = submit.txHash();
        log.info("Script-anchor CONFIRMED on L1: tx={}, app blocks {}..{}, l1Slot={}",
                submit.txHash(), submit.fromHeight(), submit.toHeight(), slot);
        return new AnchorService.ConfirmedAnchor(submit.fromHeight(), submit.toHeight(),
                submit.txHash(), slot);
    }

    void onL1Rollback(long rollbackToSlot) {
        long bootstrapSlot = ledger.metaLong(META_SCRIPT_BOOTSTRAP_SLOT, 0L);
        if (bootstrapSlot > 0 && bootstrapSlot > rollbackToSlot) {
            // The mint itself rolled back — the tx normally re-lands from the
            // mempool; identity stays (same seed → same policy) unless the seed
            // is double-spent, in which case bootstrap must be re-run.
            log.warn("L1 rollback to slot {} un-confirmed the script-anchor bootstrap (slot {}) — "
                    + "identity retained; re-run bootstrap if the tx does not re-land", rollbackToSlot,
                    bootstrapSlot);
            ledger.metaPutLong(META_SCRIPT_BOOTSTRAP_SLOT, 0L);
        }
        if (lastAnchoredL1Slot > rollbackToSlot && lastAnchorTxHash != null) {
            log.warn("L1 rollback to slot {} un-confirmed script-anchor tx {} — will re-anchor",
                    rollbackToSlot, lastAnchorTxHash);
            long anchorFrom = ledger.metaLong(META_ANCHOR_FROM, 0L);
            ledger.metaPutLong(META_LAST_ANCHORED, Math.max(0, anchorFrom - 1));
            ledger.metaPutBytes(META_ANCHOR_BLOCK_HASH, new byte[0]);
            ledger.metaPutString(META_ANCHOR_TX, "");
            lastAnchorTxHash = null;
            lastAnchoredL1Slot = 0;
        }
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        status.put("mode", "script");
        status.put("leader", leader);
        status.putAll(identityStatus());
        status.put("lastAnchoredHeight", lastAnchoredHeight());
        status.put("anchoredCount", anchoredCount);
        PendingBootstrap bootstrap = pendingBootstrap;
        if (bootstrap != null) {
            status.put("pendingBootstrapTx", bootstrap.txHash());
        }
        PendingCosign cosign = pendingCosign;
        if (cosign != null) {
            status.put("cosignPending", cosign.signatures().size() + "/" + cosign.signerSetHex().size());
            status.put("cosignRange", cosign.fromHeight() + ".." + cosign.toHeight());
        }
        PendingSubmit submit = pendingSubmit;
        if (submit != null) {
            status.put("pendingTx", submit.txHash());
            status.put("pendingRange", submit.fromHeight() + ".." + submit.toHeight());
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

    private Map<String, Object> identityStatus() {
        Map<String, Object> identity = new LinkedHashMap<>();
        byte[] policyId = ledger.metaBytes(META_SCRIPT_POLICY_ID);
        byte[] scriptHash = ledger.metaBytes(META_SCRIPT_HASH);
        identity.put("bootstrapped", policyId != null && policyId.length == 28);
        if (policyId != null && policyId.length == 28) {
            identity.put("threadPolicyId", HexUtil.encodeHexString(policyId));
        }
        if (scriptHash != null && scriptHash.length == 28) {
            identity.put("scriptHash", HexUtil.encodeHexString(scriptHash));
            identity.put("scriptAddress", AddressProvider.getEntAddress(
                    Credential.fromScript(scriptHash), network).getAddress());
        }
        return identity;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AnchorDatumCodec.AnchorDatum datumAtTip() {
        long tip = tipHeightSupplier.get();
        byte[] blockHash = new byte[32];
        byte[] stateRoot = new byte[32];
        if (tip > 0) {
            AppBlock tipBlock = blockByHeight.apply(tip);
            if (tipBlock == null)
                return null;
            blockHash = AppBlockCodec.blockHash(tipBlock);
            stateRoot = tipBlock.stateRoot();
        }
        List<byte[]> memberKeys = membersSupplier.get().stream()
                .map(HexUtil::decodeHexString)
                .toList();
        return new AnchorDatumCodec.AnchorDatum(AnchorDatumCodec.ABI_VERSION, chainId, tip,
                blockHash, stateRoot, AnchorDatumCodec.sortedKeys(memberKeys),
                thresholdSupplier.getAsInt());
    }

    private Utxo findAnchorUtxo(byte[] policyId, byte[] scriptHash) {
        if (policyId == null || policyId.length != 28 || scriptHash == null || scriptHash.length != 28)
            return null;
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null)
            return null;
        String scriptAddress = AddressProvider.getEntAddress(
                Credential.fromScript(scriptHash), network).getAddress();
        String policyHex = HexUtil.encodeHexString(policyId);
        return utxoState.getUtxosByAddress(scriptAddress, 1, 50).stream()
                .filter(u -> u.assets() != null && u.assets().stream()
                        .anyMatch(a -> policyHex.equalsIgnoreCase(a.policyId())))
                .filter(u -> u.inlineDatum() != null && u.inlineDatum().length > 0)
                .findFirst()
                .orElse(null);
    }

    private List<Utxo> usableWalletUtxos() {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null)
            throw new IllegalStateException("UTXO state unavailable — cannot select anchor inputs");
        return utxoState.getUtxosByAddress(walletAddress.getAddress(), 1, 50).stream()
                .filter(u -> u.lovelace() != null && u.lovelace().longValue() >= MIN_INPUT_LOVELACE)
                .filter(u -> u.assets() == null || u.assets().isEmpty())
                .sorted(Comparator.comparing((Utxo u) -> u.lovelace()).reversed())
                .toList();
    }

    private long txTtl() {
        Supplier<Long> slotSupplier = currentSlotSupplier;
        Long currentSlot = slotSupplier != null ? slotSupplier.get() : null;
        return currentSlot != null && currentSlot > 0
                ? currentSlot + anchorConfig.validitySlots() : 0;
    }

    private static boolean carriesThreadToken(Value value, byte[] policyId) {
        if (value == null || value.getMultiAssets() == null)
            return false;
        String policyHex = HexUtil.encodeHexString(policyId);
        return value.getMultiAssets().stream()
                .anyMatch(ma -> policyHex.equalsIgnoreCase(ma.getPolicyId())
                        && ma.getAssets() != null
                        && ma.getAssets().stream().anyMatch(a -> BigInteger.ONE.equals(a.getValue())));
    }

    private TransactionBody deserializeBody(byte[] bodyBytes) {
        try {
            DataItem item = CborSerializationUtil.deserialize(bodyBytes);
            return TransactionBody.deserialize((co.nstant.in.cbor.model.Map) item);
        } catch (Exception e) {
            log.warn("Script-anchor: sign request body does not decode: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Message codecs: [version, ...] CBOR arrays
    // ------------------------------------------------------------------

    private record SignRequest(byte[] bodyBytes, byte[] policyId, byte[] scriptHash) {
    }

    private record SignatureReply(byte[] bodyHash, byte[] signature) {
    }

    static byte[] encodeSignRequest(byte[] bodyBytes, byte[] policyId, byte[] scriptHash) {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(new ByteString(bodyBytes));
            array.add(new ByteString(policyId));
            array.add(new ByteString(scriptHash));
            return CborSerializationUtil.serialize(array);
        } catch (Exception e) {
            throw new IllegalStateException("Sign request encoding failed", e);
        }
    }

    private SignRequest decodeSignRequest(byte[] body) {
        try {
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            long version = ((UnsignedInteger) items.get(0)).getValue().longValueExact();
            if (version != 1)
                return null;
            return new SignRequest(((ByteString) items.get(1)).getBytes(),
                    ((ByteString) items.get(2)).getBytes(),
                    ((ByteString) items.get(3)).getBytes());
        } catch (Exception e) {
            log.warn("Script-anchor: malformed sign request: {}", e.toString());
            return null;
        }
    }

    static byte[] encodeSignature(byte[] bodyHash, byte[] signature) {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(new ByteString(bodyHash));
            array.add(new ByteString(signature));
            return CborSerializationUtil.serialize(array);
        } catch (Exception e) {
            throw new IllegalStateException("Signature reply encoding failed", e);
        }
    }

    private SignatureReply decodeSignature(byte[] body) {
        try {
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            long version = ((UnsignedInteger) items.get(0)).getValue().longValueExact();
            if (version != 1)
                return null;
            return new SignatureReply(((ByteString) items.get(1)).getBytes(),
                    ((ByteString) items.get(2)).getBytes());
        } catch (Exception e) {
            log.warn("Script-anchor: malformed signature reply: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------

    private record PendingBootstrap(String txHash, byte[] policyId, byte[] scriptHash, long submittedAt) {
    }

    private record PendingCosign(Transaction tx, byte[] bodyBytes, byte[] bodyHash, byte[] requestBody,
                                 Set<String> signerSetHex, Map<String, byte[]> signatures,
                                 long fromHeight, long toHeight, long startedAt) {
    }

    private record PendingSubmit(long fromHeight, long toHeight, String txHash, long submittedAt) {
    }
}
