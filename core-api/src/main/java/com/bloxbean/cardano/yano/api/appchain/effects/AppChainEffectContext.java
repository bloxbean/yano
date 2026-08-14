package com.bloxbean.cardano.yano.api.appchain.effects;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.l1view.BridgeDiffusionHandler;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;

import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * ADR-UTXO-009 SP-M6: the node-coupled surface handed to an
 * {@link AppEffectExecutorFactory} that opts into the context-aware
 * {@code create} overload. Executors like the EUTxO batch settlement stack
 * need more than a config map — the member identity, the diffusion channel
 * for co-sign rounds, committed-state queries, the L1 UTxO view, and
 * transaction submission. Every capability is scoped to the ONE chain the
 * factory is building for.
 *
 * <p>All methods are safe to call for the lifetime of the chain instance;
 * suppliers re-read live node state on each call.
 */
public interface AppChainEffectContext {

    String chainId();

    /** Build, sign and diffuse an envelope on a reserved system topic. */
    void diffuse(String topic, byte[] body);

    /** This node's app-chain member signing identity. */
    SignerProvider memberSigner();

    /** Current member set (lower-case Ed25519 public-key hex). */
    Supplier<Set<String>> members();

    /** Current membership threshold. */
    IntSupplier threshold();

    /** Query the chain's COMMITTED machine state. */
    AppQueryResult query(String path, byte[] request);

    /** The node's L1 UTxO view (may supply null before L1 sync is ready). */
    Supplier<UtxoState> l1UtxoView();

    /** Current L1 protocol parameters (may supply null before wiring). */
    Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams> protocolParams();

    /**
     * The node's phase-2 transaction evaluator, or null when unavailable —
     * script executors need ex-unit evaluation to assemble Plutus spends.
     */
    com.bloxbean.cardano.yano.api.TxEvaluationGateway txEvaluation();

    /**
     * Submit a signed L1 transaction through the node; returns the
     * transaction hash.
     */
    String submitTx(byte[] transactionCbor);

    /**
     * Register the {@code ~bridge/*} diffusion receiver for this chain (one
     * per chain; last registration wins).
     */
    void registerBridgeDiffusionHandler(BridgeDiffusionHandler handler);
}
