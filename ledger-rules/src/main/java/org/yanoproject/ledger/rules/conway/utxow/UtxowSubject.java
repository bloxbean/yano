package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawRedeemer;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxow.WitnessNeeds.NeededScript;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What the {@code UTXOW} units read ({@code ConwayScopes.UTXOW}): the transition and the transaction's scripts as
 * {@code UTXOW} sees them ({@code scriptsProvidedStAnnTx}, {@code scriptsNeededStAnnTx}; {@code mkAlonzoStAnnTx}).
 *
 * <p>{@code scriptsProvided} are the witness scripts and the reference scripts of the spending and reference inputs
 * ({@code getBabbageScriptsProvided}, Babbage/UTxO.hs:140-150); {@code scriptsNeeded} is
 * {@code getConwayScriptsNeeded}.</p>
 */
public final class UtxowSubject {

    /**
     * {@code missingRequiredDatums}' sets (Alonzo/Rules/Utxow.hs).
     *
     * @param noDataHash   spending inputs locked by a provided PlutusV1/V2 script without a datum (CIP-69)
     * @param inputHashes  the required datum hashes ({@code getInputDataHashesTxBody}, Alonzo/UTxO.hs:245-275)
     * @param txHashes     the witness datums' hashes
     * @param notAllowed   supplemental witness datums that are not allowed
     * @param allowed      supplemental witness datums that are allowed
     */
    public record Datums(SortedSet<TxInRef> noDataHash, SortedSet<String> inputHashes, SortedSet<String> txHashes,
                         SortedSet<String> notAllowed, SortedSet<String> allowed) {
    }

    /**
     * {@code hasExactSetOfRedeemers}' difference (Alonzo/Rules/Utxow.hs).
     *
     * @param extra   the redeemer keys no needed Plutus script has, in the redeemer map's order
     * @param missing the needed Plutus scripts without a redeemer, in {@code scriptsNeeded} order
     */
    public record Redeemers(List<String> extra, List<String> missing) {
    }

    private final TransitionContext ctx;
    private final SortedMap<String, RawScript> provided;
    private final SortedSet<String> witnessed;
    private final SortedSet<String> referenced;
    private final List<NeededScript> needed;
    private final SortedSet<String> neededHashes;
    private Datums datums;
    private Redeemers redeemers;

    private UtxowSubject(TransitionContext ctx, SortedMap<String, RawScript> provided, SortedSet<String> witnessed,
                         SortedSet<String> referenced, List<NeededScript> needed, SortedSet<String> neededHashes) {
        this.ctx = ctx;
        this.provided = provided;
        this.witnessed = witnessed;
        this.referenced = referenced;
        this.needed = needed;
        this.neededHashes = neededHashes;
    }

    /** Reads the transaction's provided and needed scripts. */
    public static UtxowSubject of(TransitionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        RawTransaction raw = ctx.raw();
        SortedMap<String, RawScript> provided = new TreeMap<>();
        SortedSet<String> witnessed = new TreeSet<>();
        for (RawScript script : raw.witnessScripts()) {
            String hash = script.hashHex();
            witnessed.add(hash);
            provided.put(hash, script);
        }
        // getReferenceScripts utxo (referenceInputs ∪ inputs), left-biased union over the witness scripts
        SortedSet<TxInRef> providing = new TreeSet<>(raw.inputSet());
        providing.addAll(raw.referenceSet());
        SortedSet<String> referenced = new TreeSet<>();
        for (TxInRef in : providing) {
            Optional<UtxoEntry> entry = ctx.utxo(in);
            if (entry.isPresent() && entry.get().output().getScriptRef() != null) {
                RawScript script = referenceScript(in, entry.get().output());
                String hash = script.hashHex();
                referenced.add(hash);
                provided.put(hash, script);
            }
        }
        List<NeededScript> needed = WitnessNeeds.scriptsNeeded(ctx);
        SortedSet<String> neededHashes = needed.stream().map(NeededScript::hash)
                .collect(Collectors.toCollection(TreeSet::new));
        return new UtxowSubject(ctx, provided, witnessed, referenced, needed, neededHashes);
    }

    private static RawScript referenceScript(TxInRef in, TransactionOutput output) {
        try {
            return RawScript.fromScriptRef(output.getScriptRef());
        } catch (TxDecodingException e) {
            // Outputs in the UTxO decoded when they were created, so this is a broken view: fail closed.
            throw new IllegalStateException("the reference script of UTxO " + in + " does not decode: "
                    + e.getMessage(), e);
        }
    }

    public TransitionContext ctx() {
        return ctx;
    }

    public RawTransaction raw() {
        return ctx.raw();
    }

    /** @return {@code scriptsProvided}, by hash */
    public SortedMap<String, RawScript> provided() {
        return provided;
    }

    /** @return the hashes of the witness scripts */
    public SortedSet<String> witnessed() {
        return witnessed;
    }

    /** @return the hashes of the reference scripts of the spending and reference inputs */
    public SortedSet<String> referenced() {
        return referenced;
    }

    /** @return {@code scriptsNeeded}, in Haskell's order */
    public List<NeededScript> needed() {
        return needed;
    }

    public SortedSet<String> neededHashes() {
        return neededHashes;
    }

    /** @return the needed scripts that are provided as Plutus scripts */
    public List<NeededScript> neededPlutus() {
        return needed.stream().filter(n -> provided.containsKey(n.hash()) && provided.get(n.hash()).isPlutus())
                .toList();
    }

    /** {@code plutusLanguagesUsed}: the languages of the needed, provided Plutus scripts. */
    public SortedSet<Integer> languagesUsed() {
        return neededPlutus().stream().map(n -> provided.get(n.hash()).language())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * {@code missingRequiredDatums} (Alonzo/Rules/Utxow.hs): {@code getInputDataHashesTxBody} over the spending inputs
     * locked by a provided Plutus script (Alonzo/UTxO.hs:245-275) gives the required datum hashes and the inputs without
     * a datum (PlutusV1/V2 only, CIP-69); the witness datums beyond the required ones must be supplemental: datum
     * hashes of the outputs (with the collateral return) or of the reference inputs' outputs
     * ({@code getBabbageSupplementalDataHashes}, Babbage/UTxO.hs:74-84).
     *
     * @return the sets, computed on first use
     */
    public Datums datums() {
        if (datums == null) {
            RawTransaction raw = ctx.raw();
            SortedSet<String> inputHashes = new TreeSet<>();
            SortedSet<TxInRef> noDataHash = new TreeSet<>();
            for (TxInRef in : raw.inputSet()) {
                Optional<UtxoEntry> entry = ctx.utxo(in);
                if (entry.isEmpty()) {
                    continue;
                }
                Optional<RawScript> plutus = WitnessNeeds.paymentScriptHash(entry.get()).map(provided::get)
                        .filter(RawScript::isPlutus);
                if (plutus.isEmpty()) {
                    continue;
                }
                TransactionOutput output = entry.get().output();
                if (output.getInlineDatum() != null || entry.get().inlineDatumCbor() != null) {
                    continue;
                }
                if (output.getDatumHash() != null) {
                    inputHashes.add(HexUtil.encodeHexString(output.getDatumHash()));
                } else if (plutus.get().language() < RawScript.PLUTUS_V3) {
                    noDataHash.add(in);
                }
            }
            SortedSet<String> txHashes = new TreeSet<>();
            raw.datumHashes().forEach(h -> txHashes.add(HexUtil.encodeHexString(h)));

            SortedSet<String> allowed = new TreeSet<>();
            for (RawOutput out : raw.allOutputs()) {
                if (out.datumHash() != null) {
                    allowed.add(HexUtil.encodeHexString(out.datumHash()));
                }
            }
            for (TxInRef in : raw.referenceSet()) {
                ctx.utxo(in).map(e -> e.output().getDatumHash()).filter(Objects::nonNull)
                        .ifPresent(h -> allowed.add(HexUtil.encodeHexString(h)));
            }
            SortedSet<String> supplemental = difference(txHashes, inputHashes);
            SortedSet<String> notOk = difference(supplemental, allowed);
            SortedSet<String> ok = new TreeSet<>(supplemental);
            ok.retainAll(allowed);
            datums = new Datums(noDataHash, inputHashes, txHashes, notOk, ok);
        }
        return datums;
    }

    /**
     * {@code hasExactSetOfRedeemers} (Alonzo/Rules/Utxow.hs): the redeemers needed are the purposes of the needed
     * scripts provided as Plutus scripts; {@code extSymmetricDifference} gives the extra redeemer keys (in the redeemer
     * map's order) and the missing purposes (in {@code scriptsNeeded} order).
     *
     * @return the difference, computed on first use
     */
    public Redeemers redeemers() {
        if (redeemers == null) {
            RawTransaction raw = ctx.raw();
            Map<Long, NeededScript> neededKeys = new LinkedHashMap<>();
            List<NeededScript> neededList = neededPlutus();
            neededList.forEach(n -> neededKeys.putIfAbsent(n.key(), n));
            Set<Long> present = new HashSet<>();
            for (RawRedeemer r : raw.redeemers()) {
                present.add(((long) r.tag() << 32) | r.index());
            }
            List<String> extra = new ArrayList<>();
            for (RawRedeemer r : raw.redeemers()) {
                long key = ((long) r.tag() << 32) | r.index();
                if (!neededKeys.containsKey(key)) {
                    extra.add(WitnessNeeds.purposeName(r.tag()) + " (AsIx " + r.index() + ")");
                }
            }
            List<String> missing = neededList.stream().filter(n -> !present.contains(n.key()))
                    .map(NeededScript::toString).toList();
            redeemers = new Redeemers(List.copyOf(extra), missing);
        }
        return redeemers;
    }

    static SortedSet<String> difference(Set<String> a, Set<String> b) {
        SortedSet<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    /**
     * @return the distinct hashes (sorted) of the Plutus scripts among {@code candidates} that are not well formed at
     *         the protocol version: {@link PlutusScriptDecoder} (Haskell's {@code deserialiseScript}) decides, and a
     *         script it accepts must also decode with the phase-2 evaluator when the node has one that can tell
     *         ({@code isWellFormed})
     */
    public List<String> malformed(List<RawScript> candidates) {
        ScriptPhaseEvaluator evaluator = ctx.evaluator();
        SortedSet<String> malformed = new TreeSet<>();
        for (RawScript script : candidates) {
            if (!script.isPlutus()) {
                continue;
            }
            byte[] bytes = script.bytes();
            boolean wellFormed = PlutusScriptDecoder.isWellFormed(script.language(), bytes, ctx.protocolMajor())
                    && evaluatorAccepts(evaluator, script.language(), bytes, ctx.protocolMajor());
            if (!wellFormed) {
                malformed.add(script.hashHex());
            }
        }
        return List.copyOf(malformed);
    }

    private static boolean evaluatorAccepts(ScriptPhaseEvaluator evaluator, int language, byte[] script,
                                            int protocolMajor) {
        if (evaluator == null) {
            return true;
        }
        try {
            return evaluator.isWellFormed(language, script, protocolMajor);
        } catch (UnsupportedOperationException e) {
            return true; // the evaluator has no Plutus decoder: the Java decoder's verdict stands
        }
    }
}
