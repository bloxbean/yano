package org.yanoproject.ledger.conformance.engines;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The spending, reference and collateral inputs of a transaction resolved through a case's view, in the shapes
 * the legacy validators take: CCL {@link Utxo}s (what the runtime hands {@code TransactionValidator}), outputs by
 * input (the copied Java rules' {@code UtxoSlice}) and a script supplier for reference scripts (what
 * {@code YaciScriptSupplier} answers in the node).
 *
 * <p>Absent inputs are left out of the resolved shapes and listed by {@link #unresolved()}. The node never hands
 * the legacy Scalus validator a transaction with an unresolvable input ({@code TransactionValidationService}
 * rejects it first with {@code UtxoNotFound}), so {@link ScalusLegacyEngine} checks {@link #unresolved()} first; the
 * copied Java rules report missing inputs themselves.</p>
 */
final class ResolvedInputs {

    private final Map<TransactionInput, TransactionOutput> outputs = new LinkedHashMap<>();
    private final Set<Utxo> utxos = new LinkedHashSet<>();
    private final Map<String, PlutusScript> referenceScripts = new HashMap<>();
    private final List<TransactionInput> unresolved = new ArrayList<>();

    private ResolvedInputs() {
    }

    /** @param tx the decoded transaction, or null when it does not decode (nothing is resolved) */
    static ResolvedInputs resolve(Transaction tx, LedgerView view) {
        ResolvedInputs resolved = new ResolvedInputs();
        if (tx == null) {
            return resolved;
        }
        TransactionBody body = tx.getBody();
        List<TransactionInput> all = new ArrayList<>();
        addAll(all, body.getInputs());
        addAll(all, body.getReferenceInputs());
        addAll(all, body.getCollateral());
        for (TransactionInput input : all) {
            Outpoint outpoint = Outpoints.of(input.getTransactionId(), input.getIndex());
            if (view.utxo(outpoint) instanceof Lookup.Present<UtxoEntry> present) {
                resolved.add(input, present.value());
            } else {
                resolved.unresolved.add(input);
            }
        }
        return resolved;
    }

    private static void addAll(List<TransactionInput> into, List<TransactionInput> inputs) {
        if (inputs != null) {
            into.addAll(inputs);
        }
    }

    private void add(TransactionInput input, UtxoEntry entry) {
        if (outputs.containsKey(input)) {
            return;
        }
        TransactionOutput output = entry.output();
        outputs.put(input, output);
        String referenceScriptHash = null;
        if (output.getScriptRef() != null) {
            Script script = ReferenceScriptUtil.deserializeScriptRef(output.getScriptRef());
            try {
                referenceScriptHash = HexUtil.encodeHexString(script.getScriptHash());
            } catch (Exception e) {
                throw new IllegalStateException("cannot hash reference script", e);
            }
            if (script instanceof PlutusScript plutus) {
                referenceScripts.put(referenceScriptHash, plutus);
            }
        }
        utxos.add(Utxo.builder()
                .txHash(entry.outpoint().txHash())
                .outputIndex(entry.outpoint().index())
                .address(output.getAddress())
                .amount(amounts(output))
                .dataHash(output.getDatumHash() != null ? HexUtil.encodeHexString(output.getDatumHash()) : null)
                .inlineDatum(inlineDatumHex(entry))
                .referenceScriptHash(referenceScriptHash)
                .build());
    }

    private static String inlineDatumHex(UtxoEntry entry) {
        byte[] exact = entry.inlineDatumCbor();
        if (exact != null) {
            return HexUtil.encodeHexString(exact);
        }
        return entry.output().getInlineDatum() != null ? entry.output().getInlineDatum().serializeToHex() : null;
    }

    private static List<Amount> amounts(TransactionOutput output) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(output.getValue().getCoin()));
        List<MultiAsset> multiAssets = output.getValue().getMultiAssets();
        if (multiAssets != null) {
            for (MultiAsset multiAsset : multiAssets) {
                for (Asset asset : multiAsset.getAssets()) {
                    String name = asset.getNameAsHex();
                    if (name != null && name.startsWith("0x")) {
                        name = name.substring(2);
                    }
                    amounts.add(Amount.asset(multiAsset.getPolicyId() + (name == null ? "" : name), asset.getValue()));
                }
            }
        }
        return amounts;
    }

    /** @return the spending, reference and collateral inputs the view does not have, in body order */
    List<TransactionInput> unresolved() {
        return unresolved;
    }

    /** @return the resolved inputs as CCL UTxOs */
    Set<Utxo> utxos() {
        return utxos;
    }

    /** @return the resolved outputs by input */
    Map<TransactionInput, TransactionOutput> outputs() {
        return outputs;
    }

    /** @return a script supplier over the resolved inputs' Plutus reference scripts */
    ScriptSupplier scriptSupplier() {
        return hash -> Optional.ofNullable(referenceScripts.get(hash));
    }
}
