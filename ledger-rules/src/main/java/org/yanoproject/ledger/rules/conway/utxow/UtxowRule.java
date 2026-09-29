package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.BootstrapWitness;
import org.yanoproject.ledger.rules.conway.tx.Hashes;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawRedeemer;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.Timelock;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.tx.VKeyWitness;
import org.yanoproject.ledger.rules.conway.utxo.UtxoRule;
import org.yanoproject.ledger.rules.conway.utxos.UtxosRule;
import org.yanoproject.ledger.rules.conway.utxow.WitnessNeeds.NeededScript;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The Conway {@code UTXOW} rule ({@code babbageUtxowTransition}, Babbage/Rules/Utxow.hs:328-391, used by Conway,
 * Conway/Rules/Utxow.hs:198): the witness checks in Haskell's order, then the {@code UTXO} sub-rule (:391).
 *
 * <ol>
 *   <li>{@code validateFailedBabbageScripts} (:349, dynamic): {@code ScriptWitnessNotValidatingUTXOW};</li>
 *   <li>{@code babbageMissingScripts} (:354, dynamic): {@code ExtraneousScriptWitnessesUTXOW},
 *       {@code MissingScriptWitnessesUTXOW};</li>
 *   <li>{@code missingRequiredDatums} (:357, dynamic): {@code UnspendableUTxONoDatumHash},
 *       {@code MissingRequiredDatums}, {@code NotAllowedSupplementalDatums};</li>
 *   <li>{@code hasExactSetOfRedeemers} (:361, dynamic): {@code ExtraRedeemers}, {@code MissingRedeemers};</li>
 *   <li>{@code validateVerifiedWits} (:366, static): {@code InvalidWitnessesUTXOW};</li>
 *   <li>{@code validateNeededWitnesses} (:369, dynamic): {@code MissingVKeyWitnessesUTXOW};</li>
 *   <li>{@code validateMetadata} (:374, static): {@code MissingTxMetadata}, {@code MissingTxBodyMetadataHash},
 *       {@code ConflictingMetadataHash}, {@code InvalidMetadata};</li>
 *   <li>{@code validateScriptsWellFormed} (:379, static): {@code MalformedScriptWitnesses},
 *       {@code MalformedReferenceScripts};</li>
 *   <li>{@code checkScriptIntegrityHash} (:389, dynamic): {@code PPViewHashesDontMatch} before protocol version
 *       11, {@code ScriptIntegrityHashMismatch} from 11.</li>
 * </ol>
 *
 * <p>Each item is one {@code runTest}/{@code runTestOnSignal} predicate whose failures accumulate
 * ({@code sequenceA_}) in the listed order. The rule reads the state before the transaction (invariant 5); in
 * Conway no witness check reads the certificate state ({@link WitnessNeeds}).</p>
 *
 * <p><b>Scripts.</b> {@code scriptsProvided} are the witness scripts and the reference scripts of the spending and
 * reference inputs ({@code getBabbageScriptsProvided}, Babbage/UTxO.hs:140-150); {@code scriptsNeeded} is
 * {@code getConwayScriptsNeeded}. Native scripts are evaluated here. Plutus scripts are judged well formed by
 * {@link PlutusScriptDecoder} (plutus-ledger-api's {@code deserialiseScript}) and, when the node has one, also by
 * the phase-2 evaluator ({@link ScriptPhaseEvaluator#isWellFormed}); their contexts are collected by the evaluator
 * ({@link ScriptPhaseEvaluator#collect}, the {@code CollectErrors} that {@code UTXOS} reports) when the transaction
 * needs a Plutus script it provides; without an evaluator such a transaction fails closed.</p>
 */
public final class UtxowRule {

    private UtxowRule() {
    }

    /** Runs {@code UTXOW} (and {@code UTXO}, {@code UTXOS} under it) as a sub-rule of {@code LEDGER}. */
    public static void apply(RuleFrame ledger) {
        RuleFrame utxow = ledger.child(LedgerRuleName.UTXOW);
        validate(utxow);
        UtxoRule.apply(utxow);
        ledger.subRule(utxow);
    }

    /** The transaction's scripts as {@code UTXOW} sees them. */
    private record Scripts(SortedMap<String, RawScript> provided, SortedSet<String> witnessed,
                           SortedSet<String> referenced, List<NeededScript> needed, SortedSet<String> neededHashes) {

        /** @return the needed scripts that are provided as Plutus scripts */
        List<NeededScript> neededPlutus() {
            return needed.stream().filter(n -> provided.containsKey(n.hash()) && provided.get(n.hash()).isPlutus())
                    .toList();
        }

        /** {@code plutusLanguagesUsed}: the languages of the needed, provided Plutus scripts. */
        SortedSet<Integer> languagesUsed() {
            return neededPlutus().stream().map(n -> provided.get(n.hash()).language())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** {@code babbageUtxowTransition}'s predicates, in Haskell's order. */
    static void validate(RuleFrame frame) {
        TransitionContext ctx = frame.context();
        RawTransaction raw = ctx.raw();
        Scripts scripts = scripts(ctx);
        ctx.plutusLanguagesUsed(scripts.languagesUsed());
        prepareScripts(frame, scripts);

        // :349 validateFailedBabbageScripts: needed, provided native scripts that do not validate
        ctx.check(frame, ConwayPredicate.SCRIPT_WITNESS_NOT_VALIDATING, () -> {
            Set<String> vkeyHashes = new HashSet<>();
            raw.vkeyWitnesses().forEach(w -> vkeyHashes.add(w.keyHashHex()));
            List<String> failed = new ArrayList<>();
            scripts.provided().forEach((hash, script) -> {
                if (script.isNative() && scripts.neededHashes().contains(hash)
                        && !Timelock.evaluate(script.timelock(), vkeyHashes, raw.validityStart(), raw.ttl())) {
                    failed.add(hash);
                }
            });
            return failed.isEmpty() ? null : set(failed);
        });

        // :354 babbageMissingScripts: neededNonRefs = needed − refs; [extra = witnessed − neededNonRefs,
        // missing = neededNonRefs − witnessed]
        SortedSet<String> neededNonRefs = new TreeSet<>(scripts.neededHashes());
        neededNonRefs.removeAll(scripts.referenced());
        List<LedgerFailure> missingScripts = new ArrayList<>();
        test(ctx, missingScripts, ConwayPredicate.EXTRANEOUS_SCRIPT_WITNESSES,
                difference(scripts.witnessed(), neededNonRefs));
        test(ctx, missingScripts, ConwayPredicate.MISSING_SCRIPT_WITNESSES,
                difference(neededNonRefs, scripts.witnessed()));
        frame.predicate(missingScripts);

        // :357 missingRequiredDatums
        frame.predicate(missingRequiredDatums(ctx, scripts));

        // :361 hasExactSetOfRedeemers
        frame.predicate(exactSetOfRedeemers(ctx, scripts));

        // :366 validateVerifiedWits (static): signatures over the transaction id, vkey then bootstrap witnesses
        ctx.check(frame, ConwayPredicate.INVALID_WITNESSES, () -> {
            byte[] txId = raw.txId();
            List<String> failed = new ArrayList<>();
            for (VKeyWitness w : new TreeSet<>(raw.vkeyWitnesses())) {
                if (!Ed25519.verify(w.vkey(), w.signature(), txId)) {
                    failed.add(w.toString());
                }
            }
            // Set.fromList keeps the last of witnesses equal under Ord BootstrapWitness (the key hash only)
            SortedMap<String, BootstrapWitness> bootstrap = new TreeMap<>();
            raw.bootstrapWitnesses().forEach(w -> bootstrap.put(w.keyHashHex(), w));
            for (BootstrapWitness w : bootstrap.values()) {
                if (!Ed25519.verify(w.vkey(), w.signature(), txId)) {
                    failed.add(w.toString());
                }
            }
            return failed.isEmpty() ? null : failed.toString();
        });

        // :369 validateNeededWitnesses: witsVKeyNeeded ⊆ witsKeyHashes (vkey and bootstrap witnesses)
        ctx.check(frame, ConwayPredicate.MISSING_VKEY_WITNESSES, () -> {
            SortedSet<String> missing = WitnessNeeds.vkeysNeeded(ctx);
            raw.vkeyWitnesses().forEach(w -> missing.remove(w.keyHashHex()));
            raw.bootstrapWitnesses().forEach(w -> missing.remove(w.keyHashHex()));
            return missing.isEmpty() ? null : set(missing);
        });

        // :374 validateMetadata (static)
        frame.predicate(metadata(ctx));

        // :379 validateScriptsWellFormed (static)
        frame.predicate(scriptsWellFormed(ctx));

        // :387-389 checkScriptIntegrityHash, constructor by protocol version
        ConwayPredicate integrity = ctx.protocolMajor() < 11 ? ConwayPredicate.PP_VIEW_HASHES_DONT_MATCH
                : ConwayPredicate.SCRIPT_INTEGRITY_HASH_MISMATCH;
        ctx.check(frame, integrity, () -> {
            Optional<byte[]> preimage = ScriptIntegrity.preimage(raw, scripts.languagesUsed(), ctx.params());
            byte[] computed = preimage.map(ScriptIntegrity::hash).orElse(null);
            byte[] supplied = raw.scriptDataHash();
            if (Arrays.equals(computed, supplied)) {
                return null;
            }
            String detail = "Mismatch {mismatchSupplied = " + maybe(supplied) + ", mismatchExpected = "
                    + maybe(computed) + "}";
            return integrity == ConwayPredicate.SCRIPT_INTEGRITY_HASH_MISMATCH
                    ? detail + " " + maybe(preimage.orElse(null)) : detail;
        });
    }

    /** {@code scriptsProvidedStAnnTx} and {@code scriptsNeededStAnnTx} ({@code mkAlonzoStAnnTx}). */
    private static Scripts scripts(TransitionContext ctx) {
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
        return new Scripts(provided, witnessed, referenced, needed, neededHashes);
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

    /**
     * Collects the script contexts through the evaluator when the transaction needs a Plutus script it provides
     * (otherwise Haskell's {@code plutusScriptsWithContext} is {@code Right []}): its {@code CollectErrors} are kept
     * for {@code UTXOS}. Without an evaluator, a transaction that needs Plutus fails closed.
     */
    private static void prepareScripts(RuleFrame frame, Scripts scripts) {
        TransitionContext ctx = frame.context();
        boolean needsPlutus = !scripts.neededPlutus().isEmpty();
        if (ctx.evaluator() == null) {
            if (needsPlutus) {
                frame.fail(new LedgerFailure(LedgerRuleName.ENGINE, UtxosRule.PHASE_TWO_UNAVAILABLE,
                        LedgerFailure.Phase.PHASE_1, "the transaction involves Plutus scripts and the node has no "
                        + "phase-2 evaluator"));
            }
            return;
        }
        if (!needsPlutus) {
            return;
        }
        List<LedgerFailure> prepared = ctx.evaluator().collect(ctx.raw().txCbor(), ctx.tx(), ctx.resolvedInputs(),
                ctx.params(), ctx.env().slotConfig(), ctx.forecastBasisSlot());
        ctx.collectFailures(prepared.stream().filter(f -> f.rule() != LedgerRuleName.UTXOW).toList());
    }

    /**
     * {@code missingRequiredDatums} (Alonzo/Rules/Utxow.hs): {@code getInputDataHashesTxBody} over the spending
     * inputs locked by a provided Plutus script (Alonzo/UTxO.hs:245-275) gives the required datum hashes and the
     * inputs without a datum (PlutusV1/V2 only, CIP-69); the witness datums beyond the required ones must be
     * supplemental: datum hashes of the outputs (with the collateral return) or of the reference inputs' outputs
     * ({@code getBabbageSupplementalDataHashes}, Babbage/UTxO.hs:74-84).
     */
    private static List<LedgerFailure> missingRequiredDatums(TransitionContext ctx, Scripts scripts) {
        RawTransaction raw = ctx.raw();
        SortedSet<String> inputHashes = new TreeSet<>();
        SortedSet<TxInRef> noDataHash = new TreeSet<>();
        for (TxInRef in : raw.inputSet()) {
            Optional<UtxoEntry> entry = ctx.utxo(in);
            if (entry.isEmpty()) {
                continue;
            }
            Optional<RawScript> plutus = WitnessNeeds.paymentScriptHash(entry.get()).map(scripts.provided()::get)
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

        List<LedgerFailure> failures = new ArrayList<>();
        if (!noDataHash.isEmpty() && ctx.runs(ConwayPredicate.UNSPENDABLE_UTXO_NO_DATUM_HASH)) {
            // a Set TxIn, in TxIn order
            failures.add(ConwayPredicate.UNSPENDABLE_UTXO_NO_DATUM_HASH.failure(set(noDataHash.stream()
                    .map(TxInRef::toString).toList())));
        }
        SortedSet<String> unmatched = difference(inputHashes, txHashes);
        if (!unmatched.isEmpty() && ctx.runs(ConwayPredicate.MISSING_REQUIRED_DATUMS)) {
            failures.add(ConwayPredicate.MISSING_REQUIRED_DATUMS.failure(set(unmatched) + " " + set(txHashes)));
        }
        if (!notOk.isEmpty() && ctx.runs(ConwayPredicate.NOT_ALLOWED_SUPPLEMENTAL_DATUMS)) {
            failures.add(ConwayPredicate.NOT_ALLOWED_SUPPLEMENTAL_DATUMS.failure(set(notOk) + " " + set(ok)));
        }
        return failures;
    }

    /**
     * {@code hasExactSetOfRedeemers} (Alonzo/Rules/Utxow.hs): the redeemers needed are the purposes of the needed
     * scripts provided as Plutus scripts; {@code extSymmetricDifference} gives the extra redeemer keys (in the
     * redeemer map's order) and the missing purposes (in {@code scriptsNeeded} order).
     */
    private static List<LedgerFailure> exactSetOfRedeemers(TransitionContext ctx, Scripts scripts) {
        RawTransaction raw = ctx.raw();
        Map<Long, NeededScript> needed = new LinkedHashMap<>();
        List<NeededScript> neededList = scripts.neededPlutus();
        neededList.forEach(n -> needed.putIfAbsent(n.key(), n));
        Set<Long> present = new HashSet<>();
        for (RawRedeemer r : raw.redeemers()) {
            present.add(((long) r.tag() << 32) | r.index());
        }
        List<String> extra = new ArrayList<>();
        for (RawRedeemer r : raw.redeemers()) {
            long key = ((long) r.tag() << 32) | r.index();
            if (!needed.containsKey(key)) {
                extra.add(WitnessNeeds.purposeName(r.tag()) + " (AsIx " + r.index() + ")");
            }
        }
        List<String> missing = neededList.stream().filter(n -> !present.contains(n.key()))
                .map(NeededScript::toString).toList();
        List<LedgerFailure> failures = new ArrayList<>();
        test(ctx, failures, ConwayPredicate.EXTRA_REDEEMERS, extra);
        test(ctx, failures, ConwayPredicate.MISSING_REDEEMERS, missing);
        return failures;
    }

    /**
     * {@code validateMetadata} (Shelley/Rules/Utxow.hs:427-443): the body's hash and the auxiliary data must both
     * be absent or both present; when both are, the hash must be blake2b-256 of the auxiliary data's original bytes
     * ({@code hashTxAuxData}) and the auxiliary data valid ({@code validateAlonzoTxAuxData}: its Plutus scripts well
     * formed at the protocol version).
     */
    private static List<LedgerFailure> metadata(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        byte[] bodyHash = raw.auxDataHash();
        List<LedgerFailure> failures = new ArrayList<>();
        if (raw.auxData() == null) {
            if (bodyHash != null && ctx.runs(ConwayPredicate.MISSING_TX_METADATA)) {
                failures.add(ConwayPredicate.MISSING_TX_METADATA.failure(HexUtil.encodeHexString(bodyHash)));
            }
            return failures;
        }
        byte[] computed = Hashes.blake2b256(raw.bytes(raw.auxData()));
        if (bodyHash == null) {
            if (ctx.runs(ConwayPredicate.MISSING_TX_BODY_METADATA_HASH)) {
                failures.add(ConwayPredicate.MISSING_TX_BODY_METADATA_HASH.failure(HexUtil.encodeHexString(computed)));
            }
            return failures;
        }
        if (!Arrays.equals(bodyHash, computed) && ctx.runs(ConwayPredicate.CONFLICTING_METADATA_HASH)) {
            failures.add(ConwayPredicate.CONFLICTING_METADATA_HASH.failure("Mismatch {mismatchSupplied = "
                    + HexUtil.encodeHexString(bodyHash) + ", mismatchExpected = " + HexUtil.encodeHexString(computed)
                    + "}"));
        }
        if (ctx.runs(ConwayPredicate.INVALID_METADATA)) {
            List<String> malformed = malformed(ctx, raw.auxDataContent().plutusScripts());
            if (!malformed.isEmpty()) {
                failures.add(ConwayPredicate.INVALID_METADATA.failure("malformed Plutus scripts " + malformed));
            }
        }
        return failures;
    }

    /**
     * {@code validateScriptsWellFormed} (Babbage/Rules/Utxow.hs:234-273): the Plutus witness scripts, and the
     * reference scripts of the outputs and the collateral return, must be well formed at the protocol version
     * (native scripts always are).
     */
    private static List<LedgerFailure> scriptsWellFormed(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        List<LedgerFailure> failures = new ArrayList<>();
        if (ctx.runs(ConwayPredicate.MALFORMED_SCRIPT_WITNESSES)) {
            List<String> malformed = malformed(ctx, raw.witnessScripts());
            if (!malformed.isEmpty()) {
                failures.add(ConwayPredicate.MALFORMED_SCRIPT_WITNESSES.failure(set(malformed)));
            }
        }
        if (ctx.runs(ConwayPredicate.MALFORMED_REFERENCE_SCRIPTS)) {
            List<String> malformed = malformed(ctx, raw.allOutputs().stream().map(RawOutput::scriptRef)
                    .filter(Objects::nonNull).toList());
            if (!malformed.isEmpty()) {
                failures.add(ConwayPredicate.MALFORMED_REFERENCE_SCRIPTS.failure(set(malformed)));
            }
        }
        return failures;
    }

    /**
     * @return the distinct hashes (sorted) of the Plutus scripts among {@code candidates} that are not well formed:
     *         {@link PlutusScriptDecoder} (Haskell's {@code deserialiseScript}) decides, and a script it accepts must
     *         also decode with the phase-2 evaluator when the node has one that can tell ({@code isWellFormed})
     */
    private static List<String> malformed(TransitionContext ctx, List<RawScript> candidates) {
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

    /** Adds {@code predicate}'s failure when {@code items} (a {@code NonEmpty} payload) is non-empty and it runs. */
    private static void test(TransitionContext ctx, List<LedgerFailure> failures, ConwayPredicate predicate,
                             List<String> items) {
        if (!items.isEmpty() && ctx.runs(predicate)) {
            failures.add(predicate.failure(items.toString()));
        }
    }

    /** As {@link #test(TransitionContext, List, ConwayPredicate, List)} for a set payload ({@code NonEmptySet}). */
    private static void test(TransitionContext ctx, List<LedgerFailure> failures, ConwayPredicate predicate,
                             SortedSet<String> items) {
        if (!items.isEmpty() && ctx.runs(predicate)) {
            failures.add(predicate.failure(set(items)));
        }
    }

    private static SortedSet<String> difference(Set<String> a, Set<String> b) {
        SortedSet<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static String set(Iterable<String> items) {
        return "fromList " + items;
    }

    private static String maybe(byte[] value) {
        return value == null ? "SNothing" : "SJust " + HexUtil.encodeHexString(value);
    }
}
