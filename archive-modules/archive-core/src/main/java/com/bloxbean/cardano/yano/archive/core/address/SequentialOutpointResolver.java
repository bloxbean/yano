package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

/** Private sequential resolver; genesis outputs must be seeded before replay. */
public final class SequentialOutpointResolver {
    private static final byte[] PREFIX = "resolver/".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final RocksDbHotHistoryStore store;
    private boolean genesisSeeded;

    public SequentialOutpointResolver(RocksDbHotHistoryStore store) { this.store = store; }

    public void seedGenesis(Iterable<Entry> outputs) {
        for (Entry entry : outputs) put(entry.outpoint(), entry.output());
        genesisSeeded = true;
    }

    public Optional<ResolvedOutput> resolve(Outpoint outpoint) {
        if (!genesisSeeded) throw new ArchiveStoreException("outpoint resolver is not genesis-seeded");
        return store.get(ArchiveDatasetId.ADDRESS_TRANSACTION, key(outpoint)).map(this::decode);
    }

    public void put(Outpoint outpoint, ResolvedOutput output) {
        // Resolver mutations are normally batched with the live block via RocksDbHotHistoryStore.applyBlock.
        store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION,
                new com.bloxbean.cardano.yano.archive.core.hot.HotBlockCheckpoint(0, 0, new byte[] {1}, new byte[0]),
                java.util.List.of(new com.bloxbean.cardano.yano.archive.core.hot.HotHistoryMutation(key(outpoint), encode(output))),
                new com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress(ArchiveDatasetId.ADDRESS_TRANSACTION,
                        com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack.LIVE, 0, 0, new byte[] {1}, 0));
    }

    private byte[] key(Outpoint outpoint) {
        byte[] hash = outpoint.txHash();
        ByteBuffer key = ByteBuffer.allocate(PREFIX.length + hash.length + Integer.BYTES);
        return key.put(PREFIX).put(hash).putInt(outpoint.outputIndex()).array();
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
