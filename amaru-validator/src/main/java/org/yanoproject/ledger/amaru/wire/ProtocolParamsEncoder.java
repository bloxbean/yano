package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.util.CostModelUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes CCL {@link ProtocolParams} in Amaru's own 31-element parameter layout (INTERFACE.md,
 * {@code amaru_protocol_parameters}; the {@code cbor::Encode} impl in amaru-kernel
 * {@code cardano/protocol_parameters.rs} at the pinned tag), byte for byte as Amaru encodes it.
 *
 * <ul>
 *   <li>Rationals are {@code #6.30([numerator, denominator])}. Yano holds them as {@link BigDecimal}, so
 *       each is sent as its shortest decimal fraction ({@code 0.0577} → {@code 577/10000},
 *       {@code 1.2} → {@code 12/10}, {@code 15} → {@code 15/1}). Amaru compares parameters by value
 *       and never hashes them, so the representation does not change a verdict.
 *       <b>Precondition:</b> the {@link BigDecimal} must be the exact value. Every on-chain rational
 *       parameter so far has a power-of-ten denominator, so a decimal is exact; a non-terminating one
 *       (1/3) cannot be held exactly in Yano's {@code ProtocolParams} and would already be rounded
 *       upstream, where this encoder can no longer detect it.</li>
 *   <li>Cost models come from {@code costModelsRaw} ({@code PlutusV1}/{@code PlutusV2}/{@code PlutusV3}
 *       → languages 0/1/2) or, failing that, from the named {@code costModels} put in canonical
 *       operation order by {@link CostModelUtil}.</li>
 *   <li>The five {@code .size 2} fields are {@code u16} in Amaru; a larger value is refused here instead
 *       of failing to decode inside the module.</li>
 * </ul>
 */
public final class ProtocolParamsEncoder {

    private static final String[] LANGUAGES = {"PlutusV1", "PlutusV2", "PlutusV3"};
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private ProtocolParamsEncoder() {
    }

    /**
     * @throws IllegalArgumentException when a field Amaru requires is missing or out of range
     */
    public static byte[] encode(ProtocolParams pp) {
        Objects.requireNonNull(pp, "protocol parameters");
        CborWriter w = new CborWriter(4096);
        w.array(31);
        w.uint(u64("minFeeA", pp.getMinFeeA()));
        w.uint(u64("minFeeB", pp.getMinFeeB()));
        w.uint(u64("maxBlockSize", pp.getMaxBlockSize()));
        w.uint(u64("maxTxSize", pp.getMaxTxSize()));
        w.uint(u16("maxBlockHeaderSize", pp.getMaxBlockHeaderSize()));
        w.uint(u64("keyDeposit", pp.getKeyDeposit()));
        w.uint(u64("poolDeposit", pp.getPoolDeposit()));
        w.uint(u64("eMax", pp.getEMax()));
        w.uint(u16("nOpt", pp.getNOpt()));
        rational(w, "a0", pp.getA0());
        rational(w, "rho", pp.getRho());
        rational(w, "tau", pp.getTau());
        w.array(2).uint(u64("protocolMajorVer", pp.getProtocolMajorVer()))
                .uint(u64("protocolMinorVer", pp.getProtocolMinorVer()));
        w.uint(u64("minPoolCost", pp.getMinPoolCost()));
        w.uint(u64("coinsPerUtxoSize", pp.getCoinsPerUtxoSize()));
        costModels(w, pp);
        w.array(2);
        rational(w, "priceMem", pp.getPriceMem());
        rational(w, "priceStep", pp.getPriceStep());
        w.array(2).uint(u64("maxTxExMem", pp.getMaxTxExMem())).uint(u64("maxTxExSteps", pp.getMaxTxExSteps()));
        w.array(2).uint(u64("maxBlockExMem", pp.getMaxBlockExMem()))
                .uint(u64("maxBlockExSteps", pp.getMaxBlockExSteps()));
        w.uint(u64("maxValSize", pp.getMaxValSize()));
        w.uint(u16("collateralPercent", pp.getCollateralPercent()));
        w.uint(u16("maxCollateralInputs", pp.getMaxCollateralInputs()));
        w.array(5);
        rational(w, "pvtMotionNoConfidence", pp.getPvtMotionNoConfidence());
        rational(w, "pvtCommitteeNormal", pp.getPvtCommitteeNormal());
        rational(w, "pvtCommitteeNoConfidence", pp.getPvtCommitteeNoConfidence());
        rational(w, "pvtHardForkInitiation", pp.getPvtHardForkInitiation());
        rational(w, "pvtPPSecurityGroup", pp.getPvtPPSecurityGroup());
        w.array(10);
        rational(w, "dvtMotionNoConfidence", pp.getDvtMotionNoConfidence());
        rational(w, "dvtCommitteeNormal", pp.getDvtCommitteeNormal());
        rational(w, "dvtCommitteeNoConfidence", pp.getDvtCommitteeNoConfidence());
        rational(w, "dvtUpdateToConstitution", pp.getDvtUpdateToConstitution());
        rational(w, "dvtHardForkInitiation", pp.getDvtHardForkInitiation());
        rational(w, "dvtPPNetworkGroup", pp.getDvtPPNetworkGroup());
        rational(w, "dvtPPEconomicGroup", pp.getDvtPPEconomicGroup());
        rational(w, "dvtPPTechnicalGroup", pp.getDvtPPTechnicalGroup());
        rational(w, "dvtPPGovGroup", pp.getDvtPPGovGroup());
        rational(w, "dvtTreasuryWithdrawal", pp.getDvtTreasuryWithdrawal());
        w.uint(u16("committeeMinSize", pp.getCommitteeMinSize()));
        w.uint(u64("committeeMaxTermLength", pp.getCommitteeMaxTermLength()));
        w.uint(u64("govActionLifetime", pp.getGovActionLifetime()));
        w.uint(u64("govActionDeposit", pp.getGovActionDeposit()));
        w.uint(u64("drepDeposit", pp.getDrepDeposit()));
        w.uint(u64("drepActivity", pp.getDrepActivity()));
        rational(w, "minFeeRefScriptCostPerByte", pp.getMinFeeRefScriptCostPerByte());
        return w.toByteArray();
    }

    @SuppressWarnings("deprecation") // the named costModels map is only a fallback for costModelsRaw
    private static void costModels(CborWriter w, ProtocolParams pp) {
        Map<String, List<Long>> raw = pp.getCostModelsRaw();
        List<List<Long>> models = new ArrayList<>(3);
        int present = 0;
        for (String language : LANGUAGES) {
            List<Long> model = null;
            if (raw != null && raw.get(language) != null) {
                model = raw.get(language);
            } else if (pp.getCostModels() != null && pp.getCostModels().get(language) != null) {
                model = CostModelUtil.canonicalCostModelList(language, pp.getCostModels().get(language));
            }
            models.add(model);
            present += model != null ? 1 : 0;
        }
        w.map(present);
        for (int language = 0; language < LANGUAGES.length; language++) {
            List<Long> model = models.get(language);
            if (model != null) {
                w.uint(language).array(model.size());
                for (Long cost : model) {
                    w.integer(cost);
                }
            }
        }
    }

    /** {@code #6.30([numerator, denominator])} for a non-negative decimal, as its shortest fraction. */
    static void rational(CborWriter w, String field, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("protocol parameter " + field + " is missing");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("protocol parameter " + field + " is negative: " + value);
        }
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        BigInteger numerator;
        BigInteger denominator;
        if (stripped.scale() <= 0) {
            numerator = stripped.toBigIntegerExact();
            denominator = BigInteger.ONE;
        } else {
            numerator = stripped.unscaledValue();
            denominator = BigInteger.TEN.pow(stripped.scale());
        }
        if (numerator.compareTo(U64_MAX) > 0 || denominator.compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("protocol parameter " + field + " does not fit a u64 rational: "
                    + value);
        }
        w.tag(30).array(2).uint(numerator).uint(denominator);
    }

    private static BigInteger u64(String field, Object value) {
        BigInteger result = switch (value) {
            case null -> throw new IllegalArgumentException("protocol parameter " + field + " is missing");
            case BigInteger b -> b;
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case BigDecimal d -> d.toBigIntegerExact();
            case String s -> new BigInteger(s.trim());
            default -> throw new IllegalArgumentException("protocol parameter " + field + " has type "
                    + value.getClass().getSimpleName());
        };
        if (result.signum() < 0 || result.compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("protocol parameter " + field + " is not a u64: " + result);
        }
        return result;
    }

    private static long u16(String field, Object value) {
        BigInteger result = u64(field, value);
        if (result.compareTo(BigInteger.valueOf(0xFFFF)) > 0) {
            throw new IllegalArgumentException("protocol parameter " + field + " exceeds a u16: " + result);
        }
        return result.longValue();
    }
}
