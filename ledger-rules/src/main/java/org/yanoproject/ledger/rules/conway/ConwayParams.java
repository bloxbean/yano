package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Typed access to the epoch-effective protocol parameters the Conway rules read. CCL's
 * {@link ProtocolParams} keeps some values as strings and others as boxed numbers; a missing value is a broken
 * view, not ledger data, so it throws {@link IllegalStateException} and the engine fails closed.
 *
 * <p><b>Rationals.</b> Haskell keeps the ExUnits prices and {@code minFeeRefScriptCostPerByte} as exact
 * rationals ({@code NonNegativeInterval}). CCL carries them as {@link BigDecimal}, which the fee rules then use
 * exactly ({@code MinFee}'s fractions). That is exact for every value that has a terminating decimal expansion
 * — all values on mainnet, preprod and preview and in the conformance fixtures (for example {@code 577/10000},
 * {@code 721/10000000}, {@code 15}, {@code 44}). A rational such as {@code 1/3} cannot be carried by the view in
 * this form; supporting one needs numerator/denominator in the view's parameters (precondition, recorded in
 * ADR-056 Phase 3a results).</p>
 */
public final class ConwayParams {

    private final ProtocolParams params;

    public ConwayParams(ProtocolParams params) {
        this.params = Objects.requireNonNull(params, "params");
    }

    /** {@code ppTxFeePerByte} ({@code minfee_a}) */
    public BigInteger minFeeA() {
        return integer(params.getMinFeeA(), "minFeeA");
    }

    /** {@code ppTxFeeFixed} ({@code minfee_b}) */
    public BigInteger minFeeB() {
        return integer(params.getMinFeeB(), "minFeeB");
    }

    public BigInteger maxTxSize() {
        return integer(params.getMaxTxSize(), "maxTxSize");
    }

    public BigInteger maxValSize() {
        return integer(params.getMaxValSize(), "maxValSize");
    }

    public BigInteger coinsPerUtxoByte() {
        return integer(params.getCoinsPerUtxoSize(), "coinsPerUtxoSize");
    }

    public BigInteger collateralPercent() {
        return integer(params.getCollateralPercent(), "collateralPercent");
    }

    public BigInteger maxCollateralInputs() {
        return integer(params.getMaxCollateralInputs(), "maxCollateralInputs");
    }

    public BigDecimal priceMem() {
        return decimal(params.getPriceMem(), "priceMem");
    }

    public BigDecimal priceSteps() {
        return decimal(params.getPriceStep(), "priceStep");
    }

    public BigInteger maxTxExMem() {
        return integer(params.getMaxTxExMem(), "maxTxExMem");
    }

    public BigInteger maxTxExSteps() {
        return integer(params.getMaxTxExSteps(), "maxTxExSteps");
    }

    /** {@code ppMinFeeRefScriptCostPerByte} */
    public BigDecimal minFeeRefScriptCostPerByte() {
        return decimal(params.getMinFeeRefScriptCostPerByte(), "minFeeRefScriptCostPerByte");
    }

    public BigInteger keyDeposit() {
        return integer(params.getKeyDeposit(), "keyDeposit");
    }

    public BigInteger poolDeposit() {
        return integer(params.getPoolDeposit(), "poolDeposit");
    }

    public BigInteger drepDeposit() {
        return integer(params.getDrepDeposit(), "drepDeposit");
    }

    public BigInteger govActionDeposit() {
        return integer(params.getGovActionDeposit(), "govActionDeposit");
    }

    /** {@code ppMinPoolCost} */
    public BigInteger minPoolCost() {
        return integer(params.getMinPoolCost(), "minPoolCost");
    }

    /** {@code ppEMax}: how many epochs ahead a pool retirement may be scheduled */
    public long eMax() {
        return integer(params.getEMax(), "eMax").longValueExact();
    }

    private static BigInteger integer(Object value, String name) {
        return switch (value) {
            case null -> throw new IllegalStateException("protocol parameter " + name + " is missing");
            case BigInteger b -> b;
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case BigDecimal d -> d.toBigIntegerExact();
            case String s -> new BigDecimal(s.trim()).toBigIntegerExact();
            default -> throw new IllegalStateException("protocol parameter " + name + " has type " + value.getClass());
        };
    }

    private static BigDecimal decimal(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalStateException("protocol parameter " + name + " is missing");
        }
        return value;
    }
}
