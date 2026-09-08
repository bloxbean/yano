package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.utxo.index.IndexRequirements;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoChanges;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.utxo.index.TransactionSubjects;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Built once per apply; snapshots effective effects without exposing mutable Yaci models. */
final class UtxoChangeBuilder {
    private final boolean enabled;
    private final IndexRequirements requirements;
    private final List<UtxoChanges.Transaction> transactions;
    private final List<UtxoChanges.Input> protocolConsumed;

    UtxoChangeBuilder(boolean enabled, IndexRequirements requirements) {
        this.enabled = enabled;
        this.requirements = requirements;
        transactions = enabled ? new ArrayList<>() : null;
        protocolConsumed = enabled ? new ArrayList<>() : null;
    }

    void transaction(TransactionBody tx, boolean valid, ConsumedAddressCapture addresses) {
        if (!enabled) return;
        List<UtxoChanges.Input> inputs = new ArrayList<>();
        var effectiveInputs = valid ? tx.getInputs() : tx.getCollateralInputs();
        if (effectiveInputs != null) effectiveInputs.forEach(in -> inputs.add(input(in.getTransactionId(), in.getIndex(), addresses)));
        List<UtxoChanges.Output> outputs = new ArrayList<>();
        if (valid && tx.getOutputs() != null) {
            for (int i = 0; i < tx.getOutputs().size(); i++) outputs.add(output(tx.getTxHash(), i, tx.getOutputs().get(i), false));
        } else if (!valid && tx.getCollateralReturn() != null) {
            outputs.add(output(tx.getTxHash(), tx.getOutputs() == null ? 0 : tx.getOutputs().size(), tx.getCollateralReturn(), true));
        }
        var subjects = valid && requirements.transactionSubjects() ? TransactionSubjects.extract(tx)
                : new TransactionSubjects.Result(List.of(), null);
        transactions.add(new UtxoChanges.Transaction(tx.getTxHash(), valid, inputs, outputs,
                tx.getCbor(), subjects.subjects(), subjects.error()));
    }

    void transaction(ByronTx tx, ConsumedAddressCapture addresses) {
        if (!enabled) return;
        List<UtxoChanges.Input> inputs = new ArrayList<>();
        if (tx.getInputs() != null) tx.getInputs().forEach(in -> inputs.add(input(in.getTxId(), in.getIndex(), addresses)));
        List<UtxoChanges.Output> outputs = new ArrayList<>();
        if (tx.getOutputs() != null) {
            for (int i = 0; i < tx.getOutputs().size(); i++) {
                var out = tx.getOutputs().get(i);
                outputs.add(new UtxoChanges.Output(new Outpoint(tx.getTxHash(), i), out.getAddress().getBase58Raw(), out.getAmount(), false));
            }
        }
        transactions.add(new UtxoChanges.Transaction(tx.getTxHash(), true, inputs, outputs, null, List.of(), null));
    }

    UtxoChanges build(ChainPoint previous, ChainPoint point, String era) {
        return new UtxoChanges(previous, point, era, transactions, protocolConsumed);
    }

    void protocolConsumed(String hash, int index, String address) {
        if (!enabled) return;
        protocolConsumed.add(new UtxoChanges.Input(new Outpoint(hash, index), requirements.consumedAddresses()
                ? UtxoChanges.Resolution.RESOLVED : UtxoChanges.Resolution.NOT_REQUESTED,
                requirements.consumedAddresses() ? address : null));
    }

    private UtxoChanges.Input input(String hash, int index, ConsumedAddressCapture addresses) {
        String address = requirements.consumedAddresses() ? addresses.view().addressOf(hash, index) : null;
        var resolution = !requirements.consumedAddresses() ? UtxoChanges.Resolution.NOT_REQUESTED
                : address == null ? UtxoChanges.Resolution.UNRESOLVED : UtxoChanges.Resolution.RESOLVED;
        return new UtxoChanges.Input(new Outpoint(hash, index), resolution, address);
    }

    private static UtxoChanges.Output output(String hash, int index, TransactionOutput output, boolean collateral) {
        BigInteger lovelace = BigInteger.ZERO;
        if (output.getAmounts() != null) {
            for (var amount : output.getAmounts()) if ("lovelace".equals(amount.getUnit())) lovelace = amount.getQuantity();
        }
        return new UtxoChanges.Output(new Outpoint(hash, index), output.getAddress(), lovelace, collateral);
    }
}
