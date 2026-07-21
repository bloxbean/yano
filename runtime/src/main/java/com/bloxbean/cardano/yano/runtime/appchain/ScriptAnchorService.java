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
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
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
    private static final CborStructurePreflight.Limits ANCHOR_MESSAGE_CBOR_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 4, 32, 8,
                    AppChainConfig.MAX_MESSAGE_BYTES);
    private static final CborStructurePreflight.Limits ANCHOR_TX_BODY_CBOR_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 64, 500_000, 250_000,
                    AppChainConfig.MAX_MESSAGE_BYTES);

    private static final long MIN_INPUT_LOVELACE = 1_000_000;
    private static final long RESUBMIT_AFTER_MS = 120_000;
    /** Co-sign round deadline before falling back to the responsive subset. */
    private static final long COSIGN_ROUND_TIMEOUT_MS = 30_000;
    /** Bounded scan prevents ordinary outputs sent to the script address hiding the thread NFT. */
    private static final int ANCHOR_UTXO_PAGE_SIZE = 200;
    private static final int ANCHOR_UTXO_MAX_PAGES = 50;

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
    private static final String META_ANCHOR_HISTORY = "anchor_confirmation_history_v1";
    // Script-anchor identity meta (008.4)
    private static final String META_SCRIPT_POLICY_ID = "anchor_script_policy_id";
    private static final String META_SCRIPT_HASH = "anchor_script_hash";
    private static final String META_SCRIPT_BOOTSTRAP_TX = "anchor_script_bootstrap_tx";
    private static final String META_SCRIPT_BOOTSTRAP_SLOT = "anchor_script_bootstrap_slot";
    private static final String META_SCRIPT_OUT_INDEX = "anchor_script_out_index";
    /** 1 when identity came from a verified member request rather than local bootstrap. */
    private static final String META_SCRIPT_IDENTITY_ADOPTED = "anchor_script_identity_adopted";
    /** Verified candidate advance tx ids (comma-separated, bounded). */
    private static final String META_SCRIPT_ADOPTION_TX = "anchor_script_adoption_tx";
    private static final String META_SCRIPT_CANDIDATE_POLICY = "anchor_script_candidate_policy_id";
    private static final String META_SCRIPT_CANDIDATE_HASH = "anchor_script_candidate_hash";
    private static final String META_SCRIPT_CANDIDATE_BASE_TX = "anchor_script_candidate_base_tx";
    private static final String META_SCRIPT_CANDIDATE_SLOT = "anchor_script_candidate_slot";
    private static final String META_SCRIPT_CANDIDATE_BASE_INDEX = "anchor_script_candidate_base_index";
    private static final int MAX_ADOPTION_TXS = 8;

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
    /** Raw canonical L1 point; reconciliation requires the UTxO store to match it exactly. */
    private volatile Supplier<AppChainEngine.L1Ref> currentL1PointSupplier;
    /** QuickTx sees the node's own UTxO store — we ARE the backend. */
    private final NodeUtxoSupplier cclUtxoSupplier;

    private final Object anchorLock = new Object();
    private volatile PendingBootstrap pendingBootstrap;
    private volatile ObservedBootstrap observedBootstrap;
    private volatile PendingCosign pendingCosign;
    private volatile PendingSubmit pendingSubmit;
    private volatile ObservedSubmit observedSubmit;
    private volatile long lastAnchorAttemptAt;
    private volatile String lastError;
    /** Session-local committed-view observations; never interpreted as cluster state. */
    private volatile long observedAnchorCount;
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

    void wireTxPricing(Supplier<ProtocolParams> protocolParams,
                       Supplier<AppChainEngine.L1Ref> currentL1Point) {
        this.protocolParamsSupplier = protocolParams;
        this.currentL1PointSupplier = currentL1Point;
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
        byte[] scriptHash = ledger.metaBytes(META_SCRIPT_HASH);
        return policyId != null && policyId.length == 28
                && scriptHash != null && scriptHash.length == 28;
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
                        built.datumHeight(), System.currentTimeMillis());
                lastError = null;
                log.info("Script-anchor bootstrap tx submitted: {} (policyId={}, scriptAddress={})",
                        txHash, HexUtil.encodeHexString(built.policyId()), built.scriptAddress());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("txHash", txHash);
                result.put("threadPolicyId", HexUtil.encodeHexString(built.policyId()));
                result.put("scriptHash", HexUtil.encodeHexString(built.scriptHash()));
                result.put("scriptAddress", built.scriptAddress());
                return result;
            } catch (Throwable failure) {
                recordFailure("bootstrap", failure);
                throw new IllegalStateException(lastError, failure);
            }
        }
    }

    private record BuiltBootstrap(byte[] txCbor, byte[] policyId, byte[] scriptHash,
                                  String scriptAddress, long datumHeight) {
    }

    /**
     * Thread-NFT asset name: the chain-id as UTF-8, truncated to the 32-byte
     * ledger limit. Purely a display label for explorers — identity and
     * uniqueness come from the one-shot policy id.
     */
    private byte[] threadTokenName() {
        byte[] bytes = chainId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return bytes.length <= 32 ? bytes : java.util.Arrays.copyOf(bytes, 32);
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

        // Bootstrap establishes only the unique thread identity. The first
        // real app-state commitment is an immediate threshold-co-signed
        // advance from height 0 to the current tip, so followers never need
        // to trust the unilateral bootstrap transaction as an anchor.
        AnchorDatumCodec.AnchorDatum datum = datumAtHeightZero();

        // Consume the seed (one-shot identity), mint the thread NFT straight
        // to the validator with the inline genesis datum; QuickTx balances
        // fees/collateral/change and the julc evaluator prices exact ex-units.
        // The token is NAMED with the chain-id (readable label on explorers);
        // identity/uniqueness stays with the one-shot policy id.
        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(NodeUtxoSupplier.toCcl(seed))
                .mintAsset(policy,
                        List.of(Asset.builder()
                                .name("0x" + HexUtil.encodeHexString(threadTokenName()))
                                .value(BigInteger.ONE).build()),
                        BigIntPlutusData.of(0),
                        scriptAddress.getAddress(),
                        AnchorDatumCodec.encode(datum));
        Transaction tx = txContext(scriptTx, 0).buildAndSign();
        return new BuiltBootstrap(tx.serialize(), policyId, scriptHash,
                scriptAddress.getAddress(), datum.height());
    }

    // ------------------------------------------------------------------
    // Leader: periodic tick, co-sign rounds, assembly
    // ------------------------------------------------------------------

    AnchorService.ConfirmedAnchor tick() {
        synchronized (anchorLock) {
            try {
                if (!leader) {
                    // Every member derives the durable anchor frontier from
                    // its OWN authenticated L1 UTxO view. Followers never
                    // enter any construction/submission path below.
                    return reconcileObservedAnchor();
                }
                if (observedBootstrap != null) {
                    completeObservedBootstrap();
                    return null;
                }
                if (observedSubmit != null) {
                    return completeObservedSubmit();
                }
                // Leader recovery/restart repair when there is no in-flight
                // confirmation to complete. Pending observations stay the
                // authoritative path so their retry/count semantics cannot
                // be double-applied by reconciliation.
                AnchorService.ConfirmedAnchor repaired = reconcileObservedAnchor();
                if (repaired != null) {
                    return repaired;
                }
                PendingBootstrap bootstrap = pendingBootstrap;
                if (bootstrap != null
                        && System.currentTimeMillis() - bootstrap.submittedAt() > RESUBMIT_AFTER_MS) {
                    log.warn("Script-anchor bootstrap tx {} not observed on L1 within {}ms — clearing "
                            + "(re-run bootstrap; the seed UTxO may have been spent)",
                            bootstrap.txHash(), RESUBMIT_AFTER_MS);
                    pendingBootstrap = null;
                }
                if (!bootstrapped())
                    return null;

                PendingSubmit submit = pendingSubmit;
                if (submit != null) {
                    if (System.currentTimeMillis() - submit.submittedAt() > RESUBMIT_AFTER_MS) {
                        log.warn("Script-anchor tx {} not observed on L1 within {}ms — restarting co-sign",
                                submit.txHash(), RESUBMIT_AFTER_MS);
                        pendingSubmit = null;
                        startCosignRound(null);
                    }
                    return null;
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
                    return null;
                }

                long tip = tipHeightSupplier.get();
                long lastAnchored = lastAnchoredHeight();
                if (tip <= lastAnchored)
                    return null;
                boolean dueByCount = tip - lastAnchored >= anchorConfig.everyBlocks();
                boolean dueByTime = lastAnchorAttemptAt > 0
                        ? System.currentTimeMillis() - lastAnchorAttemptAt
                                >= anchorConfig.maxIntervalMinutes() * 60_000
                        : true;
                if (dueByCount || dueByTime) {
                    startCosignRound(null);
                }
                return null;
            } catch (Throwable failure) {
                // ScheduledExecutor suppresses all later invocations when a
                // periodic task lets an Error escape. Isolate every
                // recoverable plugin/transaction failure here; only errors
                // after which the process is unsafe may terminate the task.
                recordFailure("tick", failure);
                return null;
            }
        }
    }

    boolean forceAnchorNow() {
        if (!leader)
            return false;
        synchronized (anchorLock) {
            try {
                if (observedBootstrap != null) {
                    completeObservedBootstrap();
                    return false;
                }
                if (observedSubmit != null) {
                    completeObservedSubmit();
                    return false;
                }
                if (!bootstrapped() || pendingSubmit != null || pendingCosign != null)
                    return false;
                if (tipHeightSupplier.get() <= lastAnchoredHeight())
                    return false;
                startCosignRound(null);
                return pendingCosign != null || pendingSubmit != null;
            } catch (Throwable failure) {
                recordFailure("force-anchor", failure);
                return false;
            }
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
        } catch (Throwable failure) {
            recordFailure("co-sign round start", failure);
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
        } catch (Throwable failure) {
            pendingCosign = null;
            recordFailure("assemble/submit", failure);
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
        AnchorDatumCodec.AnchorDatum prev = AnchorDatumCodec.decode(anchorUtxo.inlineDatum());
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

        // Continuing output: same lovelace + thread NFT, next inline datum.
        // Carry the EXACT unit found on the thread UTxO — new chains name the
        // token with the chain-id, pre-existing chains minted an empty name.
        String policyHex = HexUtil.encodeHexString(policyId);
        String threadUnit = anchorUtxo.assets().stream()
                .filter(a -> policyHex.equalsIgnoreCase(a.policyId()))
                .findFirst()
                .map(a -> a.policyId() + (a.assetName() != null ? a.assetName() : ""))
                .orElse(policyHex);
        List<Amount> continuing = new ArrayList<>();
        continuing.add(Amount.lovelace(anchorUtxo.lovelace()));
        continuing.add(Amount.asset(threadUnit, BigInteger.ONE));

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
        } catch (Throwable failure) {
            logFailure("message handling", failure);
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
        Utxo anchorUtxo = findAnchorUtxo(request.policyId(), request.scriptHash());
        if (anchorUtxo == null || !adoptVerifiedIdentity(
                request.policyId(), request.scriptHash(), anchorUtxo,
                HexUtil.encodeHexString(bodyHash))) {
            return;
        }
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
        byte[] expectedScriptHash;
        try {
            expectedScriptHash = AnchorScriptArtifacts.scriptHash(artifacts.validator(policyId));
        } catch (Throwable failure) {
            logFailure("anchor identity verification", failure);
            return false;
        }
        if (!java.util.Arrays.equals(expectedScriptHash, scriptHash)) {
            log.warn("Script-anchor: sign request validator does not match configured artifact — refused");
            return false;
        }
        // 0. Adopt (or match) the anchor identity
        boolean adopting = ledger.metaBytes(META_SCRIPT_POLICY_ID) == null
                || ledger.metaBytes(META_SCRIPT_POLICY_ID).length != 28;
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
        if (adopting && !validAdoptionBaseline(anchorUtxo)) {
            log.warn("Script-anchor: candidate identity baseline does not match local chain/membership — refused");
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
        } catch (Throwable failure) {
            logFailure("continuing datum decode", failure);
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
        Set<String> requiredSignerHashes = new TreeSet<>();
        if (body.getRequiredSigners() != null) {
            for (byte[] signerHash : body.getRequiredSigners()) {
                requiredSignerHashes.add(HexUtil.encodeHexString(signerHash));
            }
        }
        long requiredMembers = myMembers.stream()
                .map(HexUtil::decodeHexString)
                .map(Blake2bUtil::blake2bHash224)
                .map(HexUtil::encodeHexString)
                .filter(requiredSignerHashes::contains)
                .count();
        if (requiredMembers < thresholdSupplier.getAsInt()) {
            log.warn("Script-anchor: sign request lists fewer than the local member threshold — refused");
            return false;
        }
        return true;
    }

    private boolean validAdoptionBaseline(Utxo anchorUtxo) {
        try {
            AnchorDatumCodec.AnchorDatum datum = AnchorDatumCodec.decode(anchorUtxo.inlineDatum());
            if (datum.version() != AnchorDatumCodec.ABI_VERSION
                    || !chainId.equals(datum.chainId())
                    || datum.height() < 0L || datum.height() > tipHeightSupplier.get()) {
                return false;
            }
            byte[] expectedBlockHash = new byte[32];
            byte[] expectedStateRoot = new byte[32];
            if (datum.height() > 0L) {
                AppBlock localBlock = blockByHeight.apply(datum.height());
                if (localBlock == null) {
                    return false;
                }
                expectedBlockHash = AppBlockCodec.blockHash(localBlock);
                expectedStateRoot = localBlock.stateRoot();
            }
            if (!java.util.Arrays.equals(expectedBlockHash, datum.blockHash())
                    || !java.util.Arrays.equals(expectedStateRoot, datum.stateRoot())) {
                return false;
            }
            Set<String> datumMembers = new TreeSet<>();
            for (byte[] key : datum.memberKeys()) {
                datumMembers.add(HexUtil.encodeHexString(key).toLowerCase(Locale.ROOT));
            }
            return datumMembers.equals(new TreeSet<>(membersSupplier.get()))
                    && datum.threshold() == thresholdSupplier.getAsInt();
        } catch (Throwable failure) {
            logFailure("candidate identity datum verification", failure);
            return false;
        }
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
        // With no authoritative identity, each request remains an independent
        // candidate until a locally verified tx is threshold-accepted on L1.
        // Do not let an earlier one-member request permanently pin the node.
        Utxo utxo = findAnchorUtxo(policyId, scriptHash);
        if (utxo == null) {
            log.debug("Script-anchor: claimed anchor identity not found on local L1 view — not adopting");
            return false;
        }
        return true;
    }

    /** Record a verified candidate; authoritative identity is promoted only from committed L1 state. */
    private boolean adoptVerifiedIdentity(byte[] policyId, byte[] scriptHash, Utxo anchorUtxo,
                                          String verifiedAdvanceTx) {
        synchronized (anchorLock) {
            byte[] knownPolicy = ledger.metaBytes(META_SCRIPT_POLICY_ID);
            byte[] knownScript = ledger.metaBytes(META_SCRIPT_HASH);
            if (knownPolicy != null && knownPolicy.length == 28) {
                return java.util.Arrays.equals(knownPolicy, policyId)
                        && java.util.Arrays.equals(knownScript, scriptHash);
            }
            byte[] candidatePolicy = ledger.metaBytes(META_SCRIPT_CANDIDATE_POLICY);
            byte[] candidateHash = ledger.metaBytes(META_SCRIPT_CANDIDATE_HASH);
            boolean sameCandidate = candidatePolicy != null && candidatePolicy.length == 28
                    && java.util.Arrays.equals(candidatePolicy, policyId)
                    && java.util.Arrays.equals(candidateHash, scriptHash);
            Set<String> verifiedTxs = sameCandidate
                    ? verifiedAdoptionTxs() : new java.util.LinkedHashSet<>();
            verifiedTxs.add(verifiedAdvanceTx);
            while (verifiedTxs.size() > MAX_ADOPTION_TXS) {
                verifiedTxs.remove(verifiedTxs.iterator().next());
            }
            ledger.metaPutAll(
                    Map.of(
                            META_SCRIPT_CANDIDATE_SLOT, Math.max(0L, anchorUtxo.slot()),
                            META_SCRIPT_CANDIDATE_BASE_INDEX,
                                    (long) anchorUtxo.outpoint().index()),
                    Map.of(
                            META_SCRIPT_CANDIDATE_POLICY, policyId,
                            META_SCRIPT_CANDIDATE_HASH, scriptHash,
                            META_SCRIPT_CANDIDATE_BASE_TX,
                                    anchorUtxo.outpoint().txHash().getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8),
                            META_SCRIPT_ADOPTION_TX,
                                    String.join(",", verifiedTxs).getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8)));
            logInfoSafely("Script-anchor identity candidate recorded from verified advance: "
                            + "policyId={}, scriptHash={}, tx={}",
                    HexUtil.encodeHexString(policyId), HexUtil.encodeHexString(scriptHash),
                    verifiedAdvanceTx);
            return true;
        }
    }

    private Set<String> verifiedAdoptionTxs() {
        String encoded = ledger.metaString(META_SCRIPT_ADOPTION_TX);
        if (encoded == null || encoded.isBlank()) {
            return new java.util.LinkedHashSet<>();
        }
        Set<String> result = new java.util.LinkedHashSet<>();
        for (String value : encoded.split(",")) {
            String tx = value.trim().toLowerCase(Locale.ROOT);
            if (tx.matches("[0-9a-f]{64}")) {
                result.add(tx);
            }
        }
        return result;
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
        synchronized (anchorLock) {
            try {
                if (observedBootstrap != null) {
                    completeObservedBootstrap();
                    return null;
                }
                if (observedSubmit != null) {
                    return completeObservedSubmit();
                }
                if (txHashes == null) {
                    return null;
                }
                PendingBootstrap bootstrap = pendingBootstrap;
                if (bootstrap != null && txHashes.contains(bootstrap.txHash())) {
                    if (pendingBootstrap != bootstrap || observedBootstrap != null) {
                        return null;
                    }
                    observedBootstrap = new ObservedBootstrap(bootstrap, slot);
                    completeObservedBootstrap();
                    return null;
                }
                PendingSubmit submit = pendingSubmit;
                if (submit == null || !txHashes.contains(submit.txHash())) {
                    return null;
                }
                if (pendingSubmit != submit || observedSubmit != null) {
                    return null;
                }
                observedSubmit = new ObservedSubmit(submit, slot);
                return completeObservedSubmit();
            } catch (Throwable failure) {
                recordFailure("L1 observation", failure);
                return null;
            }
        }
    }

    private void completeObservedBootstrap() {
        ObservedBootstrap observed = observedBootstrap;
        if (observed == null) {
            return;
        }
        PendingBootstrap bootstrap = observed.bootstrap();
        try {
            byte[] anchoredBlockHash = blockHashAtConfirmedHeight(bootstrap.datumHeight());
            if (observedBootstrap != observed || pendingBootstrap != bootstrap) {
                return;
            }
            long fromHeight = bootstrap.datumHeight() > 0 ? 1L : 0L;
            AnchorService.Confirmation confirmation = new AnchorService.Confirmation(
                    fromHeight, bootstrap.datumHeight(), bootstrap.txHash(), observed.l1Slot(),
                    anchoredBlockHash);
            List<AnchorService.Confirmation> history = historyWith(confirmation);
            if (observedBootstrap != observed || pendingBootstrap != bootstrap) {
                return;
            }
            Map<String, Long> longs = new LinkedHashMap<>();
            longs.put(META_SCRIPT_BOOTSTRAP_SLOT, observed.l1Slot());
            longs.put(META_SCRIPT_IDENTITY_ADOPTED, 0L);
            longs.put(META_SCRIPT_CANDIDATE_SLOT, 0L);
            longs.put(META_SCRIPT_CANDIDATE_BASE_INDEX, -1L);
            longs.put(META_LAST_ANCHORED, bootstrap.datumHeight());
            longs.put(META_ANCHOR_FROM, fromHeight);
            longs.put(META_ANCHOR_SLOT, observed.l1Slot());
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            bytes.put(META_SCRIPT_POLICY_ID, bootstrap.policyId());
            bytes.put(META_SCRIPT_HASH, bootstrap.scriptHash());
            bytes.put(META_SCRIPT_BOOTSTRAP_TX,
                    bootstrap.txHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.put(META_SCRIPT_ADOPTION_TX, new byte[0]);
            bytes.put(META_SCRIPT_CANDIDATE_POLICY, new byte[0]);
            bytes.put(META_SCRIPT_CANDIDATE_HASH, new byte[0]);
            bytes.put(META_SCRIPT_CANDIDATE_BASE_TX, new byte[0]);
            bytes.put(META_ANCHOR_BLOCK_HASH, anchoredBlockHash);
            bytes.put(META_ANCHOR_TX,
                    bootstrap.txHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.put(META_ANCHOR_HISTORY, AnchorService.ConfirmationHistory.encode(history));
            ledger.metaPutAll(longs, bytes);

            pendingBootstrap = null;
            observedBootstrap = null;
            lastAnchoredL1Slot = observed.l1Slot();
            lastAnchorTxHash = bootstrap.txHash();
            lastError = null;
            logInfoSafely("Script-anchor bootstrap CONFIRMED on L1: tx={}, policyId={}, l1Slot={}",
                    bootstrap.txHash(), HexUtil.encodeHexString(bootstrap.policyId()), observed.l1Slot());
        } catch (Throwable failure) {
            recordFailure("L1 bootstrap confirmation", failure);
        }
    }

    private AnchorService.ConfirmedAnchor completeObservedSubmit() {
        ObservedSubmit observed = observedSubmit;
        if (observed == null) {
            return null;
        }
        PendingSubmit submit = observed.submit();
        try {
            byte[] anchoredBlockHash = blockHashAtConfirmedHeight(submit.toHeight());
            if (observedSubmit != observed || pendingSubmit != submit) {
                return null;
            }
            AnchorService.Confirmation confirmation = new AnchorService.Confirmation(
                    submit.fromHeight(), submit.toHeight(), submit.txHash(), observed.l1Slot(),
                    anchoredBlockHash);
            List<AnchorService.Confirmation> history = historyWith(confirmation);
            if (observedSubmit != observed || pendingSubmit != submit) {
                return null;
            }
            persistConfirmation(confirmation, history);

            pendingSubmit = null;
            observedSubmit = null;
            anchoredCount++;
            lastAnchoredL1Slot = observed.l1Slot();
            lastAnchorTxHash = submit.txHash();
            lastError = null;
            logInfoSafely("Script-anchor CONFIRMED on L1: tx={}, app blocks {}..{}, l1Slot={}",
                    submit.txHash(), submit.fromHeight(), submit.toHeight(), observed.l1Slot());
            return new AnchorService.ConfirmedAnchor(submit.fromHeight(), submit.toHeight(),
                    submit.txHash(), observed.l1Slot());
        } catch (Throwable failure) {
            recordFailure("L1 confirmation", failure);
            return null;
        }
    }

    private byte[] blockHashAtConfirmedHeight(long height) {
        if (height == 0) {
            return new byte[32];
        }
        AppBlock anchoredBlock = blockByHeight.apply(height);
        if (anchoredBlock == null) {
            throw new IllegalStateException("Confirmed app block is not locally available");
        }
        return AppBlockCodec.blockHash(anchoredBlock);
    }

    private void persistConfirmation(AnchorService.Confirmation confirmation,
                                     List<AnchorService.Confirmation> history) {
        persistConfirmation(confirmation, history, Map.of(), Map.of());
    }

    private void persistConfirmation(AnchorService.Confirmation confirmation,
                                     List<AnchorService.Confirmation> history,
                                     Map<String, Long> extraLongs,
                                     Map<String, byte[]> extraBytes) {
        Map<String, byte[]> byteValues = new LinkedHashMap<>();
        byteValues.put(META_ANCHOR_BLOCK_HASH, confirmation.blockHash());
        byteValues.put(META_ANCHOR_TX,
                confirmation.txHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byteValues.put(META_ANCHOR_HISTORY,
                AnchorService.ConfirmationHistory.encode(history));
        byteValues.putAll(extraBytes);
        Map<String, Long> longValues = new LinkedHashMap<>();
        longValues.put(META_LAST_ANCHORED, confirmation.toHeight());
        longValues.put(META_ANCHOR_FROM, confirmation.fromHeight());
        longValues.put(META_ANCHOR_SLOT, confirmation.l1Slot());
        longValues.putAll(extraLongs);
        ledger.metaPutAll(longValues, byteValues);
    }

    /**
     * Reconcile the latest confirmed script datum from this node's own L1
     * UTxO state.  This is deliberately independent of {@code pendingSubmit}:
     * followers co-sign but never submit, while status, evidence, snapshots
     * and L1_ANCHORED effects all consume the same local durable frontier.
     *
     * <p>The observed output is accepted only from an atomic UTxO read whose
     * committed (slot, block-hash) exactly equals this node's canonical L1
     * point, and whose datum binds exactly to this node's app block hash +
     * state root. Proposed outputs never reach this path.</p>
     */
    private AnchorService.ConfirmedAnchor reconcileObservedAnchor() {
        synchronized (anchorLock) {
            try {
                AppChainEngine.L1Ref canonicalPoint = observedL1TipPoint();
                if (canonicalPoint == null || canonicalPoint.slot() <= 0L
                        || canonicalPoint.blockHash() == null
                        || canonicalPoint.blockHash().length != 32) {
                    return null;
                }
                boolean authoritativeIdentity = bootstrapped();
                byte[] policyId = authoritativeIdentity
                        ? ledger.metaBytes(META_SCRIPT_POLICY_ID)
                        : ledger.metaBytes(META_SCRIPT_CANDIDATE_POLICY);
                byte[] scriptHash = authoritativeIdentity
                        ? ledger.metaBytes(META_SCRIPT_HASH)
                        : ledger.metaBytes(META_SCRIPT_CANDIDATE_HASH);
                if (policyId == null || policyId.length != 28
                        || scriptHash == null || scriptHash.length != 28) {
                    return null;
                }
                if (!authoritativeIdentity) {
                    byte[] expectedScriptHash = AnchorScriptArtifacts.scriptHash(
                            artifacts.validator(policyId));
                    if (!java.util.Arrays.equals(expectedScriptHash, scriptHash)) {
                        log.warn("Script-anchor: candidate validator no longer matches configured "
                                + "artifact — candidate discarded");
                        clearIdentityCandidate();
                        return null;
                    }
                }
                UtxoState utxoState = utxoStateSupplier.get();
                if (!(utxoState instanceof RollbackCapableStore committedStore)) {
                    log.debug("Script-anchor: UTxO store has no committed chain point; "
                            + "observation deferred");
                    return null;
                }
                CommittedAnchorView committedView = committedStore.readAtLatestAppliedPoint(point -> {
                    if (!samePoint(canonicalPoint, point)) {
                        return null;
                    }
                    return new CommittedAnchorView(point,
                            findAnchorUtxo(utxoState, policyId, scriptHash));
                });
                AppChainEngine.L1Ref canonicalAfterRead = observedL1TipPoint();
                if (committedView == null || !samePoint(canonicalPoint, canonicalAfterRead)) {
                    // Slot-only correlation is unsafe: after rollback an async
                    // store may still represent an orphan with the same slot.
                    // The committed-view read also excludes apply/rollback,
                    // closing the point/query TOCTOU window.
                    log.debug("Script-anchor: UTxO committed point does not match canonical L1 tip; "
                            + "observation deferred");
                    return null;
                }
                RollbackCapableStore.AppliedPoint committedPoint = committedView.point();
                Utxo anchorUtxo = committedView.anchorUtxo();
                if (anchorUtxo == null) {
                    return null;
                }
                long inclusionSlot = Math.max(0L, anchorUtxo.slot());
                if (inclusionSlot > committedPoint.slot()) {
                    log.debug("Script-anchor: thread UTxO at slot {} is ahead of committed L1 view {}; "
                                    + "observation deferred", inclusionSlot, committedPoint.slot());
                    return null;
                }

                AnchorDatumCodec.AnchorDatum datum = AnchorDatumCodec.decode(
                        anchorUtxo.inlineDatum());
                if (datum.version() != AnchorDatumCodec.ABI_VERSION
                        || !chainId.equals(datum.chainId())) {
                    log.warn("Script-anchor: observed thread datum version/chain-id mismatch — ignored");
                    return null;
                }
                long observedHeight = datum.height();
                long localTip = tipHeightSupplier.get();
                if (observedHeight < 0L || observedHeight > localTip) {
                    log.debug("Script-anchor: observed datum height {} is outside local app tip {}; "
                                    + "observation deferred", observedHeight, localTip);
                    return null;
                }

                byte[] expectedBlockHash = new byte[32];
                byte[] expectedStateRoot = new byte[32];
                if (observedHeight > 0L) {
                    AppBlock localBlock = blockByHeight.apply(observedHeight);
                    if (localBlock == null) {
                        log.debug("Script-anchor: local app block {} unavailable; observation deferred",
                                observedHeight);
                        return null;
                    }
                    expectedBlockHash = AppBlockCodec.blockHash(localBlock);
                    expectedStateRoot = localBlock.stateRoot();
                }
                if (!java.util.Arrays.equals(expectedBlockHash, datum.blockHash())
                        || !java.util.Arrays.equals(expectedStateRoot, datum.stateRoot())) {
                    log.warn("Script-anchor: observed datum block-hash/state-root differ from MY block {} "
                                    + "— ignored", observedHeight);
                    return null;
                }

                long persistedHeight = lastAnchoredHeight();
                String observedTx = anchorUtxo.outpoint() != null
                        ? anchorUtxo.outpoint().txHash() : null;
                if (observedTx == null || observedTx.isBlank()) {
                    log.warn("Script-anchor: observed thread UTxO has no transaction hash — ignored");
                    return null;
                }
                String persistedTx = ledger.metaString(META_ANCHOR_TX);
                long persistedSlot = ledger.metaLong(META_ANCHOR_SLOT, 0L);
                if (observedHeight == persistedHeight
                        && observedTx.equals(persistedTx)
                        && inclusionSlot == persistedSlot) {
                    if (ledger.metaLong(META_SCRIPT_OUT_INDEX, -1L)
                            != anchorUtxo.outpoint().index()) {
                        ledger.metaPutLong(META_SCRIPT_OUT_INDEX,
                                anchorUtxo.outpoint().index());
                    }
                    return null;
                }

                boolean advances = observedHeight > persistedHeight;
                long fromHeight = advances && persistedHeight > 0L
                        ? persistedHeight + 1L : (observedHeight > 0L ? 1L : 0L);
                AnchorService.Confirmation confirmation = new AnchorService.Confirmation(
                        fromHeight, observedHeight, observedTx, inclusionSlot,
                        expectedBlockHash);
                List<AnchorService.Confirmation> history = advances
                        ? historyWith(confirmation)
                        : canonicalHistoryThrough(confirmation);
                boolean promotesCandidate = !authoritativeIdentity;
                if (promotesCandidate && !verifiedAdoptionTxs().contains(
                        observedTx.toLowerCase(Locale.ROOT))) {
                    return null;
                }
                Map<String, Long> promotionLongs = new LinkedHashMap<>();
                promotionLongs.put(META_SCRIPT_OUT_INDEX,
                        (long) anchorUtxo.outpoint().index());
                if (promotesCandidate) {
                    promotionLongs.put(META_SCRIPT_BOOTSTRAP_SLOT, inclusionSlot);
                    promotionLongs.put(META_SCRIPT_IDENTITY_ADOPTED, 1L);
                    promotionLongs.put(META_SCRIPT_CANDIDATE_SLOT, 0L);
                    promotionLongs.put(META_SCRIPT_CANDIDATE_BASE_INDEX, -1L);
                }
                String candidateBaseTx = ledger.metaString(META_SCRIPT_CANDIDATE_BASE_TX);
                Map<String, byte[]> promotionBytes = promotesCandidate
                        ? Map.of(
                                META_SCRIPT_POLICY_ID, policyId,
                                META_SCRIPT_HASH, scriptHash,
                                META_SCRIPT_BOOTSTRAP_TX,
                                        (candidateBaseTx != null ? candidateBaseTx : "")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                META_SCRIPT_ADOPTION_TX, new byte[0],
                                META_SCRIPT_CANDIDATE_POLICY, new byte[0],
                                META_SCRIPT_CANDIDATE_HASH, new byte[0],
                                META_SCRIPT_CANDIDATE_BASE_TX, new byte[0])
                        : Map.of();
                persistConfirmation(confirmation, history, promotionLongs, promotionBytes);
                observedAnchorCount++;

                PendingSubmit submit = pendingSubmit;
                boolean completesLocalSubmit = leader && submit != null
                        && observedTx.equals(submit.txHash())
                        && observedHeight == submit.toHeight();
                if (completesLocalSubmit) {
                    pendingSubmit = null;
                    observedSubmit = null;
                    anchoredCount++;
                }
                lastAnchoredL1Slot = inclusionSlot;
                lastAnchorTxHash = observedTx;
                lastError = null;
                if (!advances) {
                    pendingCosign = null;
                    pendingSubmit = null;
                    observedSubmit = null;
                    logWarnSafely("Script-anchor canonical L1 view corrected durable frontier: "
                                    + "tx={}, appHeight={}, l1Slot={}",
                            observedTx, observedHeight, inclusionSlot);
                    return null;
                }
                logInfoSafely("Script-anchor OBSERVED on committed local L1 view: tx={}, "
                                + "app blocks {}..{}, l1Slot={}, leader={}",
                        observedTx, fromHeight, observedHeight, inclusionSlot, leader);
                return new AnchorService.ConfirmedAnchor(
                        fromHeight, observedHeight, observedTx, inclusionSlot);
            } catch (Throwable failure) {
                recordFailure("thread UTxO reconciliation", failure);
                return null;
            }
        }
    }

    private AppChainEngine.L1Ref observedL1TipPoint() {
        Supplier<AppChainEngine.L1Ref> supplier = currentL1PointSupplier;
        return supplier != null ? supplier.get() : null;
    }

    private static boolean samePoint(AppChainEngine.L1Ref expected,
                                     RollbackCapableStore.AppliedPoint actual) {
        return expected != null && actual != null
                && expected.slot() == actual.slot()
                && expected.blockHash() != null && expected.blockHash().length == 32
                && actual.blockHash() != null
                && HexUtil.encodeHexString(expected.blockHash())
                        .equalsIgnoreCase(actual.blockHash());
    }

    private static boolean samePoint(AppChainEngine.L1Ref left,
                                     AppChainEngine.L1Ref right) {
        return left != null && right != null
                && left.slot() == right.slot()
                && java.util.Arrays.equals(left.blockHash(), right.blockHash());
    }

    private void clearIdentityCandidate() {
        ledger.metaPutAll(
                Map.of(
                        META_SCRIPT_CANDIDATE_SLOT, 0L,
                        META_SCRIPT_CANDIDATE_BASE_INDEX, -1L),
                Map.of(
                        META_SCRIPT_CANDIDATE_POLICY, new byte[0],
                        META_SCRIPT_CANDIDATE_HASH, new byte[0],
                        META_SCRIPT_CANDIDATE_BASE_TX, new byte[0],
                        META_SCRIPT_ADOPTION_TX, new byte[0]));
    }

    private List<AnchorService.Confirmation> canonicalHistoryThrough(
            AnchorService.Confirmation current) {
        List<AnchorService.Confirmation> history = loadHistory();
        for (int i = 0; i < history.size(); i++) {
            AnchorService.Confirmation item = history.get(i);
            if (item.toHeight() == current.toHeight()
                    && item.l1Slot() == current.l1Slot()
                    && item.txHash().equals(current.txHash())) {
                return new ArrayList<>(history.subList(0, i + 1));
            }
        }
        return new ArrayList<>(List.of(current));
    }

    private record CommittedAnchorView(RollbackCapableStore.AppliedPoint point,
                                       Utxo anchorUtxo) {
    }

    void onL1Rollback(long rollbackToSlot) {
        synchronized (anchorLock) {
            try {
                ObservedBootstrap bootstrapObservation = observedBootstrap;
                if (bootstrapObservation != null
                        && bootstrapObservation.l1Slot() > rollbackToSlot) {
                    observedBootstrap = null;
                    pendingBootstrap = null;
                }
                ObservedSubmit submitObservation = observedSubmit;
                if (submitObservation != null && submitObservation.l1Slot() > rollbackToSlot) {
                    observedSubmit = null;
                }
                long candidateSlot = ledger.metaLong(META_SCRIPT_CANDIDATE_SLOT, 0L);
                if (candidateSlot > rollbackToSlot) {
                    clearIdentityCandidate();
                }
                long bootstrapSlot = ledger.metaLong(META_SCRIPT_BOOTSTRAP_SLOT, 0L);
                boolean adoptedIdentity = ledger.metaLong(META_SCRIPT_IDENTITY_ADOPTED, 0L) == 1L;
                if (bootstrapSlot > rollbackToSlot
                        || (adoptedIdentity && bootstrapSlot <= 0L)) {
                    resetRolledBackIdentity(rollbackToSlot, bootstrapSlot, adoptedIdentity);
                    return;
                }
                if (rollbackConfirmedHistory(rollbackToSlot)) {
                    // Co-sign/submission state was built against the rolled
                    // back thread output and confirmation frontier.
                    pendingCosign = null;
                    pendingSubmit = null;
                    observedSubmit = null;
                }
            } catch (Throwable failure) {
                recordFailure("L1 rollback", failure);
            }
        }
    }

    private void resetRolledBackIdentity(long rollbackToSlot, long identitySlot,
                                         boolean adoptedIdentity) {
        Map<String, Long> longs = new LinkedHashMap<>();
        longs.put(META_SCRIPT_BOOTSTRAP_SLOT, 0L);
        longs.put(META_SCRIPT_IDENTITY_ADOPTED, 0L);
        longs.put(META_SCRIPT_CANDIDATE_SLOT, 0L);
        longs.put(META_SCRIPT_OUT_INDEX, -1L);
        longs.put(META_SCRIPT_CANDIDATE_BASE_INDEX, -1L);
        longs.put(META_LAST_ANCHORED, 0L);
        longs.put(META_ANCHOR_FROM, 0L);
        longs.put(META_ANCHOR_SLOT, 0L);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        bytes.put(META_SCRIPT_POLICY_ID, new byte[0]);
        bytes.put(META_SCRIPT_HASH, new byte[0]);
        bytes.put(META_SCRIPT_BOOTSTRAP_TX, new byte[0]);
        bytes.put(META_SCRIPT_ADOPTION_TX, new byte[0]);
        bytes.put(META_SCRIPT_CANDIDATE_POLICY, new byte[0]);
        bytes.put(META_SCRIPT_CANDIDATE_HASH, new byte[0]);
        bytes.put(META_SCRIPT_CANDIDATE_BASE_TX, new byte[0]);
        bytes.put(META_ANCHOR_BLOCK_HASH, new byte[0]);
        bytes.put(META_ANCHOR_TX, new byte[0]);
        bytes.put(META_ANCHOR_HISTORY,
                AnchorService.ConfirmationHistory.encode(List.of()));
        ledger.metaPutAll(longs, bytes);
        pendingBootstrap = null;
        observedBootstrap = null;
        pendingCosign = null;
        pendingSubmit = null;
        observedSubmit = null;
        lastAnchorTxHash = null;
        lastAnchoredL1Slot = 0L;
        logWarnSafely("L1 rollback to slot {} invalidated script-anchor {} identity checkpoint "
                        + "at slot {} — identity reset for safe re-adoption/bootstrap",
                rollbackToSlot, adoptedIdentity ? "adopted" : "bootstrap", identitySlot);
    }

    private boolean rollbackConfirmedHistory(long rollbackToSlot) {
        long persistedSlot = ledger.metaLong(META_ANCHOR_SLOT, 0L);
        if (persistedSlot <= rollbackToSlot) {
            return false;
        }
        String rolledBackTx = ledger.metaString(META_ANCHOR_TX);
        List<AnchorService.Confirmation> retained = new ArrayList<>();
        for (AnchorService.Confirmation confirmation : loadHistory()) {
            if (confirmation.l1Slot() <= rollbackToSlot) {
                retained.add(confirmation);
            }
        }
        AnchorService.Confirmation survivor = retained.isEmpty() ? null : retained.getLast();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        longs.put(META_LAST_ANCHORED, survivor != null ? survivor.toHeight() : 0L);
        longs.put(META_ANCHOR_FROM, survivor != null ? survivor.fromHeight() : 0L);
        longs.put(META_ANCHOR_SLOT, survivor != null ? survivor.l1Slot() : 0L);
        bytes.put(META_ANCHOR_BLOCK_HASH,
                survivor != null ? survivor.blockHash() : new byte[0]);
        bytes.put(META_ANCHOR_TX, (survivor != null ? survivor.txHash() : "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.put(META_ANCHOR_HISTORY,
                AnchorService.ConfirmationHistory.encode(retained));
        ledger.metaPutAll(longs, bytes);
        lastAnchorTxHash = survivor != null ? survivor.txHash() : null;
        lastAnchoredL1Slot = survivor != null ? survivor.l1Slot() : 0L;
        logWarnSafely("L1 rollback to slot {} un-confirmed script-anchor tx {} — "
                        + "rewound to app height {}",
                rollbackToSlot, rolledBackTx, survivor != null ? survivor.toHeight() : 0L);
        return true;
    }

    private List<AnchorService.Confirmation> historyWith(AnchorService.Confirmation next) {
        List<AnchorService.Confirmation> history = new ArrayList<>(loadHistory());
        history.removeIf(item -> item.l1Slot() == next.l1Slot()
                && item.txHash().equals(next.txHash()));
        history.add(next);
        if (history.size() > AnchorService.ConfirmationHistory.MAX_ENTRIES) {
            history = new ArrayList<>(history.subList(
                    history.size() - AnchorService.ConfirmationHistory.MAX_ENTRIES,
                    history.size()));
        }
        return history;
    }

    private List<AnchorService.Confirmation> loadHistory() {
        byte[] encoded = ledger.metaBytes(META_ANCHOR_HISTORY);
        if (encoded == null || encoded.length == 0) {
            return List.of();
        }
        try {
            return AnchorService.ConfirmationHistory.decode(encoded);
        } catch (Throwable failure) {
            recordFailure("confirmation history", failure);
            return List.of();
        }
    }

    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        status.put("mode", "script");
        status.put("leader", leader);
        // address matches metadata-mode status: where the anchor lives (the
        // script address once bootstrapped, else the leader's wallet). The
        // wallet is reported separately too — it keeps paying fees after
        // bootstrap, so operators need it for top-ups.
        String address = anchorAddress();
        if (!address.isEmpty()) {
            status.put("address", address);
        }
        if (walletAddress != null) {
            status.put("walletAddress", walletAddress.getAddress());
        }
        status.putAll(identityStatus());
        status.put("lastAnchoredHeight", lastAnchoredHeight());
        status.put("observedAnchorCount", observedAnchorCount);
        status.put("anchoredCount", anchoredCount);
        PendingBootstrap bootstrap = pendingBootstrap;
        if (bootstrap != null) {
            status.put("pendingBootstrapTx", bootstrap.txHash());
        }
        ObservedBootstrap bootstrapObservation = observedBootstrap;
        if (bootstrapObservation != null) {
            status.put("bootstrapConfirmationObservedAtL1Slot", bootstrapObservation.l1Slot());
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
        ObservedSubmit submitObservation = observedSubmit;
        if (submitObservation != null) {
            status.put("confirmationObservedAtL1Slot", submitObservation.l1Slot());
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

    private Map<String, Object> identityStatus() {
        Map<String, Object> identity = new LinkedHashMap<>();
        byte[] policyId = ledger.metaBytes(META_SCRIPT_POLICY_ID);
        byte[] scriptHash = ledger.metaBytes(META_SCRIPT_HASH);
        boolean complete = policyId != null && policyId.length == 28
                && scriptHash != null && scriptHash.length == 28;
        identity.put("bootstrapped", complete);
        byte[] candidatePolicy = ledger.metaBytes(META_SCRIPT_CANDIDATE_POLICY);
        byte[] candidateHash = ledger.metaBytes(META_SCRIPT_CANDIDATE_HASH);
        identity.put("identityCandidatePending", !complete
                && candidatePolicy != null && candidatePolicy.length == 28
                && candidateHash != null && candidateHash.length == 28);
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

    private AnchorDatumCodec.AnchorDatum datumAtHeightZero() {
        List<byte[]> memberKeys = membersSupplier.get().stream()
                .map(HexUtil::decodeHexString)
                .toList();
        return new AnchorDatumCodec.AnchorDatum(
                AnchorDatumCodec.ABI_VERSION, chainId, 0L,
                new byte[32], new byte[32],
                AnchorDatumCodec.sortedKeys(memberKeys), thresholdSupplier.getAsInt());
    }

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
        return findAnchorUtxo(utxoStateSupplier.get(), policyId, scriptHash);
    }

    private Utxo findAnchorUtxo(UtxoState utxoState, byte[] policyId, byte[] scriptHash) {
        if (policyId == null || policyId.length != 28 || scriptHash == null || scriptHash.length != 28)
            return null;
        if (utxoState == null)
            return null;
        String scriptAddress = AddressProvider.getEntAddress(
                Credential.fromScript(scriptHash), network).getAddress();
        String policyHex = HexUtil.encodeHexString(policyId);

        // Normal steady state follows the exact durable outpoint. The bounded
        // address scan is only discovery/recovery after that outpoint is spent
        // (or while adopting an identity), limiting public-address dust cost.
        Utxo exact = exactThreadUtxo(utxoState, ledger.metaString(META_ANCHOR_TX),
                ledger.metaLong(META_SCRIPT_OUT_INDEX, -1L), scriptAddress, policyHex);
        if (exact != null) {
            return exact;
        }
        exact = exactThreadUtxo(utxoState, ledger.metaString(META_SCRIPT_CANDIDATE_BASE_TX),
                ledger.metaLong(META_SCRIPT_CANDIDATE_BASE_INDEX, -1L),
                scriptAddress, policyHex);
        if (exact != null) {
            return exact;
        }
        for (int page = 1; page <= ANCHOR_UTXO_MAX_PAGES; page++) {
            List<Utxo> candidates = utxoState.getUtxosByAddress(
                    scriptAddress, page, ANCHOR_UTXO_PAGE_SIZE);
            for (Utxo candidate : candidates) {
                if (isThreadUtxo(candidate, scriptAddress, policyHex)) {
                    return candidate;
                }
            }
            if (candidates.size() < ANCHOR_UTXO_PAGE_SIZE) {
                return null;
            }
        }
        log.warn("Script-anchor: thread UTxO not found within {} outputs at {}; observation deferred",
                ANCHOR_UTXO_PAGE_SIZE * ANCHOR_UTXO_MAX_PAGES, scriptAddress);
        return null;
    }

    private Utxo exactThreadUtxo(UtxoState utxoState, String txHash, long index,
                                 String scriptAddress, String policyHex) {
        if (txHash == null || !txHash.matches("(?i)[0-9a-f]{64}")
                || index < 0L || index > Integer.MAX_VALUE) {
            return null;
        }
        Utxo candidate = utxoState.getUtxo(txHash, (int) index).orElse(null);
        return isThreadUtxo(candidate, scriptAddress, policyHex) ? candidate : null;
    }

    private static boolean isThreadUtxo(Utxo candidate, String scriptAddress,
                                        String policyHex) {
        if (candidate == null || !scriptAddress.equals(candidate.address())
                || candidate.inlineDatum() == null || candidate.inlineDatum().length == 0) {
            return false;
        }
        return candidate.assets() != null
                && candidate.assets().stream().anyMatch(asset ->
                        policyHex.equalsIgnoreCase(asset.policyId())
                                && BigInteger.ONE.equals(asset.quantity()));
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
        AppChainEngine.L1Ref currentPoint = observedL1TipPoint();
        return currentPoint != null && currentPoint.slot() > 0
                ? currentPoint.slot() + anchorConfig.validitySlots() : 0;
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
            if (!CborStructurePreflight.accepts(bodyBytes, ANCHOR_TX_BODY_CBOR_LIMITS)) {
                return null;
            }
            DataItem item = CborSerializationUtil.deserialize(bodyBytes);
            return TransactionBody.deserialize((co.nstant.in.cbor.model.Map) item);
        } catch (Throwable failure) {
            logFailure("sign request body decode", failure);
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
            if (!CborStructurePreflight.accepts(body, ANCHOR_MESSAGE_CBOR_LIMITS)) {
                return null;
            }
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            if (items.size() != 4) {
                return null;
            }
            long version = ((UnsignedInteger) items.get(0)).getValue().longValueExact();
            if (version != 1)
                return null;
            byte[] bodyBytes = ((ByteString) items.get(1)).getBytes();
            byte[] policyId = ((ByteString) items.get(2)).getBytes();
            byte[] scriptHash = ((ByteString) items.get(3)).getBytes();
            if (bodyBytes.length == 0 || policyId.length != 28 || scriptHash.length != 28) {
                return null;
            }
            return new SignRequest(bodyBytes, policyId, scriptHash);
        } catch (Throwable failure) {
            logFailure("sign request decode", failure);
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
            if (!CborStructurePreflight.accepts(body, ANCHOR_MESSAGE_CBOR_LIMITS)) {
                return null;
            }
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            if (items.size() != 3) {
                return null;
            }
            long version = ((UnsignedInteger) items.get(0)).getValue().longValueExact();
            if (version != 1)
                return null;
            byte[] bodyHash = ((ByteString) items.get(1)).getBytes();
            byte[] signature = ((ByteString) items.get(2)).getBytes();
            if (bodyHash.length != 32
                    || signature.length != AppChainConfig.ED25519_SIGNATURE_BYTES) {
                return null;
            }
            return new SignatureReply(bodyHash, signature);
        } catch (Throwable failure) {
            logFailure("signature reply decode", failure);
            return null;
        }
    }

    /**
     * Publish a bounded, plugin-message-free health diagnostic. Callback
     * causes remain attached to synchronous exceptions where applicable, but
     * their text is never copied into status or routine logs.
     */
    private void recordFailure(String phase, Throwable failure) {
        preserveInterruptAndRethrowProcessFatal(failure);
        String errorType = failure.getClass().getName();
        lastError = "Script-anchor " + phase + " failed (errorType=" + errorType + ")";
        Throwable outcome = failure;
        try {
            log.warn("Script-anchor {} failed (errorType={})", phase, errorType);
        } catch (Throwable diagnosticFailure) {
            preserveInterrupt(diagnosticFailure);
            outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
        }
        LifecycleFailures.rethrowIfProcessFatal(outcome);
    }

    private void logFailure(String phase, Throwable failure) {
        preserveInterruptAndRethrowProcessFatal(failure);
        String errorType = failure.getClass().getName();
        Throwable outcome = failure;
        try {
            log.warn("Script-anchor {} failed (errorType={})", phase, errorType);
        } catch (Throwable diagnosticFailure) {
            preserveInterrupt(diagnosticFailure);
            outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
        }
        LifecycleFailures.rethrowIfProcessFatal(outcome);
    }

    private static void preserveInterruptAndRethrowProcessFatal(Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatal(failure);
        preserveInterrupt(failure);
    }

    private static void preserveInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void logInfoSafely(String format, Object... arguments) {
        guardDiagnostic(() -> log.info(format, arguments));
    }

    private void logWarnSafely(String format, Object... arguments) {
        guardDiagnostic(() -> log.warn(format, arguments));
    }

    private static void guardDiagnostic(Runnable diagnostic) {
        try {
            diagnostic.run();
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            preserveInterrupt(failure);
        }
    }

    // ------------------------------------------------------------------

    private record PendingBootstrap(String txHash, byte[] policyId, byte[] scriptHash, long datumHeight,
                                    long submittedAt) {
    }

    private record ObservedBootstrap(PendingBootstrap bootstrap, long l1Slot) {
    }

    private record PendingCosign(Transaction tx, byte[] bodyBytes, byte[] bodyHash, byte[] requestBody,
                                 Set<String> signerSetHex, Map<String, byte[]> signatures,
                                 long fromHeight, long toHeight, long startedAt) {
    }

    private record PendingSubmit(long fromHeight, long toHeight, String txHash, long submittedAt) {
    }

    private record ObservedSubmit(PendingSubmit submit, long l1Slot) {
    }
}
