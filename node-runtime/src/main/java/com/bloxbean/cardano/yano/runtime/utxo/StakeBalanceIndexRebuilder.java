package com.bloxbean.cardano.yano.runtime.utxo;

import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.db.UtxoCfNames;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds the current stake-credential UTXO balance aggregate from the
 * current unspent UTXO set. This is used by startup migrations before sync
 * starts.
 */
public final class StakeBalanceIndexRebuilder {
    private static final Logger log = LoggerFactory.getLogger(StakeBalanceIndexRebuilder.class);
    private static final int BATCH_SIZE = 10_000;
    private static final long PROGRESS_UTXO_INTERVAL = 1_000_000L;
    private static final long PROGRESS_LOG_MILLIS = 30_000L;

    public RebuildResult rebuild(RocksDbSupplier supplier, boolean force) {
        var ctx = supplier.rocks();
        RocksDB db = ctx.db();
        ColumnFamilyHandle cfUnspent = requireCf(ctx.handle(UtxoCfNames.UTXO_UNSPENT), UtxoCfNames.UTXO_UNSPENT);
        ColumnFamilyHandle cfStakeBalance = requireCf(ctx.handle(UtxoCfNames.UTXO_STAKE_BALANCE), UtxoCfNames.UTXO_STAKE_BALANCE);
        ColumnFamilyHandle cfMeta = requireCf(ctx.handle(UtxoCfNames.UTXO_META), UtxoCfNames.UTXO_META);

        try {
            if (!force && db.get(cfMeta, StakeBalanceIndexKeys.READY_MARKER) != null) {
                log.info("Stake balance index is already ready; use force=true to rebuild");
                return new RebuildResult(false, 0, 0, 0, 0, BigInteger.ZERO);
            }

            long start = System.currentTimeMillis();
            long cleared = clearStakeBalanceIndex(db, cfStakeBalance, cfMeta);
            AggregateResult aggregate = aggregateCurrentUnspent(db, cfUnspent);
            long written = writeBalances(db, cfStakeBalance, aggregate.balances());
            db.put(cfMeta, StakeBalanceIndexKeys.READY_MARKER, new byte[]{1});

            BigInteger total = aggregate.balances().values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Stake balance index rebuild complete: cleared_entries={}, scanned_utxos={}, skipped_utxos={}, credentials={}, written_entries={}, total_lovelace={}, elapsed_ms={}",
                    cleared, aggregate.scannedUtxos(), aggregate.skippedUtxos(), aggregate.balances().size(), written, total, elapsed);
            return new RebuildResult(true, elapsed, aggregate.scannedUtxos(), aggregate.skippedUtxos(),
                    aggregate.balances().size(), total);
        } catch (Exception e) {
            try {
                db.delete(cfMeta, StakeBalanceIndexKeys.READY_MARKER);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Stake balance index rebuild failed", e);
        }
    }

    public boolean isReady(RocksDbSupplier supplier) {
        var ctx = supplier.rocks();
        RocksDB db = ctx.db();
        ColumnFamilyHandle cfMeta = requireCf(ctx.handle(UtxoCfNames.UTXO_META), UtxoCfNames.UTXO_META);
        try {
            return db.get(cfMeta, StakeBalanceIndexKeys.READY_MARKER) != null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read stake balance index ready marker", e);
        }
    }

    public void clearReadyMarker(RocksDbSupplier supplier) {
        var ctx = supplier.rocks();
        RocksDB db = ctx.db();
        ColumnFamilyHandle cfMeta = requireCf(ctx.handle(UtxoCfNames.UTXO_META), UtxoCfNames.UTXO_META);
        try {
            db.delete(cfMeta, StakeBalanceIndexKeys.READY_MARKER);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear stake balance index ready marker", e);
        }
    }

    private static ColumnFamilyHandle requireCf(ColumnFamilyHandle cf, String name) {
        if (cf == null) throw new IllegalStateException("Missing RocksDB column family: " + name);
        return cf;
    }

    private static long clearStakeBalanceIndex(RocksDB db,
                                               ColumnFamilyHandle cfStakeBalance,
                                               ColumnFamilyHandle cfMeta) throws Exception {
        log.info("Stake balance index rebuild: clearing existing aggregate");
        long cleared = 0;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfStakeBalance)) {
            batch.delete(cfMeta, StakeBalanceIndexKeys.READY_MARKER);
            int pending = 1;
            it.seekToFirst();
            while (it.isValid()) {
                batch.delete(cfStakeBalance, it.key());
                cleared++;
                pending++;
                if (pending >= BATCH_SIZE) {
                    db.write(wo, batch);
                    batch.clear();
                    pending = 0;
                }
                it.next();
            }
            if (pending > 0) db.write(wo, batch);
        }
        log.info("Stake balance index rebuild: cleared {} aggregate entries", cleared);
        return cleared;
    }

    private static AggregateResult aggregateCurrentUnspent(RocksDB db,
                                                           ColumnFamilyHandle cfUnspent) {
        Map<StakeCredentialKey, BigInteger> balances = new HashMap<>();
        long scanned = 0;
        long skipped = 0;
        long nextLogAt = System.currentTimeMillis() + PROGRESS_LOG_MILLIS;

        try (RocksIterator it = db.newIterator(cfUnspent)) {
            it.seekToFirst();
            while (it.isValid()) {
                scanned++;
                try {
                    var utxo = UtxoCborCodec.decodeUtxoRecord(it.value());
                    StakeCredentialKey key = stakeCredentialKey(utxo.address);
                    if (key == null || utxo.lovelace == null || utxo.lovelace.signum() <= 0) {
                        skipped++;
                    } else {
                        balances.merge(key, utxo.lovelace, BigInteger::add);
                    }
                } catch (Exception e) {
                    skipped++;
                }
                long now = System.currentTimeMillis();
                if (scanned % PROGRESS_UTXO_INTERVAL == 0 || now >= nextLogAt) {
                    log.info("Stake balance index scan progress: scanned_utxos={}, skipped_utxos={}, credentials={}",
                            scanned, skipped, balances.size());
                    nextLogAt = now + PROGRESS_LOG_MILLIS;
                }
                it.next();
            }
        }

        log.info("Stake balance index scan complete: scanned_utxos={}, skipped_utxos={}, credentials={}",
                scanned, skipped, balances.size());
        return new AggregateResult(balances, scanned, skipped);
    }

    private static long writeBalances(RocksDB db,
                                      ColumnFamilyHandle cfStakeBalance,
                                      Map<StakeCredentialKey, BigInteger> balances) throws Exception {
        long written = 0;
        long nextLogAt = System.currentTimeMillis() + PROGRESS_LOG_MILLIS;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            int pending = 0;
            for (var entry : balances.entrySet()) {
                if (entry.getValue().signum() <= 0) continue;
                batch.put(cfStakeBalance, stakeBalanceKey(entry.getKey()), encodeStakeBalance(entry.getValue()));
                written++;
                pending++;
                if (pending >= BATCH_SIZE) {
                    db.write(wo, batch);
                    batch.clear();
                    pending = 0;
                }
                long now = System.currentTimeMillis();
                if (written % PROGRESS_UTXO_INTERVAL == 0 || now >= nextLogAt) {
                    log.info("Stake balance index write progress: written_entries={}", written);
                    nextLogAt = now + PROGRESS_LOG_MILLIS;
                }
            }
            if (pending > 0) db.write(wo, batch);
        }
        log.info("Stake balance index rebuild: wrote {} aggregate entries", written);
        return written;
    }

    private static StakeCredentialKey stakeCredentialKey(String addressStr) {
        try {
            Address address = new Address(addressStr);
            if (address.getAddressType() == AddressType.Ptr) return null;

            byte[] stakeHash = address.getDelegationCredentialHash().orElse(null);
            if (stakeHash == null || stakeHash.length != 28) return null;

            return new StakeCredentialKey(stakeCredentialType(address), HexUtil.encodeHexString(stakeHash));
        } catch (Exception e) {
            return null;
        }
    }

    private static int stakeCredentialType(Address address) {
        int typeNibble = ((address.getBytes()[0] & 0xFF) >> 4) & 0x0F;
        return switch (typeNibble) {
            case 0, 1 -> 0;
            case 2, 3 -> 1;
            default -> 0;
        };
    }

    private static byte[] stakeBalanceKey(StakeCredentialKey key) {
        byte[] hash = HexUtil.decodeHexString(key.credHash());
        ByteBuffer bb = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) key.credType());
        bb.put(hash);
        return bb.array();
    }

    private static byte[] encodeStakeBalance(BigInteger balance) {
        return CborSerializationUtil.serialize(new UnsignedInteger(balance), true);
    }

    private record StakeCredentialKey(int credType, String credHash) {}

    private record AggregateResult(Map<StakeCredentialKey, BigInteger> balances,
                                   long scannedUtxos,
                                   long skippedUtxos) {}

    public record RebuildResult(boolean rebuilt,
                                long elapsedMillis,
                                long scannedUtxos,
                                long skippedUtxos,
                                long credentialCount,
                                BigInteger totalLovelace) {}
}
