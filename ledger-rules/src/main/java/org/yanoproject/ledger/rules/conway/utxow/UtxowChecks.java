package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.ruleset.RuleUnit;
import org.yanoproject.ledger.rules.conway.ruleset.UnitKind;
import org.yanoproject.ledger.rules.conway.tx.BootstrapWitness;
import org.yanoproject.ledger.rules.conway.tx.Hashes;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.Timelock;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.tx.VKeyWitness;
import org.yanoproject.ledger.rules.conway.utxos.UtxosRule;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@code UTXOW} units ({@code babbageUtxowTransition}, Babbage/Rules/Utxow.hs:328-391, used by Conway,
 * Conway/Rules/Utxow.hs:198), of {@code ConwayScopes.UTXOW}. Where one Haskell predicate reports several constructors
 * ({@code babbageMissingScripts}, {@code missingRequiredDatums}, {@code hasExactSetOfRedeemers},
 * {@code validateMetadata}, {@code validateScriptsWellFormed}), each constructor is its own unit, in the predicate's
 * order: small-steps prepends a predicate's failures reversed, so two adjacent units report exactly what the one
 * predicate did ({@code RuleFrame}).
 */
public final class UtxowChecks {

    private UtxowChecks() {
    }

    /**
     * The engine's script preparation, before the witness checks: records {@code plutusLanguagesUsed} and, when the
     * transaction needs a Plutus script it provides (otherwise Haskell's {@code plutusScriptsWithContext} is
     * {@code Right []}), collects the script contexts through the evaluator ({@link ScriptPhaseEvaluator#collect}); its
     * {@code CollectErrors} are kept for {@code UTXOS}. Without an evaluator, a transaction that needs Plutus fails
     * closed ({@code ENGINE.PhaseTwoEvaluatorUnavailable}).
     */
    public static final class PrepareScripts implements RuleUnit<UtxowSubject> {

        @Override
        public String id() {
            return "UTXOW.prepareScripts";
        }

        @Override
        public UnitKind kind() {
            return UnitKind.STEP;
        }

        @Override
        public CheckLabel label() {
            return CheckLabel.DYNAMIC;
        }

        @Override
        public String haskellRef() {
            return "Alonzo/UTxO.hs (plutusLanguagesUsed); Alonzo/Plutus/Context.hs (collectPlutusScriptsWithContext, "
                    + "whose CollectErrors UTXOS reports: Babbage/Rules/Utxos.hs:143, 206)";
        }

        @Override
        public List<LedgerFailure> apply(UtxowSubject s) {
            TransitionContext ctx = s.ctx();
            ctx.plutusLanguagesUsed(s.languagesUsed());
            boolean needsPlutus = !s.neededPlutus().isEmpty();
            if (ctx.evaluator() == null) {
                return needsPlutus ? List.of(new LedgerFailure(LedgerRuleName.ENGINE, UtxosRule.PHASE_TWO_UNAVAILABLE,
                        LedgerFailure.Phase.PHASE_1, "the transaction involves Plutus scripts and the node has no "
                        + "phase-2 evaluator")) : List.of();
            }
            if (needsPlutus) {
                List<LedgerFailure> prepared = ctx.evaluator().collect(ctx.raw(), ctx.resolvedInputs(), ctx.params(),
                        ctx.env().slotConfig(), ctx.forecastBasisSlot());
                ctx.collectFailures(prepared.stream().filter(f -> f.rule() != LedgerRuleName.UTXOW).toList());
            }
            return List.of();
        }
    }

    /** :349 {@code validateFailedBabbageScripts}: needed, provided native scripts that do not validate. */
    public static final class ScriptWitnessNotValidating extends PredicateCheck<UtxowSubject> {

        public ScriptWitnessNotValidating() {
            super(ConwayPredicate.SCRIPT_WITNESS_NOT_VALIDATING);
        }

        @Override
        protected String detail(UtxowSubject s) {
            RawTransaction raw = s.raw();
            Set<String> vkeyHashes = new HashSet<>();
            raw.vkeyWitnesses().forEach(w -> vkeyHashes.add(w.keyHashHex()));
            List<String> failed = new ArrayList<>();
            s.provided().forEach((hash, script) -> {
                if (script.isNative() && s.neededHashes().contains(hash)
                        && !Timelock.evaluate(script.timelock(), vkeyHashes, raw.validityStart(), raw.ttl())) {
                    failed.add(hash);
                }
            });
            return failed.isEmpty() ? null : set(failed);
        }
    }

    /** {@code neededNonRefs = needed − refs} ({@code babbageMissingScripts}, Babbage/Rules/Utxow.hs:193-208). */
    private static SortedSet<String> neededNonRefs(UtxowSubject s) {
        SortedSet<String> neededNonRefs = new TreeSet<>(s.neededHashes());
        neededNonRefs.removeAll(s.referenced());
        return neededNonRefs;
    }

    /** :354 {@code babbageMissingScripts}, first half: {@code extra = witnessed − neededNonRefs}. */
    public static final class ExtraneousScriptWitnesses extends PredicateCheck<UtxowSubject> {

        public ExtraneousScriptWitnesses() {
            super(ConwayPredicate.EXTRANEOUS_SCRIPT_WITNESSES);
        }

        @Override
        protected String detail(UtxowSubject s) {
            return nonEmptySet(UtxowSubject.difference(s.witnessed(), neededNonRefs(s)));
        }
    }

    /** :354 {@code babbageMissingScripts}, second half: {@code missing = neededNonRefs − witnessed}. */
    public static final class MissingScriptWitnesses extends PredicateCheck<UtxowSubject> {

        public MissingScriptWitnesses() {
            super(ConwayPredicate.MISSING_SCRIPT_WITNESSES);
        }

        @Override
        protected String detail(UtxowSubject s) {
            return nonEmptySet(UtxowSubject.difference(neededNonRefs(s), s.witnessed()));
        }
    }

    /** :357 {@code missingRequiredDatums}: spending inputs without a datum hash (a {@code Set TxIn}, in order). */
    public static final class UnspendableUtxoNoDatumHash extends PredicateCheck<UtxowSubject> {

        public UnspendableUtxoNoDatumHash() {
            super(ConwayPredicate.UNSPENDABLE_UTXO_NO_DATUM_HASH);
        }

        @Override
        protected String detail(UtxowSubject s) {
            SortedSet<TxInRef> noDataHash = s.datums().noDataHash();
            return noDataHash.isEmpty() ? null : set(noDataHash.stream().map(TxInRef::toString).toList());
        }
    }

    /** :357 {@code missingRequiredDatums}: required datum hashes without a witness datum. */
    public static final class MissingRequiredDatums extends PredicateCheck<UtxowSubject> {

        public MissingRequiredDatums() {
            super(ConwayPredicate.MISSING_REQUIRED_DATUMS);
        }

        @Override
        protected String detail(UtxowSubject s) {
            UtxowSubject.Datums d = s.datums();
            SortedSet<String> unmatched = UtxowSubject.difference(d.inputHashes(), d.txHashes());
            return unmatched.isEmpty() ? null : set(unmatched) + " " + set(d.txHashes());
        }
    }

    /** :357 {@code missingRequiredDatums}: supplemental witness datums that are not allowed. */
    public static final class NotAllowedSupplementalDatums extends PredicateCheck<UtxowSubject> {

        public NotAllowedSupplementalDatums() {
            super(ConwayPredicate.NOT_ALLOWED_SUPPLEMENTAL_DATUMS);
        }

        @Override
        protected String detail(UtxowSubject s) {
            UtxowSubject.Datums d = s.datums();
            return d.notAllowed().isEmpty() ? null : set(d.notAllowed()) + " " + set(d.allowed());
        }
    }

    /** :361 {@code hasExactSetOfRedeemers}: redeemers no needed Plutus script has. */
    public static final class ExtraRedeemers extends PredicateCheck<UtxowSubject> {

        public ExtraRedeemers() {
            super(ConwayPredicate.EXTRA_REDEEMERS);
        }

        @Override
        protected String detail(UtxowSubject s) {
            List<String> extra = s.redeemers().extra();
            return extra.isEmpty() ? null : extra.toString();
        }
    }

    /** :361 {@code hasExactSetOfRedeemers}: needed Plutus scripts without a redeemer. */
    public static final class MissingRedeemers extends PredicateCheck<UtxowSubject> {

        public MissingRedeemers() {
            super(ConwayPredicate.MISSING_REDEEMERS);
        }

        @Override
        protected String detail(UtxowSubject s) {
            List<String> missing = s.redeemers().missing();
            return missing.isEmpty() ? null : missing.toString();
        }
    }

    /** :366 {@code validateVerifiedWits} (static): signatures over the transaction id, vkey then bootstrap witnesses. */
    public static final class InvalidWitnesses extends PredicateCheck<UtxowSubject> {

        public InvalidWitnesses() {
            super(ConwayPredicate.INVALID_WITNESSES);
        }

        @Override
        protected String detail(UtxowSubject s) {
            RawTransaction raw = s.raw();
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
        }
    }

    /** :369 {@code validateNeededWitnesses}: {@code witsVKeyNeeded ⊆ witsKeyHashes} (vkey and bootstrap witnesses). */
    public static final class MissingVKeyWitnesses extends PredicateCheck<UtxowSubject> {

        public MissingVKeyWitnesses() {
            super(ConwayPredicate.MISSING_VKEY_WITNESSES);
        }

        @Override
        protected String detail(UtxowSubject s) {
            SortedSet<String> missing = WitnessNeeds.vkeysNeeded(s.ctx());
            s.raw().vkeyWitnesses().forEach(w -> missing.remove(w.keyHashHex()));
            s.raw().bootstrapWitnesses().forEach(w -> missing.remove(w.keyHashHex()));
            return missing.isEmpty() ? null : set(missing);
        }
    }

    /**
     * :374 {@code validateMetadata} (static; Shelley/Rules/Utxow.hs:427-443), absent auxiliary data: the body must
     * not state a hash.
     */
    public static final class MissingTxMetadata extends PredicateCheck<UtxowSubject> {

        public MissingTxMetadata() {
            super(ConwayPredicate.MISSING_TX_METADATA);
        }

        @Override
        protected String detail(UtxowSubject s) {
            byte[] bodyHash = s.raw().auxDataHash();
            return s.raw().auxData() == null && bodyHash != null ? HexUtil.encodeHexString(bodyHash) : null;
        }
    }

    /** :374 {@code validateMetadata}, auxiliary data without a body hash. */
    public static final class MissingTxBodyMetadataHash extends PredicateCheck<UtxowSubject> {

        public MissingTxBodyMetadataHash() {
            super(ConwayPredicate.MISSING_TX_BODY_METADATA_HASH);
        }

        @Override
        protected String detail(UtxowSubject s) {
            RawTransaction raw = s.raw();
            if (raw.auxData() == null || raw.auxDataHash() != null) {
                return null;
            }
            return HexUtil.encodeHexString(Hashes.blake2b256(raw.bytes(raw.auxData())));
        }
    }

    /**
     * :374 {@code validateMetadata}, both present: the hash must be blake2b-256 of the auxiliary data's original bytes
     * ({@code hashTxAuxData}).
     */
    public static final class ConflictingMetadataHash extends PredicateCheck<UtxowSubject> {

        public ConflictingMetadataHash() {
            super(ConwayPredicate.CONFLICTING_METADATA_HASH);
        }

        @Override
        protected String detail(UtxowSubject s) {
            RawTransaction raw = s.raw();
            byte[] bodyHash = raw.auxDataHash();
            if (raw.auxData() == null || bodyHash == null) {
                return null;
            }
            byte[] computed = Hashes.blake2b256(raw.bytes(raw.auxData()));
            return Arrays.equals(bodyHash, computed) ? null : "Mismatch {mismatchSupplied = "
                    + HexUtil.encodeHexString(bodyHash) + ", mismatchExpected = " + HexUtil.encodeHexString(computed)
                    + "}";
        }
    }

    /**
     * :374 {@code validateMetadata}, both present: the auxiliary data must be valid ({@code validateAlonzoTxAuxData},
     * Alonzo/TxAuxData.hs:360-373: its Plutus scripts well formed at the protocol version).
     */
    public static final class InvalidMetadata extends PredicateCheck<UtxowSubject> {

        public InvalidMetadata() {
            super(ConwayPredicate.INVALID_METADATA);
        }

        @Override
        protected String detail(UtxowSubject s) {
            RawTransaction raw = s.raw();
            if (raw.auxData() == null || raw.auxDataHash() == null) {
                return null;
            }
            List<String> malformed = s.malformed(raw.auxDataContent().plutusScripts());
            return malformed.isEmpty() ? null : "malformed Plutus scripts " + malformed;
        }
    }

    /**
     * :379 {@code validateScriptsWellFormed} (static; Babbage/Rules/Utxow.hs:234-273): the Plutus witness scripts must
     * be well formed at the protocol version (native scripts always are).
     */
    public static final class MalformedScriptWitnesses extends PredicateCheck<UtxowSubject> {

        public MalformedScriptWitnesses() {
            super(ConwayPredicate.MALFORMED_SCRIPT_WITNESSES);
        }

        @Override
        protected String detail(UtxowSubject s) {
            List<String> malformed = s.malformed(s.raw().witnessScripts());
            return malformed.isEmpty() ? null : set(malformed);
        }
    }

    /** :379 {@code validateScriptsWellFormed}: the reference scripts of the outputs and the collateral return. */
    public static final class MalformedReferenceScripts extends PredicateCheck<UtxowSubject> {

        public MalformedReferenceScripts() {
            super(ConwayPredicate.MALFORMED_REFERENCE_SCRIPTS);
        }

        @Override
        protected String detail(UtxowSubject s) {
            List<String> malformed = s.malformed(s.raw().allOutputs().stream().map(RawOutput::scriptRef)
                    .filter(Objects::nonNull).toList());
            return malformed.isEmpty() ? null : set(malformed);
        }
    }

    /**
     * :387-389 {@code checkScriptIntegrityHash} before protocol version 11: {@code PPViewHashesDontMatch} with the
     * supplied and the computed hash.
     */
    public static final class PpViewHashesDontMatch extends PredicateCheck<UtxowSubject> {

        public PpViewHashesDontMatch() {
            super(ConwayPredicate.PP_VIEW_HASHES_DONT_MATCH);
        }

        @Override
        protected String detail(UtxowSubject s) {
            Optional<byte[]> preimage = ScriptIntegrity.preimage(s.raw(), s.languagesUsed(), s.ctx().params());
            return mismatch(s, preimage);
        }
    }

    /**
     * :387-389 {@code checkScriptIntegrityHash} from protocol version 11: {@code ScriptIntegrityHashMismatch}, which
     * also carries the computed preimage.
     */
    public static final class ScriptIntegrityHashMismatch extends PredicateCheck<UtxowSubject> {

        public ScriptIntegrityHashMismatch() {
            super(ConwayPredicate.SCRIPT_INTEGRITY_HASH_MISMATCH);
        }

        @Override
        protected String detail(UtxowSubject s) {
            Optional<byte[]> preimage = ScriptIntegrity.preimage(s.raw(), s.languagesUsed(), s.ctx().params());
            String detail = mismatch(s, preimage);
            return detail == null ? null : detail + " " + maybe(preimage.orElse(null));
        }
    }

    /** @return the supplied/expected mismatch of the script integrity hash, or null when they agree */
    private static String mismatch(UtxowSubject s, Optional<byte[]> preimage) {
        byte[] computed = preimage.map(ScriptIntegrity::hash).orElse(null);
        byte[] supplied = s.raw().scriptDataHash();
        if (Arrays.equals(computed, supplied)) {
            return null;
        }
        return "Mismatch {mismatchSupplied = " + maybe(supplied) + ", mismatchExpected = " + maybe(computed) + "}";
    }

    private static String nonEmptySet(SortedSet<String> items) {
        return items.isEmpty() ? null : set(items);
    }

    private static String set(Iterable<String> items) {
        return "fromList " + items;
    }

    private static String maybe(byte[] value) {
        return value == null ? "SNothing" : "SJust " + HexUtil.encodeHexString(value);
    }
}
