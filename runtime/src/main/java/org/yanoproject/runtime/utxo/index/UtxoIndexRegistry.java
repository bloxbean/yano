package org.yanoproject.runtime.utxo.index;

import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.genesis.GenesisUtxo;
import org.yanoproject.api.utxo.index.IndexReader;
import org.yanoproject.api.utxo.index.IndexRequirements;
import org.yanoproject.api.utxo.index.IndexWriter;
import org.yanoproject.api.utxo.index.IndexUnavailableException;
import org.yanoproject.api.utxo.index.UtxoChanges;
import org.yanoproject.api.utxo.index.UtxoIndexContext;
import org.yanoproject.api.utxo.index.UtxoIndexContributor;
import org.yanoproject.api.utxo.index.UtxoIndexContributorProvider;
import org.yanoproject.runtime.db.RocksDbContext;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Frozen participants in the owner's transaction; not an event bus or an index implementation. */
public final class UtxoIndexRegistry implements AutoCloseable {
    private final Supplier<RocksDbContext> storage;
    private final Object ownerLock;
    private final Supplier<ChainPoint> applied;
    private final LongSupplier generation;
    private final String network;
    private final Logger log;
    private final List<Participant> participants = new ArrayList<>();
    private boolean frozen;
    private boolean closed;
    private boolean readsPaused;
    private IndexRequirements requirements = IndexRequirements.NONE;

    private record Participant(String id, boolean builtin, IndexRequirements requirements,
                               IndexStorage storage, IndexMetadata metadata, UtxoIndexContributor contributor) { }

    public UtxoIndexRegistry(Supplier<RocksDbContext> storage, Object ownerLock,
                             Supplier<ChainPoint> applied, LongSupplier generation,
                             String network, Logger log) {
        this.storage = storage;
        this.ownerLock = ownerLock;
        this.applied = applied;
        this.generation = generation;
        this.network = network;
        this.log = log;
    }

    public void register(UtxoIndexContributorProvider provider, Map<String, String> config) {
        register(provider, config, false, Map.of());
    }

    /** Side-effect-free startup preflight, including persisted identity and storage requirements. */
    public void validateRegistration(UtxoIndexContributorProvider provider, boolean utxoEnabled, boolean filtered) {
        synchronized (ownerLock) {
            if (frozen || closed) throw new IllegalStateException("Contributor registration is closed");
            String id = provider.id();
            if ("wallet".equals(id)) throw new IllegalArgumentException("Reserved contributor: wallet");
            if (participants.stream().anyMatch(p -> p.id.equals(id))) throw new IllegalArgumentException("Duplicate contributor: " + id);
            int schema = provider.schemaVersion();
            if (schema < 1) throw new IllegalArgumentException("Invalid contributor schema version");
            new IndexStorage(storage, id); // Validate the namespace without allocating native resources.
            new IndexMetadata(storage, id, schema, network).read();
            IndexRequirements requested = Objects.requireNonNull(provider.requirements());
            if (!utxoEnabled || filtered && requested.requiresFullUtxoStorage()) {
                throw new IllegalArgumentException("Contributor requires enabled, unfiltered UTxO storage");
            }
        }
    }

    public void registerBuiltin(UtxoIndexContributorProvider provider, Map<String, String> tables) {
        register(provider, Map.of(), true, tables);
    }

    private void register(UtxoIndexContributorProvider provider, Map<String, String> config,
                          boolean builtin, Map<String, String> tables) {
        synchronized (ownerLock) {
            if (frozen || closed) throw new IllegalStateException("Contributor registration is closed");
            String id = provider.id();
            if (!builtin && "wallet".equals(id)) throw new IllegalArgumentException("Reserved contributor: wallet");
            if (participants.stream().anyMatch(p -> p.id.equals(id))) throw new IllegalArgumentException("Duplicate contributor: " + id);
            int schema = provider.schemaVersion();
            if (schema < 1) throw new IllegalArgumentException("Invalid contributor schema version");
            IndexStorage scoped = new IndexStorage(storage, id, tables);
            IndexMetadata metadata = new IndexMetadata(storage, id, schema, network);
            metadata.read(); // Reject incompatible namespaces before activating plugin code.
            IndexRequirements requested = Objects.requireNonNull(provider.requirements());
            UtxoIndexContributor contributor = Objects.requireNonNull(provider.create(context(id, config, scoped, metadata)));
            participants.add(new Participant(id, builtin, requested, scoped, metadata, contributor));
            requirements = new IndexRequirements(requirements.consumedAddresses() || requested.consumedAddresses(),
                    requirements.transactionSubjects() || requested.transactionSubjects(),
                    requirements.requiresFullUtxoStorage() || requested.requiresFullUtxoStorage());
        }
    }

    public void freeze(boolean utxoEnabled, boolean filtered) {
        synchronized (ownerLock) {
            requireReadable();
            validateStorage(utxoEnabled, filtered);
            if (!frozen) {
                participants.sort(Comparator.comparing((Participant p) -> !p.builtin)
                        .thenComparing(p -> p.builtin ? "" : p.id));
                frozen = true;
            }
        }
    }

    public boolean enabled() { return !participants.isEmpty(); }

    public void validateStorage(boolean utxoEnabled, boolean filtered) {
        if (enabled() && !utxoEnabled || requirements.requiresFullUtxoStorage() && filtered) {
            throw new IllegalArgumentException("UTxO contributors require enabled, unfiltered UTxO storage, including plugins");
        }
    }
    public IndexRequirements requirements() { return requirements; }

    public void requireMaintenanceAllowed(String operation) {
        if (enabled()) throw new IllegalStateException(operation + " is unsupported with UTxO contributors; use a fresh sync database");
    }

    public void requireAvailable(String id) {
        synchronized (ownerLock) {
            requireReadable();
            Participant participant = participants.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
            String reason = participant == null ? "Contributor not registered" : participant.metadata.unavailableReason(applied.get());
            if (reason != null) throw new IndexUnavailableException("Contributor " + id + ": " + reason);
        }
    }

    public void stageApply(WriteBatch batch, UtxoChanges changes) {
        requireFrozen();
        if (changes.point().blockNumber() <= changes.previous().blockNumber()) {
            throw new IndexLifecycleException("Non-forward UTxO transition requires rollback before replacement");
        }
        for (Participant participant : participants) {
            String failure = isolated(batch, participant, writer -> participant.contributor.stageApply(changes, writer));
            participant.metadata.applied(batch, changes.previous(), changes.point(), failure);
        }
    }

    public void stageRollback(WriteBatch batch, ChainPoint target) {
        requireFrozen();
        for (Participant participant : participants) {
            String failure = isolated(batch, participant, writer -> participant.contributor.stageRollback(target, writer));
            participant.metadata.rollback(batch, target, failure);
        }
    }

    public void stageGenesis(WriteBatch batch, String identity, List<GenesisUtxo> outputs) {
        requireFrozen();
        List<GenesisUtxo> immutable = List.copyOf(outputs);
        for (Participant participant : participants) {
            // Initialization must succeed as a whole; no partially initialized genesis.
            try (var writer = participant.storage.open(batch)) {
                participant.contributor.stageGenesis(identity, immutable, writer);
                participant.metadata.genesis(batch, immutable);
            }
        }
    }

    public void stagePruneUndo(WriteBatch batch, ChainPoint point) {
        requireFrozen();
        for (Participant participant : participants) {
            try (var writer = participant.storage.open(batch)) {
                participant.contributor.stagePruneUndo(point, writer);
                participant.metadata.prune(batch, point);
            }
        }
    }

    /** Producer materialization is not a new index event: it must match the already-indexed genesis. */
    public void validateGenesisMaterialization(List<GenesisUtxo> outputs) {
        requireFrozen();
        if (!enabled()) return;
        for (Participant participant : participants) participant.metadata.validateGenesisMaterialization(outputs);
    }

    public void reinitialize() {
        synchronized (ownerLock) {
            if (closed) throw new IndexLifecycleException("Contributor registry closed");
            readsPaused = false; // Owner has already rebound all handles and still holds its lock.
            try {
                for (Participant participant : participants) {
                    participant.metadata.read();
                    participant.contributor.reinitialize();
                }
            } catch (RuntimeException | Error failure) {
                readsPaused = true;
                throw failure;
            }
        }
    }

    public void pauseReadsForStorageReplacement() {
        synchronized (ownerLock) { readsPaused = true; }
    }

    public void requireReadable() {
        synchronized (ownerLock) {
            if (closed || readsPaused) throw new IndexUnavailableException("Contributor storage is closed or being replaced");
        }
    }

    private String isolated(WriteBatch batch, Participant participant, Consumer<IndexWriter> callback) {
        batch.setSavePoint();
        try (var writer = participant.storage.open(batch)) {
            try {
                callback.accept(writer);
                batch.popSavePoint();
                return null;
            } catch (IndexStorage.StorageFailure | IndexLifecycleException storageFailure) {
                throw storageFailure; // Never downgrade failed storage into optional-index failure.
            } catch (RuntimeException failure) {
                batch.rollbackToSavePoint();
                log.error("UTxO contributor {} failed; marking unavailable", participant.id, failure);
                return failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        } catch (RocksDBException failure) { throw new IndexStorage.StorageFailure(failure); }
    }

    private UtxoIndexContext context(String id, Map<String, String> config, IndexStorage scoped, IndexMetadata metadata) {
        Map<String, String> immutable = Map.copyOf(config);
        return new UtxoIndexContext() {
            @Override public String id() { return id; }
            @Override public String networkIdentity() { return network; }
            @Override public Map<String, String> configuration() { return immutable; }
            @Override public <T> T read(Function<ReadScope, T> query) {
                synchronized (ownerLock) {
                    requireReadable();
                    try (var reader = scoped.open(null)) {
                        return query.apply(new ReadScope() {
                            @Override public ChainPoint appliedPoint() { reader.requireActive(); return applied.get(); }
                            @Override public long generation() { reader.requireActive(); return generation.getAsLong(); }
                            @Override public boolean available() { reader.requireActive(); return metadata.available(applied.get()); }
                            @Override public byte[] getCommitted(String table, byte[] key) { return reader.getCommitted(table, key); }
                            @Override public IndexReader.Page scanCommitted(String table, byte[] prefix, String cursor, int limit) {
                                return reader.scanCommitted(table, prefix, cursor, limit);
                            }
                        });
                    }
                }
            }
        };
    }

    private void requireFrozen() {
        if (!frozen || closed || readsPaused || !Thread.holdsLock(ownerLock)) throw new IndexLifecycleException("Registry requires frozen registration, live storage and owner lock");
    }

    @Override public void close() {
        synchronized (ownerLock) {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            for (Participant participant : participants.reversed()) {
                try { participant.contributor.close(); }
                catch (RuntimeException | Error error) {
                    failure = LifecycleFailures.merge(failure, error);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure != null) throw (RuntimeException) failure;
        }
    }
}
