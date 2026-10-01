package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A local UTxO set for the gate's addresses, so dependent transactions can be built while their parents are still
 * in the mempool (ADR-056 Phase 6b): QuickTx selects from it, and every transaction the gate expects to be admitted
 * is {@linkplain #apply(Transaction, String) applied} to it (inputs spent, outputs at tracked addresses added).
 */
public final class GateWallet implements UtxoSupplier {

    private final Set<String> addresses;
    private final LinkedHashMap<String, Utxo> utxos = new LinkedHashMap<>();

    public GateWallet(Set<String> addresses) {
        this.addresses = Set.copyOf(addresses);
    }

    public synchronized void add(Utxo utxo) {
        utxos.put(key(utxo.getTxHash(), utxo.getOutputIndex()), utxo);
    }

    /** Spends the transaction's inputs and adds its outputs at tracked addresses. */
    public synchronized void apply(Transaction tx, String txHash) {
        for (TransactionInput input : tx.getBody().getInputs()) {
            utxos.remove(key(input.getTransactionId(), input.getIndex()));
        }
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (addresses.contains(output.getAddress())) {
                add(new Utxo(txHash, i, output.getAddress(), List.of(Amount.lovelace(output.getValue().getCoin())),
                        null, null, null));
            }
        }
    }

    public synchronized Map<String, Utxo> snapshot() {
        return new LinkedHashMap<>(utxos);
    }

    public synchronized void restore(Map<String, Utxo> snapshot) {
        utxos.clear();
        utxos.putAll(snapshot);
    }

    public synchronized int size() {
        return utxos.size();
    }

    @Override
    public synchronized List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        List<Utxo> matching = new ArrayList<>();
        for (Utxo utxo : utxos.values()) {
            if (utxo.getAddress().equals(address)) {
                matching.add(utxo);
            }
        }
        int size = nrOfItems != null && nrOfItems > 0 ? nrOfItems : DEFAULT_NR_OF_ITEMS_TO_FETCH;
        int from = (page != null ? page : 0) * size;
        if (from >= matching.size()) {
            return List.of();
        }
        return List.copyOf(matching.subList(from, Math.min(matching.size(), from + size)));
    }

    @Override
    public synchronized Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        return Optional.ofNullable(utxos.get(key(txHash, outputIndex)));
    }

    private static String key(String txHash, int index) {
        return txHash + "#" + index;
    }
}
