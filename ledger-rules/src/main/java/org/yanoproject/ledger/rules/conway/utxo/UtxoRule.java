package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.AddressBytes;
import org.yanoproject.ledger.rules.conway.tx.LedgerValue;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxos.UtxosRule;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The Conway {@code UTXO} rule ({@code conwayUtxoTransition}, Conway/Rules/Utxo.hs:211-244): Babbage's
 * {@code babbageUtxoValidation} (Babbage/Rules/Utxo.hs:325-412), then the {@code UTXOS} sub-rule.
 *
 * <p>Every check is a {@code runTest} (dynamic) or {@code runTestOnSignal} (static) predicate, so they all run
 * and their failures accumulate in this order; {@link ConwayPredicate} carries each one's protocol-version
 * gate and label, which {@link TransitionContext#check} applies. The rule reads the pre-certificate state
 * (invariant 5).</p>
 */
public final class UtxoRule {

    /** Haskell's constant overhead of a UTxO entry in the minimum-UTxO formula (Babbage/TxOut.hs:670-689). */
    public static final BigInteger MIN_UTXO_OVERHEAD = BigInteger.valueOf(160);

    private UtxoRule() {
    }

    /**
     * Runs {@code UTXO} (with its {@code UTXOS} sub-rule) as a sub-rule of {@code UTXOW}.
     *
     * @param utxow the enclosing {@code UTXOW} frame
     */
    public static void apply(RuleFrame utxow) {
        RuleFrame utxo = utxow.child(LedgerRuleName.UTXO);
        validate(utxo);
        UtxosRule.apply(utxo);
        utxow.subRule(utxo);
    }

    /** {@code babbageUtxoValidation}, in Haskell's order (Babbage/Rules/Utxo.hs:342-412). */
    static void validate(RuleFrame frame) {
        TransitionContext ctx = frame.context();
        RawTransaction raw = ctx.raw();
        ConwayParams pp = new ConwayParams(ctx.params());
        long slot = ctx.env().currentSlot();

        // :356 disjointRefInputs, PV 9-10 only (Babbage/Rules/Utxo.hs:200-214)
        ctx.check(frame, ConwayPredicate.BABBAGE_NON_DISJOINT_REF_INPUTS, () -> {
            SortedSet<TxInRef> common = new TreeSet<>(raw.inputSet());
            common.retainAll(raw.referenceSet());
            return common.isEmpty() ? null : common.toString();
        });

        // :359 ininterval slot (txvld txb) (Allegra/Rules/Utxo.hs:230-240): lower <= slot < upper
        ctx.check(frame, ConwayPredicate.OUTSIDE_VALIDITY_INTERVAL, () -> {
            BigInteger lower = raw.validityStart();
            BigInteger upper = raw.ttl();
            BigInteger now = BigInteger.valueOf(slot);
            boolean inside = (lower == null || lower.compareTo(now) <= 0) && (upper == null || now.compareTo(upper) < 0);
            return inside ? null : "ValidityInterval {invalidBefore = " + bound(lower) + ", invalidHereafter = "
                    + bound(upper) + "} (SlotNo " + slot + ")";
        });

        // :365 validateOutsideForecast (Alonzo/Rules/Utxo.hs:366-386). Only with redeemers and an upper bound, and
        // the bound is translated with `unsafeLinearExtendEpochInfo slotNo ei` (:377-386; cardano-slotting
        // Cardano/Slotting/EpochInfo/Extend.hs): a bound at or before the current slot uses the ledger's epoch info
        // for that slot, a later one is extended linearly from the current slot's own translation. Both succeed
        // whenever the current slot is translatable, which the validation slot always is. So at the pin the check
        // cannot fail in Conway (catalogue: unreachable). A bound past the forecast horizon is still rejected, but by
        // the script context: Conway's mempool and LEDGERS build it with the unextended epoch info
        // (Shelley/API/Mempool.hs:283-293, Babbage/Rules/Ledgers.hs:126-133), so transValidityInterval fails and
        // UTXOS reports CollectErrors [BadTranslation TimeTranslationPastHorizon] (the phase-2 evaluator's
        // preparation, with the node's ForecastHorizon). Amaru's corpus spells that "OutsideForecast".
        ctx.check(frame, ConwayPredicate.OUTSIDE_FORECAST, () -> null);

        // :368 txins txb ≠ ∅ (static; Shelley/Rules/Utxo.hs:428-435)
        ctx.check(frame, ConwayPredicate.INPUT_SET_EMPTY, () -> raw.inputs().isEmpty() ? "" : null);

        // :371 feesOK (Babbage/Rules/Utxo.hs:168-194): part 1, then with redeemers validateTotalCollateral (parts 3-7)
        feesOk(frame, ctx, raw, pp);

        // :375 (spendInputs ∪ collInputs ∪ refInputs) ⊆ dom utxo (Shelley/Rules/Utxo.hs:461-472)
        ctx.check(frame, ConwayPredicate.BAD_INPUTS, () -> {
            List<TxInRef> bad = raw.allInputs().stream().filter(in -> ctx.utxo(in).isEmpty()).toList();
            return bad.isEmpty() ? null : bad.toString();
        });

        // :378 consumed = produced, with the pre-certificate certState (Shelley/Rules/Utxo.hs:506-522)
        ctx.check(frame, ConwayPredicate.VALUE_NOT_CONSERVED, () -> {
            ValueBalance.Balance balance = ValueBalance.of(ctx);
            return balance.conserved() ? null : "Mismatch {mismatchSupplied = " + balance.consumed()
                    + ", mismatchExpected = " + balance.produced() + "}";
        });

        List<RawOutput> allOutputs = raw.allOutputs();

        // :385 getValue txout ≥ inject ((160 + serSize txout) · coinsPerUTxOByte), pointwise
        // (Babbage/Rules/Utxo.hs:303-323; babbageMinUTxOValue, Babbage/TxOut.hs:665-689)
        ctx.check(frame, ConwayPredicate.BABBAGE_OUTPUT_TOO_SMALL, () -> {
            List<String> tooSmall = new ArrayList<>();
            for (RawOutput out : allOutputs) {
                BigInteger min = minUtxo(pp, out);
                if (out.value().coin().compareTo(min) < 0 || !out.value().assetsNonNegative()) {
                    tooSmall.add(describe(out) + " needs " + min);
                }
            }
            return tooSmall.isEmpty() ? null : String.join("; ", tooSmall);
        });

        // :389 serSize (getValue txout) ≤ maxValSize (Alonzo/Rules/Utxo.hs:412-434)
        ctx.check(frame, ConwayPredicate.OUTPUT_TOO_BIG, () -> {
            List<String> tooBig = new ArrayList<>();
            BigInteger max = pp.maxValSize();
            for (RawOutput out : allOutputs) {
                int size = out.value().serializedSize();
                if (BigInteger.valueOf(size).compareTo(max) > 0) {
                    tooBig.add("(" + size + ", " + max + ", " + describe(out) + ")");
                }
            }
            // Haskell folds with foldl' and prepends, so the list is in reverse output order.
            Collections.reverse(tooBig);
            return tooBig.isEmpty() ? null : String.join("; ", tooBig);
        });

        // :392 bootstrap address attributes ≤ 64 bytes (static; Shelley/Rules/Utxo.hs:544-561)
        ctx.check(frame, ConwayPredicate.OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG, () -> {
            List<String> tooBig = allOutputs.stream()
                    .filter(out -> AddressBytes.isBootstrap(out.address())
                            && AddressBytes.bootstrapAttrsSize(out.address()) > AddressBytes.MAX_BOOTSTRAP_ATTRS_SIZE)
                    .map(UtxoRule::describe)
                    .toList();
            return tooBig.isEmpty() ? null : String.join("; ", tooBig);
        });

        int network = ctx.env().networkId() == NetworkId.MAINNET ? 1 : 0;

        // :397 netId a = NetworkId for every output address (static; Shelley/Rules/Utxo.hs:474-488)
        ctx.check(frame, ConwayPredicate.WRONG_NETWORK, () -> {
            SortedSet<String> wrong = new TreeSet<>();
            for (RawOutput out : allOutputs) {
                if (AddressBytes.network(out.address()) != network) {
                    wrong.add(HexUtil.encodeHexString(out.address()));
                }
            }
            return wrong.isEmpty() ? null : network(network) + " " + wrong;
        });

        // :400 netId a = NetworkId for every withdrawal (static; Shelley/Rules/Utxo.hs:490-504)
        ctx.check(frame, ConwayPredicate.WRONG_NETWORK_WITHDRAWAL, () -> {
            SortedSet<String> wrong = new TreeSet<>();
            for (RawTransaction.Withdrawal w : raw.withdrawals()) {
                if (w.network() != network) {
                    wrong.add(HexUtil.encodeHexString(w.rewardAccount()));
                }
            }
            return wrong.isEmpty() ? null : network(network) + " " + wrong;
        });

        // :403 txnetworkid txb = NetworkId ∨ absent (static; Alonzo/Rules/Utxo.hs:436-449)
        ctx.check(frame, ConwayPredicate.WRONG_NETWORK_IN_TX_BODY, () -> {
            Integer body = raw.networkId();
            return body == null || body == network ? null
                    : "Mismatch {mismatchSupplied = " + network(body) + ", mismatchExpected = " + network(network) + "}";
        });

        // :406 txsize tx ≤ maxTxSize (static; Shelley/Rules/Utxo.hs:563-578; size: RawTransaction#size)
        ctx.check(frame, ConwayPredicate.MAX_TX_SIZE, () -> {
            BigInteger size = BigInteger.valueOf(raw.size());
            BigInteger max = pp.maxTxSize();
            return size.compareTo(max) <= 0 ? null
                    : "Mismatch {mismatchSupplied = " + size + ", mismatchExpected = " + max + "}";
        });

        // :409 totExunits tx ≤ maxTxExUnits, pointwise (Alonzo/Rules/Utxo.hs:451-468)
        ctx.check(frame, ConwayPredicate.EX_UNITS_TOO_BIG, () -> {
            BigInteger mem = MinFee.totalMem(raw);
            BigInteger steps = MinFee.totalSteps(raw);
            boolean fits = mem.compareTo(pp.maxTxExMem()) <= 0 && steps.compareTo(pp.maxTxExSteps()) <= 0;
            return fits ? null : "Mismatch {mismatchSupplied = ExUnits " + mem + " " + steps
                    + ", mismatchExpected = ExUnits " + pp.maxTxExMem() + " " + pp.maxTxExSteps() + "}";
        });

        // :412 ‖collateral tx‖ ≤ maxCollInputs (Alonzo/Rules/Utxo.hs:470-481)
        ctx.check(frame, ConwayPredicate.TOO_MANY_COLLATERAL_INPUTS, () -> {
            BigInteger count = BigInteger.valueOf(raw.collateralSet().size());
            BigInteger max = pp.maxCollateralInputs();
            return count.compareTo(max) <= 0 ? null
                    : "Mismatch {mismatchSupplied = " + count + ", mismatchExpected = " + max + "}";
        });
    }

    /**
     * Babbage {@code feesOK} (Babbage/Rules/Utxo.hs:168-194): part 1 always; parts 3–7
     * ({@code validateTotalCollateral}, :216-245, a {@code sequenceA_} whose failures all accumulate) only when the
     * witness set has redeemers.
     */
    private static void feesOk(RuleFrame frame, TransitionContext ctx, RawTransaction raw, ConwayParams pp) {
        // Part 1: minfee pp tx ≤ txfee txb (getConwayMinFeeTxUtxo)
        ctx.check(frame, ConwayPredicate.FEE_TOO_SMALL, () -> {
            BigInteger minFee = MinFee.of(ctx);
            return minFee.compareTo(raw.fee()) <= 0 ? null
                    : "Mismatch {mismatchSupplied = Coin " + raw.fee() + ", mismatchExpected = Coin " + minFee + "}";
        });
        if (!raw.hasRedeemers()) {
            return;
        }
        // utxoCollateral = collateral ◁ utxo (Map.restrictKeys)
        Map<TxInRef, UtxoEntry> collateral = new LinkedHashMap<>();
        for (TxInRef in : raw.collateralSet()) {
            ctx.utxo(in).ifPresent(entry -> collateral.put(in, entry));
        }
        RawOutput collateralReturn = raw.collateralReturn();

        // Part 3: every collateral input is locked by a key or is a bootstrap address (Alonzo/Rules/Utxo.hs:327-333)
        ctx.check(frame, ConwayPredicate.SCRIPTS_NOT_PAID, () -> {
            List<String> scriptLocked = new ArrayList<>();
            collateral.forEach((in, entry) -> {
                if (!AddressBytes.isVKeyLocked(AddressBytes.fromCcl(entry.output().getAddress()))) {
                    scriptLocked.add(in.toString());
                }
            });
            return scriptLocked.isEmpty() ? null : scriptLocked.toString();
        });

        LedgerValue collateralBalance = LedgerValue.ZERO;
        for (UtxoEntry entry : collateral.values()) {
            collateralBalance = collateralBalance.add(LedgerValue.of(entry.output().getValue()));
        }
        LedgerValue totalBalance = collateralReturn == null ? collateralBalance
                : collateralBalance.subtract(collateralReturn.value());
        boolean utxoOnlyAda = collateralBalance.isAdaOnly();

        // Part 4: all non-ADA is returned (Babbage/Rules/Utxo.hs:253-293)
        LedgerValue reportedBalance = collateralBalance;
        ctx.check(frame, ConwayPredicate.COLLATERAL_CONTAINS_NON_ADA, () -> {
            boolean returnOnlyAda = collateralReturn == null || collateralReturn.value().isAdaOnly();
            boolean onlyAda = (utxoOnlyAda && returnOnlyAda) || totalBalance.isAdaOnly();
            if (onlyAda) {
                return null;
            }
            LedgerValue withNonAda = collateralReturn != null && utxoOnlyAda ? collateralReturn.value()
                    : reportedBalance;
            return withNonAda.toString();
        });

        // collAdaBalance (Babbage/Collateral.hs:30-41): Σ collateral coin − return coin, as DeltaCoin
        BigInteger balance = collateralReturn == null ? collateralBalance.coin()
                : collateralBalance.coin().subtract(collateralReturn.value().coin());
        BigInteger fee = raw.fee();

        // Part 5: 100 · balance ≥ collateralPercent · fee (Alonzo/Rules/Utxo.hs:335-351)
        ctx.check(frame, ConwayPredicate.INSUFFICIENT_COLLATERAL, () -> {
            BigInteger percent = pp.collateralPercent();
            if (balance.multiply(BigInteger.valueOf(100)).compareTo(percent.multiply(fee)) >= 0) {
                return null;
            }
            BigInteger[] qr = percent.multiply(fee).divideAndRemainder(BigInteger.valueOf(100));
            BigInteger required = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
            return "DeltaCoin " + balance + ", Coin " + required;
        });

        // Part 6: (txcoll ≠ ◇) ⇒ balance = txcoll (Babbage/Rules/Utxo.hs:295-301)
        ctx.check(frame, ConwayPredicate.INCORRECT_TOTAL_COLLATERAL_FIELD, () -> {
            BigInteger declared = raw.totalCollateral();
            return declared == null || declared.equals(balance) ? null
                    : "DeltaCoin " + balance + ", Coin " + declared;
        });

        // Part 7: collateral ◁ utxo ≠ ∅
        ctx.check(frame, ConwayPredicate.NO_COLLATERAL_INPUTS, () -> collateral.isEmpty() ? "" : null);
    }

    /** {@code babbageMinUTxOValue}: {@code (160 + sizedSize txOut) · coinsPerUTxOByte}, with the output's original size. */
    public static BigInteger minUtxo(ConwayParams pp, RawOutput out) {
        return MIN_UTXO_OVERHEAD.add(BigInteger.valueOf(out.size())).multiply(pp.coinsPerUtxoByte());
    }

    private static String describe(RawOutput out) {
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
