package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionGenesisBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionGenesisReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionRowBuilder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures the genesis distribution into a projection archive, exactly once.
 *
 * <p>Genesis funds are produced by no block, so nothing in the block pipeline emits them. Before
 * this existed, a projection archive silently omitted the entire distribution - on preprod a
 * single Byron output of 30,000,000,000,000,000 lovelace - while reporting itself complete. An
 * archive in that state cannot reconstruct the UTXO set from its own contents.
 *
 * <p>The distribution comes from one provider covering <strong>both</strong> eras. Taking Shelley
 * from the genesis-block event's payload and Byron from somewhere else would let two sources
 * drift, and a projection that captured a stale half would look complete while being wrong. The
 * event supplies the lifecycle trigger and the first-block coordinate, nothing more; if it
 * happens to carry Shelley funds they are cross-checked against the provider and a disagreement
 * is fatal.
 */
public final class ProjectionGenesisBootstrap {

    private final ProjectionIdentity projectionIdentity;
    private final GenesisUtxoProvider provider;
    private final YaciUtxoHistoryDecoder decoder;

    public ProjectionGenesisBootstrap(ProjectionIdentity projectionIdentity,
                                      GenesisUtxoProvider provider,
                                      YaciUtxoHistoryDecoder decoder) {
        this.projectionIdentity = Objects.requireNonNull(projectionIdentity, "projectionIdentity");
        this.provider = provider == null ? GenesisUtxoProvider.EMPTY : provider;
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Deterministic identity for a distribution: the network this archive is bound to, plus a
     * digest of the exact normalised rows.
     *
     * <p>Both halves matter. The network binding alone would accept an edited genesis file; the
     * row digest alone would accept the same distribution on a different network.
     */
    public String identityOf(List<GenesisUtxo> utxos) {
        String binding = projectionIdentity.networkIdentity().networkMagic()
                + ":" + projectionIdentity.networkIdentity().genesisHash()
                + "|" + GenesisUtxos.digest(utxos);
        return hex(sha256(binding.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Run the bootstrap if this archive has not recorded one.
     *
     * <p>Idempotent by construction rather than by flag: a matching receipt short-circuits, and
     * the sink commits rows and receipt in one transaction, so there is no state in which the
     * rows are durable but unrecorded. Safe to call from the event trigger and again from startup
     * reconciliation.
     *
     * @return the receipt, whether newly committed or already present
     */
    public ProjectionGenesisReceipt bootstrap(ProjectionSink sink, long blockNumber, long slot,
                                              int epoch, long blockTime, byte[] blockHash,
                                              byte[] parentHash, String blockHashHex) {
        Objects.requireNonNull(sink, "sink");
        List<GenesisUtxo> utxos = provider.genesisUtxos(blockNumber, slot, blockHashHex);
        String identity = identityOf(utxos);

        Optional<ProjectionGenesisReceipt> existing = sink.genesisReceipt();
        if (existing.isPresent()) {
            if (!existing.get().matches(identity)) {
                throw new IllegalStateException("this archive recorded genesis "
                        + existing.get().identity() + " but this node derives " + identity
                        + "; a different network or an edited genesis configuration cannot be"
                        + " projected into an existing archive");
            }
            return existing.get();
        }

        var fact = decoder.genesisFact(utxos);
        List<ArchiveRow> rows = ProjectionRowBuilder.genesisRows(
                projectionIdentity.networkIdentity(), projectionIdentity.canonicalProjectionVersion(),
                blockNumber, slot, epoch, blockTime, blockHash, parentHash, fact);

        BigInteger total = utxos.stream().map(GenesisUtxo::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        // An empty distribution still commits a receipt. "Nothing to distribute" and "never
        // bootstrapped" must not look alike, or requirement 9's coverage gate could never open
        // for a devnet.
        return sink.commitGenesis(new ProjectionGenesisBatch(
                projectionIdentity, identity, GenesisUtxos.digest(utxos), total, rows));
    }

    /**
     * Cross-check the genesis-block event's own Shelley funds against the provider.
     *
     * <p>The event is only the trigger, but it does carry a Shelley payload. If the two disagree
     * the archive would be captured from a different distribution than the ledger initialised
     * from, so this fails closed rather than preferring either side.
     */
    public void verifyEventAgreesWithProvider(java.util.Map<String, BigInteger> eventInitialFunds,
                                              List<GenesisUtxo> providerUtxos, long networkMagic) {
        if (eventInitialFunds == null || eventInitialFunds.isEmpty()) return;
        var fromEvent = GenesisUtxos.of(eventInitialFunds, java.util.Map.of(), networkMagic, 0, 0,
                "00".repeat(32));
        var fromProvider = providerUtxos.stream()
                .filter(utxo -> !utxo.isByron())
                .map(utxo -> new GenesisUtxo(utxo.address(), utxo.amount(), utxo.txHash(),
                        utxo.outputIndex(), utxo.originType(), 0, 0, "00".repeat(32)))
                .toList();
        String eventDigest = GenesisUtxos.digest(fromEvent);
        String providerDigest = GenesisUtxos.digest(fromProvider);
        if (!eventDigest.equals(providerDigest)) {
            throw new IllegalStateException("the genesis block event's Shelley funds (" + eventDigest
                    + ") disagree with the genesis provider (" + providerDigest
                    + "); refusing to project a distribution the ledger did not initialise from");
        }
    }

    /** The distribution this node would capture, for the event cross-check. */
    public List<GenesisUtxo> distribution(long blockNumber, long slot, String blockHashHex) {
        return provider.genesisUtxos(blockNumber, slot, blockHashHex);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
