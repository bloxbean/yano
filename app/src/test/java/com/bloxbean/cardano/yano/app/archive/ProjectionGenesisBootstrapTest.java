package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxoProvider;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionGenesisBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionGenesisReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Genesis capture is the one part of a projection archive that no block produces, so nothing
 * downstream can notice it was skipped. These pin the guards that make that impossible.
 */
class ProjectionGenesisBootstrapTest {

    private static final String BYRON_ADDR = "FHnt4NL7yPXuYUxBF33VX5dZMBDAab2kvSNLRzCskvuKNCSDknzrQvKeQhGUw5a";
    private static final String SHELLEY_HEX = "00" + "11".repeat(28) + "22".repeat(28);
    private static final String ZERO_HASH = "00".repeat(32);

    private static ProjectionIdentity identity(int magic, String genesisHash) {
        return new ProjectionIdentity(new ArchiveNetworkIdentity(magic, genesisHash), "ducklake", 1,
                Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));
    }

    private static ProjectionGenesisBootstrap bootstrap(ProjectionIdentity id, GenesisUtxoProvider provider) {
        return new ProjectionGenesisBootstrap(id, provider,
                new YaciUtxoHistoryDecoder(slot -> 0, slot -> 0));
    }

    private static GenesisUtxoProvider providerOf(Map<String, BigInteger> shelley,
                                                  Map<String, BigInteger> byron, long magic) {
        return (blockNumber, slot, blockHash) ->
                GenesisUtxos.of(shelley, byron, magic, blockNumber, slot, blockHash);
    }

    /**
     * Records what was committed and can pretend a bootstrap already happened.
     *
     * <p>Only the genesis surface is implemented; the block path is not exercised here, and a
     * method that is reached unexpectedly should fail loudly rather than return a plausible
     * default that hides the mistake.
     */
    private static final class FakeSink
            implements com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink {
        ProjectionGenesisReceipt stored;
        ProjectionGenesisBatch lastBatch;

        @Override public String engine() { return "fake"; }
        @Override public void initialize(ProjectionIdentity expected) { }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate coordinate() {
            return com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate.NONE;
        }
        @Override public Optional<com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt>
                receiptFor(long firstBlock) { return Optional.empty(); }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt append(
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch batch,
                com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader artifacts) {
            throw new UnsupportedOperationException("block append is not part of this test");
        }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance.Result maintain(
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance.Budget budget) {
            throw new UnsupportedOperationException("maintenance is not part of this test");
        }
        @Override public com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth health() {
            return com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth.ready();
        }
        @Override public void close() { }
        @Override public Optional<ProjectionGenesisReceipt> genesisReceipt() {
            return Optional.ofNullable(stored);
        }

        @Override public ProjectionGenesisReceipt commitGenesis(ProjectionGenesisBatch batch) {
            lastBatch = batch;
            stored = new ProjectionGenesisReceipt(batch.identity(), batch.rowDigest(),
                    batch.rows().size(), batch.totalLovelace(), Instant.now());
            return stored;
        }
    }

    @Test
    void theIdentityBindsBothTheNetworkAndTheDistribution() {
        // Either half alone is insufficient: the network binding would accept an edited genesis
        // file, and the row digest would accept the same distribution on a different network.
        var utxos = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 1, 0, 0, ZERO_HASH);
        var edited = GenesisUtxos.of(Map.of(SHELLEY_HEX, BigInteger.TWO), Map.of(), 1, 0, 0, ZERO_HASH);

        var onNetworkOne = bootstrap(identity(1, "genesis-a"), GenesisUtxoProvider.EMPTY);
        var onNetworkTwo = bootstrap(identity(2, "genesis-b"), GenesisUtxoProvider.EMPTY);

        assertThat(onNetworkOne.identityOf(utxos))
                .as("a different network must not share an identity")
                .isNotEqualTo(onNetworkTwo.identityOf(utxos));
        assertThat(onNetworkOne.identityOf(utxos))
                .as("an edited distribution must not share an identity")
                .isNotEqualTo(onNetworkOne.identityOf(edited));
        assertThat(onNetworkOne.identityOf(utxos))
                .as("and it must be stable")
                .isEqualTo(onNetworkOne.identityOf(utxos));
    }

    @Test
    void aFreshArchiveIsSeededWithTheCompleteDistribution() {
        var id = identity(1, "genesis-a");
        var sink = new FakeSink();
        var boot = bootstrap(id, providerOf(Map.of(SHELLEY_HEX, BigInteger.valueOf(5)),
                Map.of(BYRON_ADDR, BigInteger.valueOf(30_000_000_000_000_000L)), 1));

        var receipt = boot.bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH);

        assertThat(receipt.totalLovelace())
                .as("both eras, not just one")
                .isEqualTo(BigInteger.valueOf(30_000_000_000_000_005L));
        assertThat(sink.lastBatch.rows()).isNotEmpty();
    }

    @Test
    void aSecondBootstrapWithTheSameDistributionIsANoOp() {
        // The crash boundary after commit: replay must not write a second genesis.
        var id = identity(1, "genesis-a");
        var sink = new FakeSink();
        var boot = bootstrap(id, providerOf(Map.of(), Map.of(BYRON_ADDR, BigInteger.TEN), 1));

        var first = boot.bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH);
        sink.lastBatch = null;
        var second = boot.bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH);

        assertThat(second.identity()).isEqualTo(first.identity());
        assertThat(sink.lastBatch).as("nothing was re-committed").isNull();
    }

    @Test
    void anArchiveRecordingADifferentGenesisIsRefused() {
        var sink = new FakeSink();
        bootstrap(identity(1, "genesis-a"), providerOf(Map.of(), Map.of(BYRON_ADDR, BigInteger.ONE), 1))
                .bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH);

        // Same archive, different network configuration: this must not append a second genesis.
        var other = bootstrap(identity(2, "genesis-b"),
                providerOf(Map.of(), Map.of(BYRON_ADDR, BigInteger.ONE), 2));

        assertThatThrownBy(() -> other.bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be projected into an existing archive");
    }

    @Test
    void anEmptyDistributionStillCommitsAReceipt() {
        // Devnets distribute nothing; the coverage gate must still be able to open.
        var sink = new FakeSink();
        var boot = bootstrap(identity(1, "genesis-a"), GenesisUtxoProvider.EMPTY);

        var receipt = boot.bootstrap(sink, 0, 0, 0, 0, new byte[32], new byte[32], ZERO_HASH);

        assertThat(receipt.rowCount()).isZero();
        assertThat(receipt.totalLovelace()).isEqualTo(BigInteger.ZERO);
        assertThat(sink.genesisReceipt()).isPresent();
    }

    @Test
    void theEventsShelleyFundsMustAgreeWithTheProvider() {
        // The event is only the trigger. If it also carries Shelley funds and they disagree, the
        // archive would be built from a different distribution than the ledger initialised from.
        var boot = bootstrap(identity(1, "genesis-a"),
                providerOf(Map.of(SHELLEY_HEX, BigInteger.ONE), Map.of(), 1));
        List<GenesisUtxo> distribution = boot.distribution(0, 0, ZERO_HASH);

        // Agreement passes.
        boot.verifyEventAgreesWithProvider(Map.of(SHELLEY_HEX, BigInteger.ONE), distribution, 1);

        // Disagreement is fatal.
        assertThatThrownBy(() -> boot.verifyEventAgreesWithProvider(
                Map.of(SHELLEY_HEX, BigInteger.valueOf(999)), distribution, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disagree with the genesis provider");
    }

    @Test
    void anEventCarryingNoShelleyFundsIsNotTreatedAsDisagreement() {
        // Byron-start networks publish no Shelley funds on the event; that is not a conflict.
        var boot = bootstrap(identity(1, "genesis-a"),
                providerOf(Map.of(), Map.of(BYRON_ADDR, BigInteger.ONE), 1));

        boot.verifyEventAgreesWithProvider(Map.of(), boot.distribution(0, 0, ZERO_HASH), 1);
        boot.verifyEventAgreesWithProvider(null, boot.distribution(0, 0, ZERO_HASH), 1);
    }
}
