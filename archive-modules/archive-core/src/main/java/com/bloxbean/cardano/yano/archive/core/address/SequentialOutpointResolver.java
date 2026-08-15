package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistoryStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Private sequential resolver; genesis outputs must be seeded before replay. */
public final class SequentialOutpointResolver {
    private final byte[] prefix;
    private final byte[] seeded;
    private final HotHistoryStore store;
    private boolean genesisSeeded;

    public SequentialOutpointResolver(HotHistoryStore store) {
        this(store, "backfill");
    }

    public SequentialOutpointResolver(HotHistoryStore store, String namespace) {
        this.store = store;
        if (namespace == null || !namespace.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("resolver namespace");
        this.prefix = ("resolver/" + namespace + "/").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.seeded = ("resolver/" + namespace + "/seeded").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.genesisSeeded = store.get(ArchiveDatasetId.ADDRESS_TRANSACTION, seeded).isPresent();
    }

    public void seedGenesis(Iterable<Entry> outputs) {
        if (genesisSeeded) return;
        seedEntries(outputs, true);
    }

    public boolean isSeeded() {
        return genesisSeeded;
    }

    public java.util.OptionalLong seedBaseBlock() {
        byte[] marker = store.get(ArchiveDatasetId.ADDRESS_TRANSACTION, seeded).orElse(null);
        return marker != null && marker.length == Long.BYTES
                ? java.util.OptionalLong.of(ByteBuffer.wrap(marker).getLong())
                : java.util.OptionalLong.empty();
    }

    public void seedEntries(Iterable<Entry> outputs, boolean complete) {
        java.util.List<com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation> mutations = new java.util.ArrayList<>(10_001);
        for (Entry entry : outputs) {
            mutations.add(putMutation(entry.outpoint(), entry.output()));
            if (mutations.size() == 10_000) {
                store.seed(ArchiveDatasetId.ADDRESS_TRANSACTION, mutations);
                mutations.clear();
            }
        }
        if (!mutations.isEmpty()) store.seed(ArchiveDatasetId.ADDRESS_TRANSACTION, mutations);
        if (complete) completeSeed(-1);
    }

    public void completeSeed(long baseBlock) {
        store.seed(ArchiveDatasetId.ADDRESS_TRANSACTION, java.util.List.of(
                new com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation(
                        seeded, ByteBuffer.allocate(Long.BYTES).putLong(baseBlock).array())));
        genesisSeeded = true;
    }

    public Optional<ResolvedOutput> resolve(Outpoint outpoint) {
        if (!genesisSeeded) throw new ArchiveStoreException("outpoint resolver is not genesis-seeded");
        return store.get(ArchiveDatasetId.ADDRESS_TRANSACTION, key(outpoint)).map(this::decode);
    }

    public void put(Outpoint outpoint, ResolvedOutput output) {
        store.seed(ArchiveDatasetId.ADDRESS_TRANSACTION, java.util.List.of(putMutation(outpoint, output)));
    }

    public com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation putMutation(
            Outpoint outpoint, ResolvedOutput output) {
        return new com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation(key(outpoint), encode(output));
    }

    public com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation deleteMutation(Outpoint outpoint) {
        return new com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation(key(outpoint), null);
    }

    public byte[] encodeOutput(ResolvedOutput output) { return encode(output); }
    public byte[] logicalKey(Outpoint outpoint) { return key(outpoint); }

    private byte[] key(Outpoint outpoint) {
        byte[] hash = outpoint.txHash();
        ByteBuffer key = ByteBuffer.allocate(prefix.length + hash.length + Integer.BYTES);
        return key.put(prefix).put(hash).putInt(outpoint.outputIndex()).array();
    }
    private byte[] encode(ResolvedOutput output) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            write(out, output.addressKey()); write(out, output.paymentCredential()); write(out, output.stakeCredential());
            return bytes.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private ResolvedOutput decode(byte[] value) {
        try (var in = new DataInputStream(new ByteArrayInputStream(value))) {
            return new ResolvedOutput(read(in), read(in), read(in));
        } catch (Exception e) { throw new ArchiveStoreException("invalid resolver output", e); }
    }
    private void write(DataOutputStream out, byte[] value) throws java.io.IOException { out.writeInt(value == null ? -1 : value.length); if (value != null) out.write(value); }
    private byte[] read(DataInputStream in) throws java.io.IOException { int n = in.readInt(); return n < 0 ? null : in.readNBytes(n); }
    public record Entry(Outpoint outpoint, ResolvedOutput output) { }
}
