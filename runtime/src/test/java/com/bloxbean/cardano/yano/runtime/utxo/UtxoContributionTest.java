package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.runtime.genesis.AvvmAddressConverter;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import com.bloxbean.cardano.yano.api.utxo.index.IndexRequirements;
import com.bloxbean.cardano.yano.api.utxo.index.IndexWriter;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoChanges;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContext;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributor;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributorProvider;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtxoContributionTest {
    @Test void disabledCaptureDoesNotTraverseTransactionsOrInputs() {
        UtxoChangeBuilder builder = new UtxoChangeBuilder(false, IndexRequirements.NONE);
        builder.transaction((TransactionBody) null, true, null);
        builder.transaction((ByronTx) null, null);
        builder.protocolConsumed(null, -1, null);
    }
    @TempDir Path directory;
    private static final String ADDRESS = new Address(new byte[57]).toBech32();

    @Test void pruneFailureDoesNotAdvanceCursorOrDeleteUndoAndRetrySucceeds() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()),
                     Map.of(YanoPropertyKeys.Utxo.ROLLBACK_WINDOW, 0, YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(provider(new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { writer.put("undo", number(changes.point().blockNumber()), new byte[]{1}); }
                @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
                @Override public void stagePruneUndo(ChainPoint point, IndexWriter writer) {
                    writer.delete("undo", number(point.blockNumber()));
                    if (fail.get()) throw new IllegalStateException("Test prune failure");
                }
            }, IndexRequirements.NONE), Map.of());
            genesis(store, chain);
            apply(store, 1, List.of());
            store.pruneOnce();
            assertThat(chain.rocks().db().get(chain.rocks().handle(UtxoCfNames.UTXO_BLOCK_DELTA), number(1))).isNotNull();
            assertThat(chain.rocks().db().get(chain.rocks().handle(UtxoCfNames.UTXO_META), "prune.delta.cursor".getBytes(StandardCharsets.UTF_8))).isNull();
            fail.set(false);
            store.pruneOnce();
            assertThat(chain.rocks().db().get(chain.rocks().handle(UtxoCfNames.UTXO_BLOCK_DELTA), number(1))).isNull();
            assertThat(chain.rocks().db().get(chain.rocks().handle(UtxoCfNames.UTXO_META), "prune.delta.cursor".getBytes(StandardCharsets.UTF_8))).isEqualTo(number(1));
        }
    }

    @Test void captureIsImmutableOrderedAndResolvesSameBlockEffectiveInputs() {
        List<UtxoChanges> captured = new ArrayList<>();
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(provider(new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { captured.add(changes); }
                @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
            }, new IndexRequirements(true, true, false)), Map.of());
            genesis(store, chain);
            List<TransactionOutput> outputs = new ArrayList<>();
            store.setFilterChain(new StorageFilterChain(List.of(new StorageFilter() {
                @Override public boolean acceptUtxoOutput(UtxoFilterContext context, Block block, TransactionBody tx) { return false; }
            })));
            outputs.add(TransactionOutput.builder().address(ADDRESS).build());
            TransactionBody first = TransactionBody.builder().txHash("11".repeat(32)).inputs(Set.of()).outputs(outputs).build();
            TransactionBody second = TransactionBody.builder().txHash("22".repeat(32))
                    .inputs(Set.of(TransactionInput.builder().transactionId(first.getTxHash()).index(0).build()))
                    .outputs(List.of()).build();
            AtomicReference<String> projectionInput = new AtomicReference<>();
            store.setProjectionContributor(new CanonicalProjectionContributor() {
                @Override public boolean enabled() { return true; }
                @Override public boolean needsConsumedOutputAddresses() { return true; }
                @Override public void contributeBlock(BlockAppliedEvent event, ProjectionStagingWriter writer) { }
                @Override public void contributeByronBlock(ByronBlockProjectionEvent event, ProjectionStagingWriter writer) { }
                @Override public void rollbackFrom(long block) { }
                @Override public void contributeBlock(BlockAppliedEvent event, ConsumedOutputAddresses consumed, ProjectionStagingWriter writer) {
                    projectionInput.set(consumed.addressOf(first.getTxHash(), 0));
                }
            });
            apply(store, 1, List.of(first, second));
            assertThat(projectionInput.get()).isEqualTo(ADDRESS);
            outputs.clear();
            var changes = captured.getFirst();
            assertThat(changes.previous()).isEqualTo(ChainPoint.ORIGIN);
            assertThat(changes.transactions().getFirst().created()).hasSize(1);
            assertThat(changes.transactions().getLast().consumed().getFirst().resolution()).isEqualTo(UtxoChanges.Resolution.RESOLVED);
            assertThat(changes.transactions().getLast().consumed().getFirst().address()).isEqualTo(ADDRESS);
            assertThatThrownBy(() -> changes.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> changes.transactions().getFirst().created().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void maintenanceGuardsRunBeforeAnyMutation() {
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(provider(new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
            }, IndexRequirements.NONE), Map.of());
            assertThatThrownBy(() -> store.injectFaucetUtxo(ADDRESS, 1)).hasMessageContaining("unsupported");
            assertThatThrownBy(() -> store.injectBootstrapUtxos(List.of(), 1, 1, "11".repeat(32))).hasMessageContaining("unsupported");
            assertThatThrownBy(() -> store.rebuildFullStateFromGenesis(chain, Map.of(), 42, Map.of(), Map.of())).hasMessageContaining("unsupported");
            assertThat(store.getUtxosByAddress(ADDRESS, 1, 10)).isEmpty();
        }
    }

    @Test void protocolRemovalsAreVisibleAndUndeclaredGenesisFundsAreRejected() {
        List<UtxoChanges> captured = new ArrayList<>();
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(provider(new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { captured.add(changes); }
                @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
            }, new IndexRequirements(true, false, true)), Map.of());
            store.wireAllegraBootstrapRemoval(chain);
            String byron = AvvmAddressConverter.convertAvvmToByronAddress(Base64.getEncoder().encodeToString(new byte[32])).orElseThrow();
            store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(byron, BigInteger.TEN), 0, 0, "00".repeat(32));
            assertThatThrownBy(() -> store.storeGenesisUtxos(Map.of(HexFormat.of().formatHex(new byte[57]), BigInteger.ONE),
                    42, 0, 0, "00".repeat(32))).hasMessageContaining("differ");
            assertThatThrownBy(() -> store.storeByronGenesisUtxos(Map.of(), 0, 0, "00".repeat(32))).hasMessageContaining("unsupported");
            store.applyBlock(new BlockAppliedEvent(Era.Allegra, 100, 1, "11".repeat(32),
                    Block.builder().era(Era.Allegra).transactionBodies(List.of()).invalidTransactions(List.of()).build()));
            assertThat(captured.getFirst().protocolConsumed()).singleElement().satisfies(input -> {
                assertThat(input.address()).isEqualTo(byron);
                assertThat(input.outpoint().txHash()).isEqualTo(GenesisUtxos.byron(byron, BigInteger.TEN, 0, 0, "00".repeat(32)).txHash());
            });
        }
    }

    @Test void deferredGenesisMaterializesOnceAfterFastForwardWithoutResurrectingSpentFunds() {
        try (var chain = new DirectRocksDBChainState(directory.toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(provider(new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
            }, IndexRequirements.NONE), Map.of());
            store.wireAllegraBootstrapRemoval(chain);
            Map<String, BigInteger> funds = Map.of(HexFormat.of().formatHex(new byte[57]), BigInteger.TEN);
            store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0, "00".repeat(32), funds);
            apply(store, 1, List.of());
            apply(store, 2, List.of());
            store.storeGenesisUtxos(funds, 42, 0, 0, "00".repeat(32));
            assertThat(store.getUtxosByAddress(ADDRESS, 1, 10)).hasSize(1);
            var genesis = GenesisUtxos.shelley(funds.keySet().iterator().next(), BigInteger.TEN, 42, 0, 0, "00".repeat(32));
            apply(store, 3, List.of(TransactionBody.builder().txHash("33".repeat(32))
                    .inputs(Set.of(new TransactionInput(genesis.txHash(), 0))).outputs(List.of()).build()));
            store.storeGenesisUtxos(funds, 42, 0, 0, "00".repeat(32));
            assertThat(store.getUtxosByAddress(ADDRESS, 1, 10)).isEmpty();
        }
    }

    @Test void contributorRebindReadsOnlyReplacementDatabaseHandles() {
        AtomicReference<ChainPoint> rebound = new AtomicReference<>();
        AtomicReference<UtxoIndexContext> readContext = new AtomicReference<>();
        try (var chain = new DirectRocksDBChainState(directory.resolve("live").toString());
             var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false))) {
            store.registerIndexContributor(new UtxoIndexContributorProvider() {
                @Override public String id() { return "rebind"; }
                @Override public UtxoIndexContributor create(UtxoIndexContext context) {
                    readContext.set(context);
                    return new UtxoIndexContributor() {
                        @Override public void stageApply(UtxoChanges changes, IndexWriter writer) { }
                        @Override public void stageRollback(ChainPoint point, IndexWriter writer) { }
                        @Override public void reinitialize() { rebound.set(context.read(UtxoIndexContext.ReadScope::appliedPoint)); }
                    };
                }
            }, Map.of());
            genesis(store, chain);
            apply(store, 1, List.of());
            Path snapshot = directory.resolve("snapshot");
            chain.createSnapshot(snapshot.toString());
            apply(store, 2, List.of());
            store.prepareForStorageReplacement();
            assertThatThrownBy(() -> readContext.get().read(UtxoIndexContext.ReadScope::appliedPoint))
                    .hasMessageContaining("being replaced");
            chain.restoreFromSnapshot(snapshot.toString());
            store.reinitialize();
            assertThat(rebound.get()).isEqualTo(new ChainPoint(1, 10, "%064x".formatted(1)));
            apply(store, 2, List.of());
        }
    }

    private static UtxoIndexContributorProvider provider(UtxoIndexContributor contributor, IndexRequirements requirements) {
        return new UtxoIndexContributorProvider() {
            @Override public String id() { return "test.index"; }
            @Override public IndexRequirements requirements() { return requirements; }
            @Override public UtxoIndexContributor create(UtxoIndexContext context) { return contributor; }
        };
    }
    private static void genesis(DefaultUtxoStore store, DirectRocksDBChainState chain) {
        store.wireAllegraBootstrapRemoval(chain);
        store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0, "00".repeat(32));
    }
    private static void apply(DefaultUtxoStore store, int block, List<TransactionBody> transactions) {
        store.applyBlock(new BlockAppliedEvent(Era.Babbage, block * 10L, block, "%064x".formatted(block),
                Block.builder().transactionBodies(transactions).invalidTransactions(List.of()).build()));
    }
    private static byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
}
