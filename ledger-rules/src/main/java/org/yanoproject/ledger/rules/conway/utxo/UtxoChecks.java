package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.PredicateCheck;
import org.yanoproject.ledger.rules.conway.tx.AddressBytes;
import org.yanoproject.ledger.rules.conway.tx.LedgerValue;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxo.UtxoSubject.Collateral;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The {@code UTXO} checks ({@code babbageUtxoValidation}, Babbage/Rules/Utxo.hs:325-412), each a unit of
 * {@code ConwayScopes.UTXO}. Every one is a {@code runTest} (dynamic) or {@code runTestOnSignal} (static) predicate,
 * so they all run and their failures accumulate in the rule set's order. They read the pre-certificate state
 * (invariant 5).
 */
public final class UtxoChecks {

    private UtxoChecks() {
    }

    /** :356 {@code disjointRefInputs} (Babbage/Rules/Utxo.hs:200-214): no input is also a reference input. */
    public static final class NonDisjointRefInputs extends PredicateCheck<UtxoSubject> {

        public NonDisjointRefInputs() {
            super(ConwayPredicate.BABBAGE_NON_DISJOINT_REF_INPUTS);
        }

        @Override
        protected String detail(UtxoSubject s) {
            SortedSet<TxInRef> common = new TreeSet<>(s.raw().inputSet());
            common.retainAll(s.raw().referenceSet());
            return common.isEmpty() ? null : common.toString();
        }
    }

    /** :359 {@code ininterval slot (txvld txb)} (Allegra/Rules/Utxo.hs:230-240): {@code lower <= slot < upper}. */
    public static final class OutsideValidityInterval extends PredicateCheck<UtxoSubject> {

        public OutsideValidityInterval() {
            super(ConwayPredicate.OUTSIDE_VALIDITY_INTERVAL);
        }

        @Override
        protected String detail(UtxoSubject s) {
            BigInteger lower = s.raw().validityStart();
            BigInteger upper = s.raw().ttl();
            BigInteger now = BigInteger.valueOf(s.slot());
            boolean inside = (lower == null || lower.compareTo(now) <= 0) && (upper == null || now.compareTo(upper) < 0);
            return inside ? null : "ValidityInterval {invalidBefore = " + bound(lower) + ", invalidHereafter = "
                    + bound(upper) + "} (SlotNo " + s.slot() + ")";
        }
    }

    /**
     * :365 {@code validateOutsideForecast} (Alonzo/Rules/Utxo.hs:366-386). Only with redeemers and an upper bound, and
     * the bound is translated with {@code unsafeLinearExtendEpochInfo slotNo ei} (:377-386; cardano-slotting
     * Cardano/Slotting/EpochInfo/Extend.hs): a bound at or before the current slot uses the ledger's epoch info for that
     * slot, a later one is extended linearly from the current slot's own translation. Both succeed whenever the current
     * slot is translatable, which the validation slot always is. So at the pin the check cannot fail in Conway
     * (catalogue: unreachable). A bound past the forecast horizon is still rejected, but by the script context: Conway's
     * mempool and LEDGERS build it with the unextended epoch info (Shelley/API/Mempool.hs:283-293,
     * Babbage/Rules/Ledgers.hs:126-133), so transValidityInterval fails and UTXOS reports CollectErrors [BadTranslation
     * TimeTranslationPastHorizon] (the phase-2 evaluator's preparation, with the node's ForecastHorizon). Amaru's corpus
     * spells that "OutsideForecast".
     */
    public static final class OutsideForecast extends PredicateCheck<UtxoSubject> {

        public OutsideForecast() {
            super(ConwayPredicate.OUTSIDE_FORECAST);
        }

        @Override
        protected String detail(UtxoSubject s) {
            return null;
        }
    }

    /** :368 {@code txins txb ≠ ∅} (static; Shelley/Rules/Utxo.hs:428-435). */
    public static final class InputSetEmpty extends PredicateCheck<UtxoSubject> {

        public InputSetEmpty() {
            super(ConwayPredicate.INPUT_SET_EMPTY);
        }

        @Override
        protected String detail(UtxoSubject s) {
            return s.raw().inputs().isEmpty() ? "" : null;
        }
    }

    /** :371 {@code feesOK} part 1 (Babbage/Rules/Utxo.hs:168-194): {@code minfee pp tx ≤ txfee txb}. */
    public static final class FeeTooSmall extends PredicateCheck<UtxoSubject> {

        public FeeTooSmall() {
            super(ConwayPredicate.FEE_TOO_SMALL);
        }

        @Override
        protected String detail(UtxoSubject s) {
            BigInteger minFee = MinFee.of(s.ctx());
            return minFee.compareTo(s.raw().fee()) <= 0 ? null
                    : "Mismatch {mismatchSupplied = Coin " + s.raw().fee() + ", mismatchExpected = Coin " + minFee + "}";
        }
    }

    /**
     * A {@code feesOK} collateral check ({@code validateTotalCollateral}, parts 3–7, Babbage/Rules/Utxo.hs:216-245, a
     * {@code sequenceA_} whose failures all accumulate): it runs only when the witness set has redeemers.
     */
    private abstract static class CollateralCheck extends PredicateCheck<UtxoSubject> {

        CollateralCheck(ConwayPredicate predicate) {
            super(predicate);
        }

        abstract String detail(UtxoSubject s, Collateral collateral);

        @Override
        protected final String detail(UtxoSubject s) {
            return s.raw().hasRedeemers() ? detail(s, s.collateral()) : null;
        }
    }

    /** Part 3: every collateral input is locked by a key or is a bootstrap address (Alonzo/Rules/Utxo.hs:327-333). */
    public static final class ScriptsNotPaid extends CollateralCheck {

        public ScriptsNotPaid() {
            super(ConwayPredicate.SCRIPTS_NOT_PAID);
        }

        @Override
        String detail(UtxoSubject s, Collateral collateral) {
            List<String> scriptLocked = new ArrayList<>();
            collateral.inputs().forEach((in, entry) -> {
                if (!AddressBytes.isVKeyLocked(AddressBytes.fromCcl(entry.output().getAddress()))) {
                    scriptLocked.add(in.toString());
                }
            });
            return scriptLocked.isEmpty() ? null : scriptLocked.toString();
        }
    }

    /** Part 4: all non-ADA is returned (Babbage/Rules/Utxo.hs:253-293). */
    public static final class CollateralContainsNonAda extends CollateralCheck {

        public CollateralContainsNonAda() {
            super(ConwayPredicate.COLLATERAL_CONTAINS_NON_ADA);
        }

        @Override
        String detail(UtxoSubject s, Collateral collateral) {
            RawOutput returned = collateral.returned();
            boolean utxoOnlyAda = collateral.balance().isAdaOnly();
            boolean returnOnlyAda = returned == null || returned.value().isAdaOnly();
            boolean onlyAda = (utxoOnlyAda && returnOnlyAda) || collateral.total().isAdaOnly();
            if (onlyAda) {
                return null;
            }
            LedgerValue withNonAda = returned != null && utxoOnlyAda ? returned.value() : collateral.balance();
            return withNonAda.toString();
        }
    }

    /** Part 5: {@code 100 · balance ≥ collateralPercent · fee} (Alonzo/Rules/Utxo.hs:335-351). */
    public static final class InsufficientCollateral extends CollateralCheck {

        public InsufficientCollateral() {
            super(ConwayPredicate.INSUFFICIENT_COLLATERAL);
        }

        @Override
        String detail(UtxoSubject s, Collateral collateral) {
            BigInteger balance = collateral.adaBalance();
            BigInteger fee = s.raw().fee();
            BigInteger percent = s.pp().collateralPercent();
            if (balance.multiply(BigInteger.valueOf(100)).compareTo(percent.multiply(fee)) >= 0) {
                return null;
            }
            BigInteger[] qr = percent.multiply(fee).divideAndRemainder(BigInteger.valueOf(100));
            BigInteger required = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
            return "DeltaCoin " + balance + ", Coin " + required;
        }
    }

    /** Part 6: {@code (txcoll ≠ ◇) ⇒ balance = txcoll} (Babbage/Rules/Utxo.hs:295-301). */
    public static final class IncorrectTotalCollateralField extends CollateralCheck {

        public IncorrectTotalCollateralField() {
            super(ConwayPredicate.INCORRECT_TOTAL_COLLATERAL_FIELD);
        }

        @Override
        String detail(UtxoSubject s, Collateral collateral) {
            BigInteger declared = s.raw().totalCollateral();
            BigInteger balance = collateral.adaBalance();
            return declared == null || declared.equals(balance) ? null : "DeltaCoin " + balance + ", Coin " + declared;
        }
    }

    /** Part 7: {@code collateral ◁ utxo ≠ ∅}. */
    public static final class NoCollateralInputs extends CollateralCheck {

        public NoCollateralInputs() {
            super(ConwayPredicate.NO_COLLATERAL_INPUTS);
        }

        @Override
        String detail(UtxoSubject s, Collateral collateral) {
            return collateral.inputs().isEmpty() ? "" : null;
        }
    }

    /** :375 {@code (spendInputs ∪ collInputs ∪ refInputs) ⊆ dom utxo} (Shelley/Rules/Utxo.hs:461-472). */
    public static final class BadInputs extends PredicateCheck<UtxoSubject> {

        public BadInputs() {
            super(ConwayPredicate.BAD_INPUTS);
        }

        @Override
        protected String detail(UtxoSubject s) {
            List<TxInRef> bad = s.raw().allInputs().stream().filter(in -> s.ctx().utxo(in).isEmpty()).toList();
            return bad.isEmpty() ? null : bad.toString();
        }
    }

    /** :378 {@code consumed = produced}, with the pre-certificate certState (Shelley/Rules/Utxo.hs:506-522). */
    public static final class ValueNotConserved extends PredicateCheck<UtxoSubject> {

        public ValueNotConserved() {
            super(ConwayPredicate.VALUE_NOT_CONSERVED);
        }

        @Override
        protected String detail(UtxoSubject s) {
            ValueBalance.Balance balance = ValueBalance.of(s.ctx());
            return balance.conserved() ? null : "Mismatch {mismatchSupplied = " + balance.consumed()
                    + ", mismatchExpected = " + balance.produced() + "}";
        }
    }

    /**
     * :385 {@code getValue txout ≥ inject ((160 + serSize txout) · coinsPerUTxOByte)}, pointwise
     * (Babbage/Rules/Utxo.hs:303-323; {@code babbageMinUTxOValue}, Babbage/TxOut.hs:665-689).
     */
    public static final class OutputTooSmall extends PredicateCheck<UtxoSubject> {

        public OutputTooSmall() {
            super(ConwayPredicate.BABBAGE_OUTPUT_TOO_SMALL);
        }

        @Override
        protected String detail(UtxoSubject s) {
            List<String> tooSmall = new ArrayList<>();
            for (RawOutput out : s.allOutputs()) {
                BigInteger min = UtxoRule.minUtxo(s.pp(), out);
                if (out.value().coin().compareTo(min) < 0 || !out.value().assetsNonNegative()) {
                    tooSmall.add(describe(out) + " needs " + min);
                }
            }
            return tooSmall.isEmpty() ? null : String.join("; ", tooSmall);
        }
    }

    /** :389 {@code serSize (getValue txout) ≤ maxValSize} (Alonzo/Rules/Utxo.hs:412-434). */
    public static final class OutputTooBig extends PredicateCheck<UtxoSubject> {

        public OutputTooBig() {
            super(ConwayPredicate.OUTPUT_TOO_BIG);
        }

        @Override
        protected String detail(UtxoSubject s) {
            List<String> tooBig = new ArrayList<>();
            BigInteger max = s.pp().maxValSize();
            for (RawOutput out : s.allOutputs()) {
                int size = out.value().serializedSize();
                if (BigInteger.valueOf(size).compareTo(max) > 0) {
                    tooBig.add("(" + size + ", " + max + ", " + describe(out) + ")");
                }
            }
            // Haskell folds with foldl' and prepends, so the list is in reverse output order.
            Collections.reverse(tooBig);
            return tooBig.isEmpty() ? null : String.join("; ", tooBig);
        }
    }

    /** :392 bootstrap address attributes ≤ 64 bytes (static; Shelley/Rules/Utxo.hs:544-561). */
    public static final class OutputBootAddrAttrsTooBig extends PredicateCheck<UtxoSubject> {

        public OutputBootAddrAttrsTooBig() {
            super(ConwayPredicate.OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG);
        }

        @Override
        protected String detail(UtxoSubject s) {
            List<String> tooBig = s.allOutputs().stream()
                    .filter(out -> AddressBytes.isBootstrap(out.address())
                            && AddressBytes.bootstrapAttrsSize(out.address()) > AddressBytes.MAX_BOOTSTRAP_ATTRS_SIZE)
                    .map(UtxoChecks::describe)
                    .toList();
            return tooBig.isEmpty() ? null : String.join("; ", tooBig);
        }
    }

    /** :397 {@code netId a = NetworkId} for every output address (static; Shelley/Rules/Utxo.hs:474-488). */
    public static final class WrongNetwork extends PredicateCheck<UtxoSubject> {

        public WrongNetwork() {
            super(ConwayPredicate.WRONG_NETWORK);
        }

        @Override
        protected String detail(UtxoSubject s) {
            SortedSet<String> wrong = new TreeSet<>();
            for (RawOutput out : s.allOutputs()) {
                if (AddressBytes.network(out.address()) != s.network()) {
                    wrong.add(HexUtil.encodeHexString(out.address()));
                }
            }
            return wrong.isEmpty() ? null : network(s.network()) + " " + wrong;
        }
    }

    /** :400 {@code netId a = NetworkId} for every withdrawal (static; Shelley/Rules/Utxo.hs:490-504). */
    public static final class WrongNetworkWithdrawal extends PredicateCheck<UtxoSubject> {

        public WrongNetworkWithdrawal() {
            super(ConwayPredicate.WRONG_NETWORK_WITHDRAWAL);
        }

        @Override
        protected String detail(UtxoSubject s) {
            SortedSet<String> wrong = new TreeSet<>();
            for (RawTransaction.Withdrawal w : s.raw().withdrawals()) {
                if (w.network() != s.network()) {
                    wrong.add(HexUtil.encodeHexString(w.rewardAccount()));
                }
            }
            return wrong.isEmpty() ? null : network(s.network()) + " " + wrong;
        }
    }

    /** :403 {@code txnetworkid txb = NetworkId ∨ absent} (static; Alonzo/Rules/Utxo.hs:436-449). */
    public static final class WrongNetworkInTxBody extends PredicateCheck<UtxoSubject> {

        public WrongNetworkInTxBody() {
            super(ConwayPredicate.WRONG_NETWORK_IN_TX_BODY);
        }

        @Override
        protected String detail(UtxoSubject s) {
            Integer body = s.raw().networkId();
            return body == null || body == s.network() ? null
                    : "Mismatch {mismatchSupplied = " + network(body) + ", mismatchExpected = " + network(s.network())
                    + "}";
        }
    }

    /** :406 {@code txsize tx ≤ maxTxSize} (static; Shelley/Rules/Utxo.hs:563-578; size: RawTransaction#size). */
    public static final class MaxTxSize extends PredicateCheck<UtxoSubject> {

        public MaxTxSize() {
            super(ConwayPredicate.MAX_TX_SIZE);
        }

        @Override
        protected String detail(UtxoSubject s) {
            BigInteger size = BigInteger.valueOf(s.raw().size());
            BigInteger max = s.pp().maxTxSize();
            return size.compareTo(max) <= 0 ? null
                    : "Mismatch {mismatchSupplied = " + size + ", mismatchExpected = " + max + "}";
        }
    }

    /** :409 {@code totExunits tx ≤ maxTxExUnits}, pointwise (Alonzo/Rules/Utxo.hs:451-468). */
    public static final class ExUnitsTooBig extends PredicateCheck<UtxoSubject> {

        public ExUnitsTooBig() {
            super(ConwayPredicate.EX_UNITS_TOO_BIG);
        }

        @Override
        protected String detail(UtxoSubject s) {
            BigInteger mem = MinFee.totalMem(s.raw());
            BigInteger steps = MinFee.totalSteps(s.raw());
            boolean fits = mem.compareTo(s.pp().maxTxExMem()) <= 0 && steps.compareTo(s.pp().maxTxExSteps()) <= 0;
            return fits ? null : "Mismatch {mismatchSupplied = ExUnits " + mem + " " + steps
                    + ", mismatchExpected = ExUnits " + s.pp().maxTxExMem() + " " + s.pp().maxTxExSteps() + "}";
        }
    }

    /** :412 {@code ‖collateral tx‖ ≤ maxCollInputs} (Alonzo/Rules/Utxo.hs:470-481). */
    public static final class TooManyCollateralInputs extends PredicateCheck<UtxoSubject> {

        public TooManyCollateralInputs() {
            super(ConwayPredicate.TOO_MANY_COLLATERAL_INPUTS);
        }

        @Override
        protected String detail(UtxoSubject s) {
            BigInteger count = BigInteger.valueOf(s.raw().collateralSet().size());
            BigInteger max = s.pp().maxCollateralInputs();
            return count.compareTo(max) <= 0 ? null
                    : "Mismatch {mismatchSupplied = " + count + ", mismatchExpected = " + max + "}";
        }
    }

    static String describe(RawOutput out) {
        return (out.collateralReturn() ? "collateral return" : "output " + out.index()) + " "
                + HexUtil.encodeHexString(out.address()) + " " + out.value();
    }

    private static String bound(BigInteger slot) {
        return slot == null ? "SNothing" : "SJust (SlotNo " + slot + ")";
    }

    private static String network(int id) {
        return id == 1 ? "Mainnet" : "Testnet";
    }
}
