package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code totalRefScriptSizeInBlock} (Conway/Rules/Bbody.hs:357-371), one transaction at a time, for the
 * {@code BBODY.BodyRefScriptsSizeTooBig} limit {@code maxRefScriptSizePerBlock}.
 *
 * <p>A transaction counts {@code txNonDistinctRefScriptsSize} (Conway/UTxO.hs:166-170): the original size of the
 * reference script of every spending and reference input. At protocol version 10 and below every transaction is
 * measured against the pre-block UTxO; from 11 against the pre-block UTxO plus the outputs of the earlier
 * transactions of the block ({@code txouts} of a phase-2-valid one, {@code collOuts} of an invalid one; spent entries
 * are not removed).</p>
 */
public final class BlockRefScriptSize {

    private final Function<Outpoint, Lookup<byte[]>> preBlockScriptRefs;
    private final boolean cumulative;
    private final Map<Outpoint, Lookup<byte[]>> producedScriptRefs = new HashMap<>();
    private long total;

    /**
     * @param preBlockScriptRefs the reference script of a pre-block UTxO entry: present with its {@code script}
     *                           CBOR, absent when the entry has none or does not exist, unavailable when unknown
     * @param protocolMajor      the protocol major version of the pre-block state
     */
    public BlockRefScriptSize(Function<Outpoint, Lookup<byte[]>> preBlockScriptRefs, int protocolMajor) {
        this.preBlockScriptRefs = Objects.requireNonNull(preBlockScriptRefs, "preBlockScriptRefs");
        this.cumulative = protocolMajor >= 11;
    }

    /** Over a ledger view of the pre-block state. */
    public static BlockRefScriptSize over(LedgerView preBlock, int protocolMajor) {
        Objects.requireNonNull(preBlock, "preBlock");
        return new BlockRefScriptSize(in -> switch (preBlock.utxo(in)) {
            case Lookup.Present<UtxoEntry> p -> Lookup.ofNullable(p.value().output().getScriptRef());
            case Lookup.Absent<UtxoEntry> a -> Lookup.absent();
            case Lookup.Unavailable<UtxoEntry> u -> Lookup.unavailable(u.reason());
        }, protocolMajor);
    }

    /**
     * @return the reference-script bytes {@code body} adds to the block total, or unavailable (with the reason) when
     *         an input cannot be resolved or a reference script does not decode
     */
    public Lookup<Long> measure(TransactionBody body) {
        Set<Outpoint> inputs = new LinkedHashSet<>();
        addAll(inputs, body.getReferenceInputs());
        addAll(inputs, body.getInputs());
        long size = 0;
        for (Outpoint in : inputs) {
            Lookup<byte[]> entry = cumulative ? producedScriptRefs.get(in) : null;
            if (entry == null) {
                entry = preBlockScriptRefs.apply(in);
            }
            if (entry instanceof Lookup.Unavailable<byte[]> u) {
                return Lookup.unavailable(u.reason());
            }
            if (entry instanceof Lookup.Present<byte[]> p) {
                try {
                    size += MinFee.scriptOriginalSize(p.value());
                } catch (RuntimeException e) {
                    return Lookup.unavailable("reference script of " + in + " does not decode: " + e.getMessage());
                }
            }
        }
        return Lookup.present(size);
    }

    /**
     * Adds a transaction of the block: its measured size joins the total and, from protocol version 11, its outputs
     * become visible to the later transactions.
     *
     * @param txId        the transaction id
     * @param body        the transaction body
     * @param phase2Valid whether the transaction is phase-2 valid ({@code txouts}) or not ({@code collOuts})
     * @param size        its {@link #measure} result
     */
    public void add(String txId, TransactionBody body, boolean phase2Valid, long size) {
        total += size;
        if (!cumulative) {
            return;
        }
        List<TransactionOutput> outputs = body.getOutputs() != null ? body.getOutputs() : List.of();
        if (phase2Valid) {
            for (int o = 0; o < outputs.size(); o++) {
                putScriptRef(new Outpoint(txId, o), outputs.get(o));
            }
        } else if (body.getCollateralReturn() != null) {
            putScriptRef(new Outpoint(txId, outputs.size()), body.getCollateralReturn());
        }
    }

    /** @return the sum of the added transactions' sizes */
    public long total() {
        return total;
    }

    private void putScriptRef(Outpoint outpoint, TransactionOutput output) {
        producedScriptRefs.put(outpoint, Lookup.ofNullable(output.getScriptRef()));
    }

    private static void addAll(Set<Outpoint> into, List<TransactionInput> inputs) {
        if (inputs == null) {
            return;
        }
        for (TransactionInput in : inputs) {
            into.add(new Outpoint(in.getTransactionId().toLowerCase(), in.getIndex()));
        }
    }
}
