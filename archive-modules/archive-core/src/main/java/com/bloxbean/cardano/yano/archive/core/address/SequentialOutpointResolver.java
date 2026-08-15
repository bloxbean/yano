package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryOperation;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Private sequential resolver; genesis outputs must be seeded before replay. */
public final class SequentialOutpointResolver {
    private final HotHistoryStore store;
    private final String namespace;
    private boolean genesisSeeded;

    public SequentialOutpointResolver(HotHistoryStore store) {
        this(store, "backfill");
    }

    public SequentialOutpointResolver(HotHistoryStore store, String namespace) {
        this.store = store;
        if (namespace == null || !namespace.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("resolver namespace");
        this.namespace = namespace;
        this.genesisSeeded = store.resolverSeeded(namespace);
    }

    public void seedGenesis(Iterable<Entry> outputs) {
        if (genesisSeeded) return;
        seedEntries(outputs, true);
    }

    public boolean isSeeded() {
        return genesisSeeded;
    }

    public java.util.OptionalLong seedBaseBlock() {
        return store.resolverBaseBlock(namespace);
    }

    public void seedEntries(Iterable<Entry> outputs, boolean complete) {
        store.seedResolver(namespace, outputs, complete, complete ? -1 : Long.MIN_VALUE);
        if (complete) genesisSeeded = true;
    }

    public void completeSeed(long baseBlock) {
        store.seedResolver(namespace, java.util.List.of(), true, baseBlock);
        genesisSeeded = true;
    }

    public Optional<ResolvedOutput> resolve(Outpoint outpoint) {
        if (!genesisSeeded) throw new ArchiveStoreException("outpoint resolver is not genesis-seeded");
        return store.resolveOutput(namespace, outpoint);
    }

    public void put(Outpoint outpoint, ResolvedOutput output) {
        store.seedResolver(namespace, java.util.List.of(new Entry(outpoint, output)), false, Long.MIN_VALUE);
    }

    public HotHistoryOperation.OutputCreated putOperation(
            Outpoint outpoint, ResolvedOutput output) {
        return new HotHistoryOperation.OutputCreated(namespace, outpoint, output);
    }

    public HotHistoryOperation.OutputConsumed consumeOperation(Outpoint outpoint, byte[] spendingTxHash,
                                                               String inputRole) {
        return new HotHistoryOperation.OutputConsumed(namespace, outpoint, spendingTxHash, inputRole);
    }
    public record Entry(Outpoint outpoint, ResolvedOutput output) { }
}
