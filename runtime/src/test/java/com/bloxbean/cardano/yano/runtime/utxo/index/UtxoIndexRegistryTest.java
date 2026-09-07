package com.bloxbean.cardano.yano.runtime.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.utxo.index.IndexWriter;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoChanges;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContext;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributor;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributorProvider;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtxoIndexRegistryTest {
    @TempDir Path directory;
    private DirectRocksDBChainState chain;
    private final Object lock = new Object();
    private ChainPoint applied = ChainPoint.ORIGIN;
    private UtxoIndexRegistry registry;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach void open() {
        chain = new DirectRocksDBChainState(directory.toString());
        registry = registry();
    }

    private UtxoIndexRegistry registry() {
        return new UtxoIndexRegistry(chain::rocks, lock, () -> applied, () -> 7, "42:test-genesis", LoggerFactory.getLogger(getClass()));
    }

    @AfterEach void close() { registry.close(); chain.close(); }

    @Test void commitAbortFailureIsolationRollbackAndReplay() throws Exception {
        Probe good = new Probe("z-good");
        Probe bad = new Probe("a-fails");
        registry.register(good, Map.of());
        registry.register(bad, Map.of());
        genesis();
        apply(1, true);
        assertThat(calls).containsExactly("a-fails", "z-good");
        assertThat(good.value(1)).isNotNull();
        bad.fail = true;
        apply(2, false); // abandoned outer transaction publishes nothing, including failure markers
        assertThat(good.value(2)).isNull();
        assertThat(bad.available()).isTrue();
        apply(2, true);
        assertThat(good.value(2)).isNotNull();
        assertThat(bad.value(2)).isNull();
        assertThat(bad.available()).isFalse();
        bad.fail = false;
        apply(3, true);
        assertThat(bad.available()).isFalse(); // no automatic repair on the next good block
        rollback(point(1), false);
        assertThat(bad.available()).isFalse();
        assertThat(good.value(3)).isNotNull();
        rollback(point(1), true);
        assertThat(good.value(2)).isNull();
        assertThat(good.value(3)).isNull();
        assertThat(bad.available()).isTrue();
        apply(2, true);
        assertThat(bad.available()).isTrue();
    }

    @Test void corruptHostMetadataMakesQueriesUnavailableAndRefusesMutation() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        genesis();
        byte[] id = "probe".getBytes(StandardCharsets.UTF_8);
        byte[] key = ByteBuffer.allocate(12 + id.length).put((byte) 1).putShort((short) id.length)
                .put(id).put((byte) 0).putLong(0).array();
        chain.rocks().db().put(chain.rocks().handle(IndexStorage.META), key, new byte[]{1});
        assertThat(probe.available()).isFalse();
        assertThatThrownBy(() -> registry.requireAvailable("probe")).hasMessageContaining("Invalid contributor metadata");
        assertThatThrownBy(() -> apply(1, true)).hasMessageContaining("Invalid contributor metadata");
        assertThat(probe.value(1)).isNull();
    }

    @Test void rollbackToSameHeightWithDifferentHashDoesNotClaimAvailability() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        genesis();
        apply(1, true);
        rollback(new ChainPoint(1, point(1).slot(), "ff".repeat(32)), true);
        assertThat(probe.available()).isFalse();
    }

    @Test void missingUndoFailsClosedAndStateSurvivesReopen() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        genesis();
        apply(1, true);
        synchronized (lock) {
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                registry.stagePruneUndo(batch, point(1));
                chain.rocks().db().write(options, batch);
            }
        }
        registry.close();
        chain.close();
        chain = new DirectRocksDBChainState(directory.toString());
        registry = registry();
        Probe reopened = new Probe("probe");
        registry.register(reopened, Map.of());
        registry.freeze(true, false);
        assertThat(reopened.available()).isTrue();
        rollback(ChainPoint.ORIGIN, true);
        assertThat(reopened.available()).isFalse();
    }

    @Test void scopedStorageCommittedReadsPaginationAndLifetime() throws Exception {
        var first = new IndexStorage(chain::rocks, "one");
        var other = new IndexStorage(chain::rocks, "one.other");
        IndexWriter retained;
        try (WriteBatch batch = new WriteBatch(); var writer = first.open(batch); WriteOptions options = new WriteOptions()) {
            retained = writer;
            writer.put("items", new byte[]{1}, new byte[]{11});
            writer.put("items", new byte[]{2}, new byte[]{22});
            writer.put("items_more", new byte[]{1}, new byte[]{33});
            assertThat(writer.getCommitted("items", new byte[]{1})).isNull();
            assertThatThrownBy(() -> writer.put("../utxo_unspent", new byte[0], new byte[0])).isInstanceOf(IllegalArgumentException.class);
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { writer.getCommitted("items", new byte[]{1}); }
                catch (Throwable failure) { wrongThread.set(failure); }
            });
            thread.start(); thread.join();
            assertThat(wrongThread.get()).isInstanceOf(IllegalStateException.class);
            chain.rocks().db().write(options, batch);
        }
        assertThatThrownBy(() -> retained.put("items", new byte[0], new byte[0])).isInstanceOf(IllegalStateException.class);
        try (var reader = first.open(null); var foreign = other.open(null)) {
            assertThat(foreign.getCommitted("items", new byte[]{1})).isNull();
            var page = reader.scanCommitted("items", new byte[0], null, 1);
            assertThat(page.entries()).hasSize(1);
            assertThat(page.entries().getFirst().value()).containsExactly((byte) 11);
            assertThat(reader.scanCommitted("items", new byte[0], page.nextCursor(), 1).entries().getFirst().value()).containsExactly((byte) 22);
            assertThatThrownBy(() -> foreign.scanCommitted("items", new byte[0], page.nextCursor(), 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> reader.scanCommitted("items_more", new byte[0], page.nextCursor(), 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void duplicateFrozenRegistrationAndExpiredReadScopesAreRejected() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        assertThatThrownBy(() -> registry.register(new Probe("probe"), Map.of())).isInstanceOf(IllegalArgumentException.class);
        genesis();
        assertThatThrownBy(() -> registry.register(new Probe("later"), Map.of())).isInstanceOf(IllegalStateException.class);
        UtxoIndexContext.ReadScope escaped = probe.context.read(scope -> scope);
        assertThatThrownBy(escaped::generation).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(escaped::available).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.requireMaintenanceAllowed("injection")).isInstanceOf(IllegalStateException.class);
        registry.close();
        assertThatThrownBy(() -> probe.context.read(scope -> scope.generation())).isInstanceOf(IllegalStateException.class);
    }

    @Test void schemaMismatchAndMissingInitializationFailClosed() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        registry.freeze(true, false);
        apply(1, true);
        assertThat(probe.available()).isFalse();
        registry.close();
        registry = registry();
        Probe changed = new Probe("probe") { @Override public int schemaVersion() { return 2; } };
        assertThatThrownBy(() -> registry.register(changed, Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("schema/network");
    }

    @Test void missingBlockAndUnrewoundReplacementCannotClaimCompleteness() throws Exception {
        Probe probe = new Probe("probe");
        registry.register(probe, Map.of());
        genesis();
        apply(1, true);
        assertThatThrownBy(() -> apply(1, true)).hasMessageContaining("Non-forward");
        assertThat(probe.available()).isTrue();
        apply(3, true);
        assertThat(probe.available()).isFalse();
        rollback(point(1), true);
        assertThat(probe.available()).isTrue();
    }

    private void genesis() throws Exception {
        registry.freeze(true, false);
        synchronized (lock) {
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                registry.stageGenesis(batch, "42:test-genesis", List.of());
                chain.rocks().db().write(options, batch);
            }
        }
    }

    private void apply(int number, boolean commit) throws Exception {
        synchronized (lock) {
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                registry.stageApply(batch, new UtxoChanges(applied, point(number), "Conway", List.of()));
                if (commit) { chain.rocks().db().write(options, batch); applied = point(number); }
            }
        }
    }

    private void rollback(ChainPoint target, boolean commit) throws Exception {
        synchronized (lock) {
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                registry.stageRollback(batch, target);
                if (commit) { chain.rocks().db().write(options, batch); applied = target; }
            }
        }
    }

    private static ChainPoint point(int n) { return new ChainPoint(n, n * 10L, "%064x".formatted(n)); }
    private static byte[] key(long n) { return ByteBuffer.allocate(8).putLong(n).array(); }

    private class Probe implements UtxoIndexContributorProvider {
        private final String id;
        UtxoIndexContext context;
        boolean fail;
        Probe(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public UtxoIndexContributor create(UtxoIndexContext context) {
            this.context = context;
            return new UtxoIndexContributor() {
                @Override public void stageApply(UtxoChanges changes, IndexWriter writer) {
                    calls.add(id);
                    writer.put("blocks", key(changes.point().blockNumber()), new byte[]{1});
                    if (fail) throw new IllegalArgumentException("Test failure");
                }
                @Override public void stageRollback(ChainPoint target, IndexWriter writer) {
                    writer.deleteRange("blocks", key(target.blockNumber() + 1), key(Long.MAX_VALUE));
                }
            };
        }
        byte[] value(int block) { return context.read(scope -> scope.getCommitted("blocks", key(block))); }
        boolean available() { return context.read(UtxoIndexContext.ReadScope::available); }
    }
}
