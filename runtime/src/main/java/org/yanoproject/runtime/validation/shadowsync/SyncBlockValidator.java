package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.tx.RawRedeemer;
import org.yanoproject.ledger.rules.conway.tx.CclTransactions;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.utxo.BlockRefScriptSize;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates the transactions of one applied block against its pre-block state (ADR-056 Phase 7a, shadow sync).
 *
 * <p>Per engine, the transactions are validated in block order with rule {@code LEDGER}, origin {@code SYNC} and no
 * {@code previous} (full validation, Plutus included) over a block-local {@link OverlayLedgerView} on the pre-block
 * view, so each transaction sees the effects of the earlier ones.</p>
 *
 * <h2>Expected outcomes</h2>
 * The block was accepted by the chain, so every transaction in it is a valid ledger transaction:
 * <ul>
 *   <li>not listed in {@code invalid_transactions} ({@code is_valid = true}): {@link Expected#VALID}, a
 *       {@code Valid} outcome whose provenance is phase-2 valid;</li>
 *   <li>listed ({@code is_valid = false}, reassembled so by {@link SyncBlock}): {@link Expected#PHASE2_INVALID}, a
 *       {@code Valid} outcome whose provenance is phase-2 <em>invalid</em>. Under origin {@code SYNC} the engines run
 *       Haskell's {@code UTXOS} for {@code IsValid False}: every phase-1 rule, then the scripts must fail (a script
 *       that passes is {@code UTXOS.ValidationTagMismatch}); the effects are collateral only.</li>
 * </ul>
 * An outcome that is a ledger-rule rejection (any failure outside {@link LedgerRuleName#ENGINE}), or the wrong
 * phase-2 verdict, is a {@link Kind#DISAGREED disagreement}: the engine would have rejected a chain-valid block. An
 * outcome with only {@code ENGINE} failures (unavailable state, a decoding failure, an unsupported era or context, an
 * engine error) is an {@link Kind#ENGINE_FAILURE}: no verdict.
 *
 * <h2>After a finding</h2>
 * The chain applied the transaction, so its effects are derived anyway ({@link TxEffectsDeriver}, with the chain's
 * phase-2 verdict) and applied to the overlay, and later transactions still see a correct state. When even that
 * fails the overlay is <em>tainted</em>: later findings of the same engine are reported as engine failures
 * ({@code overlayTainted}), not disagreements.
 *
 * <h2>Block rules ({@code BBODY}, cardano-ledger {@code f649f975}), reported separately from the transactions</h2>
 * <ul>
 *   <li>{@code WrongBlockBodySizeBBODY}, {@code InvalidBodyHashBBODY} (Shelley/Rules/Bbody.hs, called first by Alonzo
 *       {@code alonzoBbodyTransition}): computed by {@link SyncBlock} from the stored body segments. Yano's sync path
 *       does not otherwise check them (header validation only compares the header's fields).</li>
 *   <li>{@code TooManyExUnits} (Alonzo/Rules/Bbody.hs {@code validateExUnits}, after {@code LEDGERS}): the point-wise
 *       sum of every transaction's redeemer budgets, phase-2-invalid ones included, at most the pre-block
 *       {@code maxBlockExUnits}.</li>
 *   <li>{@code BodyRefScriptsSizeTooBig} (below).</li>
 *   <li>Out of scope: {@code HeaderProtVerTooHigh} (Conway/Rules/Bbody.hs, a header check, mainnet only below PV 12)
 *       and the block-count bookkeeping ({@code incrBlocks}), which is canonical ledger state, not a verdict.</li>
 * </ul>
 * {@code BBODY.BodyRefScriptsSizeTooBig} (Conway/Rules/Bbody.hs:342-371): the sum
 * over the block's transactions of {@code txNonDistinctRefScriptsSize} (reference scripts of spending ∪ reference
 * inputs, Conway/UTxO.hs:166-170) must not exceed {@code maxRefScriptSizePerBlock} (1 MiB). At protocol version 10 and
 * below every transaction is measured against the pre-block UTxO; from 11 against the pre-block UTxO plus the
 * outputs of the earlier transactions of the block ({@code txouts} of a phase-2-valid one, {@code collOuts} of an
 * invalid one; spent entries are not removed).
 */
public final class SyncBlockValidator {

    /** What the chain says about a transaction. */
    public enum Expected {
        VALID,
        PHASE2_INVALID
    }

    /** How an engine's outcome compares with the chain. */
    public enum Kind {
        AGREED,
        DISAGREED,
        ENGINE_FAILURE
    }

    /**
     * One transaction's result for one engine.
     *
     * @param index          position in the block
     * @param txHash         transaction id
     * @param expected       the chain's verdict
     * @param kind           the comparison
     * @param actual         {@code VALID}, {@code PHASE2_INVALID}, or the first failure's {@code RULE.Constructor}
     * @param failures       the engine's failures (empty unless invalid)
     * @param overlayTainted an earlier transaction's effects could not be applied to the overlay
     * @param dump           the replay bundle written for this finding, or {@code null}
     */
    public record TxResult(int index, String txHash, Expected expected, Kind kind, String actual,
                           List<LedgerFailure> failures, boolean overlayTainted, Path dump) {
        public TxResult {
            failures = List.copyOf(failures);
        }
    }

    /** One engine's results for the block, in block order. */
    public record EngineResult(String engine, List<TxResult> txs) {
        public EngineResult {
            txs = List.copyOf(txs);
        }
    }

    /**
     * The block's reference-script size check.
     *
     * @param totalSize         {@code totalRefScriptSizeInBlock}
     * @param limit             {@code maxRefScriptSizePerBlock}
     * @param unavailableReason why the check could not run, or {@code null}
     */
    public record RefScriptCheck(long totalSize, long limit, String unavailableReason) {
        public boolean checked() {
            return unavailableReason == null;
        }

        public boolean violated() {
            return checked() && totalSize > limit;
        }
    }

    /**
     * The block's {@code TooManyExUnits} check (Alonzo/Rules/Bbody.hs {@code validateExUnits}): the point-wise sum of
     * every transaction's redeemer budgets ({@code totExUnits}, phase-2-invalid transactions included: the fold is
     * over the whole transaction sequence) must not exceed the pre-block {@code maxBlockExUnits}.
     *
     * @param unavailableReason why the check could not run, or {@code null}
     */
    public record ExUnitsCheck(BigInteger mem, BigInteger steps, BigInteger maxMem, BigInteger maxSteps,
                               String unavailableReason) {
        public boolean checked() {
            return unavailableReason == null;
        }

        public boolean violated() {
            return checked() && (mem.compareTo(maxMem) > 0 || steps.compareTo(maxSteps) > 0);
        }
    }

    /**
     * The block's results.
     *
     * @param protocolMajor the protocol major version of the pre-block state
     * @param engines       per engine
     * @param refScripts    {@code BodyRefScriptsSizeTooBig}
     * @param exUnits       {@code TooManyExUnits}
     * @param body          {@code WrongBlockBodySizeBBODY} / {@code InvalidBodyHashBBODY}, or {@code null} when the
     *                      block's header could not be read
     */
    public record BlockResult(int protocolMajor, List<EngineResult> engines, RefScriptCheck refScripts,
                              ExUnitsCheck exUnits, SyncBlock.BodyDigest body) {
        public BlockResult {
            engines = List.copyOf(engines);
        }
    }

    /** Writes a replay bundle for a finding. */
    @FunctionalInterface
    public interface Dumper {
        /** @return the written file, or {@code null} when nothing was written */
        Path write(ShadowDumpBundle bundle);

        /**
         * @return whether a bundle would be written now; checked before the engine is re-run over a recording, so a
         *         dumper that is full costs nothing
         */
        default boolean accepts() {
            return true;
        }
    }

    /** The engine name the chain's recorded outcome carries in a replay bundle. */
    public static final String CHAIN = "chain";
    /** Engine constructor for an engine that threw instead of answering. */
    public static final String ENGINE_THREW = "EngineThrew";
    /** Engine constructor for a finding after the overlay lost a transaction's effects. */
    public static final String OVERLAY_TAINTED = "OverlayTainted";

    private final TxEffectsDeriver deriver = new TxEffectsDeriver();
    private final long maxRefScriptSizePerBlock;

    public SyncBlockValidator() {
        this(ConwayLedgerConstants.HASKELL.maxRefScriptSizePerBlock());
    }

    SyncBlockValidator(long maxRefScriptSizePerBlock) {
        this.maxRefScriptSizePerBlock = maxRefScriptSizePerBlock;
    }

    /**
     * @param block   the block's transactions
     * @param base    the pre-block state (not modified)
     * @param env     the environment: the block's slot and epoch, the pre-block protocol version, forecast basis
     *                {@code next(parent)}
     * @param engines the engines, each with its own overlay
     * @param dumper  writes bundles for findings, or {@code null}
     */
    public BlockResult validate(SyncBlock block, LedgerView base, ValidationEnv env,
                                List<LedgerValidationEngine> engines, Dumper dumper) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(env, "env");
        List<RawTransaction> decoded = decodeAll(block);
        List<EngineResult> results = new ArrayList<>();
        for (LedgerValidationEngine engine : engines) {
            results.add(validateWith(engine, block, decoded, base, env, dumper));
        }
        return new BlockResult(env.protocolMajor(), results, refScripts(block, decoded, base, env.protocolMajor()),
                exUnits(block, decoded, base), block.body());
    }

    private EngineResult validateWith(LedgerValidationEngine engine, SyncBlock block, List<RawTransaction> decoded,
                                      LedgerView base, ValidationEnv env, Dumper dumper) {
        OverlayLedgerView overlay = OverlayLedgerView.over(base);
        boolean tainted = false;
        List<TxResult> txs = new ArrayList<>(block.size());
        for (int i = 0; i < block.size(); i++) {
            byte[] tx = block.txs().get(i);
            String id = block.txIds().get(i);
            Expected expected = block.phase2Invalid(i) ? Expected.PHASE2_INVALID : Expected.VALID;
            TxValidationOutcome outcome = validate(engine, tx, overlay, env);
            Kind kind = classify(expected, outcome);
            if (kind == Kind.DISAGREED && tainted) {
                kind = Kind.ENGINE_FAILURE;
            }
            Path dump = null;
            if (kind != Kind.AGREED && dumper != null) {
                dump = dump(engine, tx, id, overlay, env, dumper);
            }
            txs.add(new TxResult(i, id, expected, kind, actual(outcome), failures(outcome, tainted), tainted, dump));
            // Advance the overlay by what the chain applied.
            TxEffects effects = outcome instanceof TxValidationOutcome.Valid valid && kind == Kind.AGREED
                    ? valid.effects()
                    : chainEffects(decoded.get(i), overlay, env, expected == Expected.VALID);
            if (effects == null) {
                tainted = true;
                continue;
            }
            try {
                overlay = overlay.apply(effects);
            } catch (RuntimeException e) {
                tainted = true;
            }
        }
        return new EngineResult(engine.name(), txs);
    }

    private static TxValidationOutcome validate(LedgerValidationEngine engine, byte[] tx, LedgerView view,
                                                ValidationEnv env) {
        try {
            return engine.validate(new TxValidationRequest(tx, view, env, TxValidationRequest.Rule.LEDGER,
                    TxValidationRequest.Origin.SYNC, null));
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            // A deeply nested transaction can overflow a recursive decoder (preprod block 5183974): an engine
            // failure for this transaction, not a lost block.
            return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE, ENGINE_THREW,
                    LedgerFailure.Phase.PHASE_1, e.toString()));
        }
    }

    /** Compares an engine's outcome with the chain's verdict. */
    public static Kind classify(Expected expected, TxValidationOutcome outcome) {
        return switch (outcome) {
            case TxValidationOutcome.Valid valid -> {
                boolean phase2Valid = valid.validated().phase2Valid();
                yield (expected == Expected.VALID) == phase2Valid ? Kind.AGREED : Kind.DISAGREED;
            }
            case TxValidationOutcome.Invalid invalid ->
                    invalid.failures().stream().allMatch(f -> f.rule() == LedgerRuleName.ENGINE)
                            ? Kind.ENGINE_FAILURE : Kind.DISAGREED;
        };
    }

    private static String actual(TxValidationOutcome outcome) {
        return switch (outcome) {
            case TxValidationOutcome.Valid valid -> valid.validated().phase2Valid() ? "VALID" : "PHASE2_INVALID";
            case TxValidationOutcome.Invalid invalid -> invalid.failures().getFirst().qualifiedName();
        };
    }

    private static List<LedgerFailure> failures(TxValidationOutcome outcome, boolean tainted) {
        List<LedgerFailure> failures = new ArrayList<>();
        if (outcome instanceof TxValidationOutcome.Invalid invalid) {
            failures.addAll(invalid.failures());
        }
        if (tainted && !failures.isEmpty()) {
            failures.add(new LedgerFailure(LedgerRuleName.ENGINE, OVERLAY_TAINTED, LedgerFailure.Phase.PHASE_1,
                    "an earlier transaction's effects could not be applied to the block overlay"));
        }
        return failures;
    }

    private TxEffects chainEffects(RawTransaction tx, LedgerView overlay, ValidationEnv env, boolean phase2Valid) {
        if (tx == null) {
            return null;
        }
        try {
            return deriver.derive(tx, overlay, env, phase2Valid);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static Path dump(LedgerValidationEngine engine, byte[] tx, String id, LedgerView overlay,
                             ValidationEnv env, Dumper dumper) {
        if (!dumper.accepts()) {
            return null;
        }
        try {
            // The overlay is immutable: re-running the engine over a recording of it sees the same state.
            RecordingLedgerView recording = new RecordingLedgerView(overlay);
            TxValidationOutcome replayed = validate(engine, tx, recording, env);
            ShadowDumpBundle bundle = new ShadowDumpBundle(id, tx, TxValidationRequest.Rule.LEDGER,
                    TxValidationRequest.Origin.SYNC, env, new RecordedOutcome(CHAIN, true, List.of()),
                    RecordedOutcome.of(engine.name(), replayed), recording.reads());
            return dumper.write(bundle);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** Each transaction read once from its bytes, for the chain's effects and the block checks; null when it does not. */
    private static List<RawTransaction> decodeAll(SyncBlock block) {
        List<RawTransaction> decoded = new ArrayList<>(block.size());
        for (byte[] tx : block.txs()) {
            RawTransaction t;
            try {
                t = RawTransaction.parse(tx, CclTransactions.deserialize(tx));
            } catch (Exception | StackOverflowError e) {
                t = null;
            }
            decoded.add(t);
        }
        return decoded;
    }

    /** {@code totalRefScriptSizeInBlock} (Bbody.hs:357-371) against {@code base}, the pre-block UTxO. */
    RefScriptCheck refScripts(SyncBlock block, List<RawTransaction> decoded, LedgerView base, int protocolMajor) {
        BlockRefScriptSize size = BlockRefScriptSize.over(base, protocolMajor);
        for (int i = 0; i < block.size(); i++) {
            Transaction tx = decoded.get(i) != null ? decoded.get(i).decoded() : null;
            if (tx == null || tx.getBody() == null) {
                return new RefScriptCheck(size.total(), maxRefScriptSizePerBlock,
                        "transaction " + block.txIds().get(i) + " does not decode");
            }
            TransactionBody body = tx.getBody();
            Lookup<Long> txSize = size.measure(body);
            if (txSize instanceof Lookup.Unavailable<Long> u) {
                return new RefScriptCheck(size.total(), maxRefScriptSizePerBlock, u.reason());
            }
            size.add(block.txIds().get(i), body, !block.phase2Invalid(i), ((Lookup.Present<Long>) txSize).value());
        }
        return new RefScriptCheck(size.total(), maxRefScriptSizePerBlock, null);
    }

    /** {@code validateExUnits} (Alonzo/Rules/Bbody.hs) against the pre-block {@code maxBlockExUnits}. */
    static ExUnitsCheck exUnits(SyncBlock block, List<RawTransaction> decoded, LedgerView base) {
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (int i = 0; i < block.size(); i++) {
            RawTransaction tx = decoded.get(i);
            if (tx == null) {
                return new ExUnitsCheck(mem, steps, null, null,
                        "transaction " + block.txIds().get(i) + " does not decode");
            }
            // totExUnits sums the Redeemers map (Alonzo/Tx.hs:464): read as Haskell holds it, so a duplicate
            // (tag, index) counts once, as RawTransaction#redeemers resolves it.
            for (RawRedeemer redeemer : tx.redeemers()) {
                mem = mem.add(redeemer.mem());
                steps = steps.add(redeemer.steps());
            }
        }
        Lookup<ProtocolParams> params = base.protocolParams();
        if (!(params instanceof Lookup.Present<ProtocolParams> present)) {
            return new ExUnitsCheck(mem, steps, null, null, "no pre-block protocol parameters");
        }
        String maxMem = present.value().getMaxBlockExMem();
        String maxSteps = present.value().getMaxBlockExSteps();
        if (maxMem == null || maxSteps == null) {
            return new ExUnitsCheck(mem, steps, null, null, "maxBlockExUnits is not in the protocol parameters");
        }
        return new ExUnitsCheck(mem, steps, new BigInteger(maxMem.trim()), new BigInteger(maxSteps.trim()), null);
    }
}
