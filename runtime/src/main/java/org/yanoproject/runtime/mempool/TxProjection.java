package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.api.util.AddressKeyUtil;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.conway.tx.CclTransactions;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.runtime.chain.TransactionOutputProjector;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The UTxO-facing projection of a mempool transaction (the same projection {@code DefaultMemPool} indexes): its
 * id, the regular inputs it claims, every input it reads, and its outputs as query-model {@link Utxo}s.
 * Immutable by convention.
 *
 * @param txHash        lowercase hex transaction id
 * @param regularInputs spending inputs (normalized)
 * @param allInputs     spending, reference and collateral inputs (normalized)
 * @param outputs       outputs by outpoint, in index order
 * @param subjects      address and payment-credential keys of the outputs, by address
 */
public record TxProjection(String txHash, Set<Outpoint> regularInputs, Set<Outpoint> allInputs,
                           Map<Outpoint, Utxo> outputs, Map<String, SubjectKey> subjects) {

    /** Address hash and payment credential of an output address, for subject queries. */
    public record SubjectKey(byte[] address, byte[] payment) {
    }

    /**
     * @throws Exception when the bytes are not a decodable transaction
     */
    public static TxProjection of(byte[] txBytes) throws Exception {
        String txHash = TransactionUtil.getTxHash(txBytes).toLowerCase(Locale.ROOT);
        Transaction transaction = CclTransactions.deserialize(txBytes);
        if (transaction.getBody() == null) {
            throw new IllegalArgumentException("transaction body is null");
        }
        Set<Outpoint> regular = inputs(transaction.getBody().getInputs());
        Set<Outpoint> all = new LinkedHashSet<>(regular);
        all.addAll(inputs(transaction.getBody().getReferenceInputs()));
        all.addAll(inputs(transaction.getBody().getCollateral()));
        Map<Outpoint, Utxo> outputs = new LinkedHashMap<>();
        for (Utxo output : TransactionOutputProjector.projectOutputs(txHash, txBytes, transaction)) {
            outputs.put(output.outpoint(), output);
        }
        Map<String, SubjectKey> subjects = new HashMap<>();
        outputs.values().forEach(output -> subjects.computeIfAbsent(output.address(), address ->
                new SubjectKey(AddressKeyUtil.addrHash28(address), AddressKeyUtil.paymentCred28(address))));
        return new TxProjection(txHash, Collections.unmodifiableSet(regular), Collections.unmodifiableSet(all),
                Collections.unmodifiableMap(outputs), Map.copyOf(subjects));
    }

    private static Set<Outpoint> inputs(Collection<TransactionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<Outpoint> projected = new LinkedHashSet<>();
        for (TransactionInput input : inputs) {
            projected.add(Outpoints.of(input.getTransactionId(), input.getIndex()));
        }
        return projected;
    }
}
