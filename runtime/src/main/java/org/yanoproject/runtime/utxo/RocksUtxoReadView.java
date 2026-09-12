package org.yanoproject.runtime.utxo;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.UtxoReadView;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Bounded snapshot iterator. All native access shares the store's lifecycle monitor. */
final class RocksUtxoReadView implements UtxoReadView {
    private final Object guard;
    private final RocksDB db;
    private final ColumnFamilyHandle cfAddr;
    private final ColumnFamilyHandle cfUnspent;
    private final ColumnFamilyHandle cfMeta;
    private final byte[] appliedHashKey;
    private final byte[] snapshotHash;
    private final BiFunction<byte[], Outpoint, Utxo> decoder;
    private final Consumer<RocksUtxoReadView> onClose;
    private final byte[] prefix;
    private final boolean descending;
    private final Snapshot snapshot;
    private final ReadOptions options;
    private final RocksIterator iterator;
    private final long started = System.nanoTime();
    private int scanned;
    private boolean closed;

    RocksUtxoReadView(Object guard, RocksDB db, ColumnFamilyHandle cfAddr,
                      ColumnFamilyHandle cfUnspent, ColumnFamilyHandle cfMeta, byte[] appliedHashKey,
                      BiFunction<byte[], Outpoint, Utxo> decoder,
                      Consumer<RocksUtxoReadView> onClose, byte[] prefix, boolean descending) {
        this.guard = guard;
        this.db = db;
        this.cfAddr = cfAddr;
        this.cfUnspent = cfUnspent;
        this.cfMeta = cfMeta;
        this.appliedHashKey = appliedHashKey;
        this.decoder = decoder;
        this.onClose = onClose;
        this.prefix = prefix;
        this.descending = descending;
        snapshot = db.getSnapshot();
        ReadOptions openedOptions = null;
        RocksIterator openedIterator = null;
        try {
            openedOptions = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            snapshotHash = db.get(cfMeta, openedOptions, appliedHashKey);
            openedIterator = db.newIterator(cfAddr, openedOptions);
            if (descending) {
                byte[] upper = Arrays.copyOf(prefix, 70);
                Arrays.fill(upper, 28, 70, (byte) 0xff);
                openedIterator.seekForPrev(upper);
            } else openedIterator.seek(prefix);
            options = openedOptions;
            iterator = openedIterator;
        } catch (Exception e) {
            if (openedIterator != null) openedIterator.close();
            if (openedOptions != null) openedOptions.close();
            db.releaseSnapshot(snapshot);
            throw new IllegalStateException("Cannot open UTxO read view", e);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("UTxO read view closed");
        if (System.nanoTime() - started > MAX_QUERY_NANOS) {
            close();
            throw new IllegalStateException("UTxO query time limit exceeded");
        }
    }

    @Override
    public Optional<Utxo> next() {
        synchronized (guard) {
            checkOpen();
            try {
                while (iterator.isValid()) {
                    byte[] key = iterator.key();
                    if (!UtxoKeyUtil.prefixMatches(key, prefix, 28)) break;
                    if (key.length != 70) throw new IllegalStateException("Invalid UTxO index key length");
                    if (++scanned > MAX_SCANNED_OUTPUTS) throw new IllegalStateException("UTxO scan limit exceeded");
                    checkOpen();
                    String hash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 36, 68));
                    int index = ByteBuffer.wrap(key, 68, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
                    byte[] value = db.get(cfUnspent, options, UtxoKeyUtil.outpointKey(hash, index));
                    if (descending) iterator.prev(); else iterator.next();
                    if (value != null) return Optional.of(decoder.apply(value, new Outpoint(hash, index)));
                }
                iterator.status();
                return Optional.empty();
            } catch (Exception e) {
                close();
                throw new IllegalStateException("Consistent UTxO listing failed", e);
            }
        }
    }

    @Override
    public Optional<Utxo> getUtxo(Outpoint outpoint) {
        synchronized (guard) {
            checkOpen();
            try {
                byte[] value = db.get(cfUnspent, options,
                        UtxoKeyUtil.outpointKey(outpoint.txHash(), outpoint.index()));
                return value == null ? Optional.empty() : Optional.of(decoder.apply(value, outpoint));
            } catch (Exception e) {
                close();
                throw new IllegalStateException("Consistent UTxO point read failed", e);
            }
        }
    }

    @Override
    public void checkCurrent() {
        synchronized (guard) {
            checkOpen();
            try {
                if (!Arrays.equals(snapshotHash, db.get(cfMeta, appliedHashKey))) {
                    throw new IllegalStateException("Canonical chain changed; retry UTxO query");
                }
            } catch (Exception e) {
                close();
                throw new IllegalStateException("UTxO read view is no longer current", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (guard) {
            if (closed) return;
            closed = true;
            iterator.close();
            options.close();
            db.releaseSnapshot(snapshot);
            onClose.accept(this);
        }
    }
}
