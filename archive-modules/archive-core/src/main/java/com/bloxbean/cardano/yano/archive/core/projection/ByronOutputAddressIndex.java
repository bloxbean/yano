package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The archive-owned outpoint resolver ADR-039 requires for Byron address participation.
 *
 * <p>Shelley+ consumed addresses are captured during apply, from the output the UTXO store is
 * deleting at that moment. Byron has no such moment. {@code BlockAppliedEvent.block()} is
 * deliberately {@code null} for Byron, the live UTXO path returns on that sentinel, and so an
 * output created by a Byron transaction is written to no live column family at all. When a
 * later block spends it — a Byron block, or a Shelley+ block spending a Byron-era output years
 * afterwards — the capture map has nothing to offer and the address-transaction section cannot
 * be built. This index is what answers instead.
 *
 * <p><strong>Append-only, deliberately.</strong> An outpoint's address is immutable: the
 * transaction hash commits to the outputs, so {@code (txHash, index) -> address} is a fact that
 * cannot change and can never need updating. Nothing is deleted when an output is spent, and
 * nothing is rolled back when a block is: entries for a block that left the chain are
 * unreachable rather than wrong, and a re-applied block rewrites them identically. Deleting on
 * spend would buy disk at the price of a rollback that unspends an output whose address had
 * already been discarded — which is a crash loop, not a smaller database.
 *
 * <p>Only Byron-era outputs are recorded. Shelley+ outputs are in {@code cfUnspent} and their
 * addresses are captured during apply, so writing them here would duplicate the UTXO set for
 * nothing.
 */
public final class ByronOutputAddressIndex {

    /**
     * Marker recording that the Byron genesis distribution has been seeded.
     *
     * <p>Six bytes, so it cannot collide with an outpoint key, which is always 36.
     */
    private static final byte[] SEEDED = "seeded".getBytes(StandardCharsets.UTF_8);

    private final RocksDB db;
    private final ColumnFamilyHandle cf;

    ByronOutputAddressIndex(RocksDB db, ColumnFamilyHandle cf) {
        this.db = Objects.requireNonNull(db, "db");
        this.cf = Objects.requireNonNull(cf, "cf");
    }

    /** The address of an output recorded here, or {@code null} when this index never saw it. */
    public String addressOf(String txHash, int outputIndex) {
        if (txHash == null) return null;
        byte[] value = get(outpointKey(txHash, outputIndex));
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * Stage one output into the caller's batch.
     *
     * <p>Staged rather than written directly so the entry becomes durable with the projection
     * section derived from the same block, never before or after it.
     */
    public void put(ProjectionStagingWriter writer, String txHash, int outputIndex, String address) {
        if (txHash == null || address == null) return;
        writer.put(ProjectionCfNames.PROJ_BYRON_UTXO, outpointKey(txHash, outputIndex),
                address.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether the Byron genesis distribution has already been seeded into this index. */
    public boolean genesisSeeded() {
        return get(SEEDED) != null;
    }

    /** Stage the seed marker, in the same batch as the entries it accounts for. */
    public void markGenesisSeeded(ProjectionStagingWriter writer) {
        writer.put(ProjectionCfNames.PROJ_BYRON_UTXO, SEEDED, new byte[]{1});
    }

    private byte[] get(byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to read the Byron output address index", e);
        }
    }

    private static byte[] outpointKey(String txHash, int outputIndex) {
        byte[] hash = HexUtil.decodeHexString(txHash);
        return ByteBuffer.allocate(hash.length + 4).order(ByteOrder.BIG_ENDIAN)
                .put(hash).putInt(outputIndex).array();
    }
}
