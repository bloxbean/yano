package org.yanoproject.api;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cardano host capabilities offered to plugins, registered under {@link #SERVICE_KEY} before plugin
 * {@code init} in Cardano mode only.
 *
 * <p>Parameter suppliers stay live and distinct: linear fees use tracked params; script pricing retains the
 * host's static-file fallback. A newly registered body-retention requirement is owned by its caller until
 * permanent close. {@link #epochSnapshots()} returns empty until the host has wired its ledger-state reader,
 * and the same reader afterwards; it stays empty on hosts without an account-state store or epoch
 * parameters.</p>
 */
public record CardanoHostServices(
        Function<byte[], String> txSubmitter,
        Supplier<UtxoState> utxoState,
        ChainBlockReader chain,
        Function<OptionalLong, BlockBodyRetentionRegistry.Registration> retainBodies,
        TxEvaluationGateway txEvaluation,
        Supplier<ProtocolParamsSnapshot> protocolParameters,
        Supplier<ProtocolParams> pricingParameters,
        Supplier<Optional<EpochSnapshotReader>> epochSnapshots) {
    public static final String SERVICE_KEY = "org.yanoproject.host.cardano-services";

    public CardanoHostServices {
        Objects.requireNonNull(txSubmitter, "txSubmitter");
        Objects.requireNonNull(utxoState, "utxoState");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(retainBodies, "retainBodies");
        Objects.requireNonNull(txEvaluation, "txEvaluation");
        Objects.requireNonNull(protocolParameters, "protocolParameters");
        Objects.requireNonNull(pricingParameters, "pricingParameters");
        Objects.requireNonNull(epochSnapshots, "epochSnapshots");
    }
}
