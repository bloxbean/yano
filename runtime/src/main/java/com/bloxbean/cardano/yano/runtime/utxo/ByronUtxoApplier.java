package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.api.plugin.UtxoFilterContext;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Stages native Byron UTXO mutations into a store-owned batch. */
final class ByronUtxoApplier {
    private static final long WARNING_INTERVAL_MILLIS = 30_000L;

    private final RocksDB db;
    private final ColumnFamilyHandle cfUnspent;
    private final ColumnFamilyHandle cfSpent;
    private final ColumnFamilyHandle cfAddr;
    private final boolean indexAddressHash;
    private final Supplier<StorageFilterChain> filterChainSupplier;
    private final Logger log;
    private final LongAdder unresolvedInputs = new LongAdder();
    private final LongAdder filteredStoreUnresolvedInputs = new LongAdder();
    private final AtomicLong lastWarningMillis = new AtomicLong();

    ByronUtxoApplier(RocksDB db,
                     ColumnFamilyHandle cfUnspent,
                     ColumnFamilyHandle cfSpent,
                     ColumnFamilyHandle cfAddr,
                     boolean indexAddressHash,
                     Supplier<StorageFilterChain> filterChainSupplier,
                     Logger log) {
        this.db = db;
        this.cfUnspent = cfUnspent;
        this.cfSpent = cfSpent;
        this.cfAddr = cfAddr;
        this.indexAddressHash = indexAddressHash;
        this.filterChainSupplier = filterChainSupplier;
        this.log = log;
    }

    ApplyResult stageBlock(ByronMainBlockAppliedEvent event, WriteBatch batch) throws RocksDBException {
        List<UtxoDeltaCodec.OutRef> created = new ArrayList<>();
        List<UtxoDeltaCodec.OutRef> spent = new ArrayList<>();
        Map<String, byte[]> intraBlockOutputs = new HashMap<>();
        Set<String> consumedInputs = new HashSet<>();
        ByronMainBlock block = event.block();
        var payloads = block.getBody() != null ? block.getBody().getTxPayload() : null;
        if (payloads == null) return new ApplyResult(created, spent, 0);

        int filteredOutputs = 0;
        for (var payload : payloads) {
            ByronTx tx = payload != null ? payload.getTransaction() : null;
            if (tx == null) continue;
            requireHash(tx.getTxHash(), "Byron transaction hash");

            if (tx.getInputs() != null) {
                for (ByronTxIn input : tx.getInputs()) {
                    stageInput(event, input, batch, intraBlockOutputs, consumedInputs, spent);
                }
            }

            if (tx.getOutputs() != null) {
                for (int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    ByronTxOut output = tx.getOutputs().get(outputIndex);
                    if (output == null || output.getAddress() == null
                            || output.getAddress().getBase58Raw() == null || output.getAmount() == null) {
                        throw new IllegalStateException("Malformed Byron output in transaction " + tx.getTxHash());
                    }
                    String address = output.getAddress().getBase58Raw();
                    BigInteger lovelace = output.getAmount();
                    StorageFilterChain filters = filterChainSupplier.get();
                    if (filters != null && !filters.isEmpty()) {
                        UtxoFilterContext context = new UtxoFilterContext(
                                address, null, lovelace.longValueExact(), List.of(),
                                event.slot(), event.blockNumber(), tx.getTxHash(), outputIndex);
                        if (!filters.acceptByronUtxoOutput(context, block, tx)) {
                            filteredOutputs++;
                            continue;
                        }
                    }

                    byte[] value = encodeOutput(address, lovelace, event.slot(),
                            event.blockNumber(), event.blockHash());
                    stageOutput(batch, tx.getTxHash(), outputIndex, address,
                            event.slot(), value, created);
                    intraBlockOutputs.put(outpointId(tx.getTxHash(), outputIndex), value);
                }
            }
        }
        return new ApplyResult(created, spent, filteredOutputs);
    }

    GenesisResult stageGenesisOutputs(Map<String, BigInteger> nonAvvmBalances,
                                      Map<String, BigInteger> avvmBalances,
                                      long slot,
                                      long blockNumber,
                                      String blockHash,
                                      WriteBatch batch) throws RocksDBException {
        List<byte[]> all = new ArrayList<>();
        List<byte[]> avvm = new ArrayList<>();
        stageGenesisDistribution(nonAvvmBalances, false, slot, blockNumber, blockHash, batch, all, avvm);
        stageGenesisDistribution(avvmBalances, true, slot, blockNumber, blockHash, batch, all, avvm);
        return new GenesisResult(List.copyOf(all), List.copyOf(avvm));
    }

    long unresolvedInputCount() {
        return unresolvedInputs.sum();
    }

    long filteredStoreUnresolvedInputCount() {
        return filteredStoreUnresolvedInputs.sum();
    }

    private void stageInput(ByronMainBlockAppliedEvent event,
                            ByronTxIn input,
                            WriteBatch batch,
                            Map<String, byte[]> intraBlockOutputs,
                            Set<String> consumedInputs,
                            List<UtxoDeltaCodec.OutRef> spent) throws RocksDBException {
        if (input == null) throw new IllegalStateException("Null Byron transaction input");
        requireHash(input.getTxId(), "Byron input transaction id");
        byte[] key = UtxoKeyUtil.outpointKey(input.getTxId(), input.getIndex());
        String id = outpointId(input.getTxId(), input.getIndex());
        if (!consumedInputs.add(id)) {
            throw new IllegalStateException("Duplicate Byron input " + id
                    + " at block " + event.blockNumber());
        }
        byte[] previous = intraBlockOutputs.remove(id);
        if (previous == null) previous = db.get(cfUnspent, key);

        if (previous == null) {
            StorageFilterChain filters = filterChainSupplier.get();
            boolean selective = filters != null && !filters.isEmpty();
            if (selective) filteredStoreUnresolvedInputs.increment();
            else unresolvedInputs.increment();
            warnUnresolved(event, input, selective);
            if (!selective) {
                throw new IllegalStateException("Unresolved Byron input " + id
                        + " at block " + event.blockNumber() + " (slot " + event.slot() + ")");
            }
            return;
        }

        batch.put(cfSpent, key, UtxoCborCodec.wrapSpent(previous, event.slot()));
        batch.delete(cfUnspent, key);
        UtxoCborCodec.StoredUtxo stored = UtxoCborCodec.decodeUtxoRecord(previous);
        if (indexAddressHash) {
            byte[] addressIndex = UtxoKeyUtil.addressIndexKey(
                    UtxoKeyUtil.addrHash28(stored.address), stored.slot,
                    input.getTxId(), input.getIndex());
            batch.delete(cfAddr, addressIndex);
        }
        spent.add(new UtxoDeltaCodec.OutRef(input.getTxId(), input.getIndex()));
    }

    private void stageGenesisDistribution(Map<String, BigInteger> balances,
                                          boolean redeem,
                                          long slot,
                                          long blockNumber,
                                          String blockHash,
                                          WriteBatch batch,
                                          List<byte[]> all,
                                          List<byte[]> avvm) throws RocksDBException {
        if (balances == null) return;
        for (var entry : balances.entrySet()) {
            var output = GenesisUtxos.byron(
                    entry.getKey(), entry.getValue(), blockNumber, slot, blockHash);
            byte[] value = encodeOutput(output.address(), output.amount(), slot, blockNumber, blockHash);
            byte[] key = stageOutput(batch, output.txHash(), output.outputIndex(), output.address(),
                    slot, value, null);
            all.add(key);
            if (redeem) avvm.add(key);
        }
    }

    private byte[] stageOutput(WriteBatch batch,
                               String txHash,
                               int outputIndex,
                               String address,
                               long slot,
                               byte[] value,
                               List<UtxoDeltaCodec.OutRef> created) throws RocksDBException {
        byte[] key = UtxoKeyUtil.outpointKey(txHash, outputIndex);
        batch.put(cfUnspent, key, value);
        if (indexAddressHash) {
            batch.put(cfAddr, UtxoKeyUtil.addressIndexKey(
                    UtxoKeyUtil.addrHash28(address), slot, txHash, outputIndex), new byte[0]);
        }
        if (created != null) created.add(new UtxoDeltaCodec.OutRef(txHash, outputIndex));
        return key;
    }

    private static byte[] encodeOutput(String address, BigInteger lovelace,
                                       long slot, long blockNumber, String blockHash) {
        return UtxoCborCodec.encodeUtxoRecord(address, lovelace, null,
                null, null, null, false, slot, blockNumber, blockHash);
    }

    private void warnUnresolved(ByronMainBlockAppliedEvent event, ByronTxIn input, boolean selective) {
        long now = System.currentTimeMillis();
        long previous = lastWarningMillis.get();
        if (now - previous >= WARNING_INTERVAL_MILLIS && lastWarningMillis.compareAndSet(previous, now)) {
            log.warn("Unresolved Byron input {}#{} at block {} slot {} hash {} (selectiveStore={})",
                    input.getTxId(), input.getIndex(), event.blockNumber(), event.slot(),
                    event.blockHash(), selective);
        }
    }

    private static String outpointId(String txHash, int index) {
        return txHash + ':' + index;
    }

    private static void requireHash(String hash, String description) {
        if (hash == null || hash.isBlank()) throw new IllegalStateException(description + " is required");
    }

    record ApplyResult(List<UtxoDeltaCodec.OutRef> created,
                       List<UtxoDeltaCodec.OutRef> spent,
                       int filteredOutputs) {}

    record GenesisResult(List<byte[]> allOutpointKeys, List<byte[]> avvmOutpointKeys) {}
}
