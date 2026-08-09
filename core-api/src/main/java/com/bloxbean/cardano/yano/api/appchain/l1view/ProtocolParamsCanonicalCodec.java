package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Arrays;

/** Canonical positional CBOR profile for ADR-028 protocol-parameter facts. */
public final class ProtocolParamsCanonicalCodec {
    public static final int VERSION = 1;
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    private ProtocolParamsCanonicalCodec() {
    }

    /**
     * Encode every currently exposed parameter in a frozen positional array.
     * Decimal ledger ratios are represented as exact {@code [numerator,
     * denominator]} pairs, never CBOR floats or decimal strings.
     */
    public static byte[] encode(ProtocolParamsSnapshot value) {
        Objects.requireNonNull(value, "value");
        List<Object> fields = new ArrayList<>();
        fields.add(VERSION);
        fields.add(value.epoch());
        fields.add(value.minFeeA());
        fields.add(value.minFeeB());
        fields.add(value.maxBlockSize());
        fields.add(value.maxTxSize());
        fields.add(value.maxBlockHeaderSize());
        fields.add(value.keyDeposit());
        fields.add(value.poolDeposit());
        fields.add(value.eMax());
        fields.add(value.nOpt());
        fields.add(rational(value.a0()));
        fields.add(rational(value.rho()));
        fields.add(rational(value.tau()));
        fields.add(rational(value.decentralisationParam()));
        fields.add(value.extraEntropy());
        fields.add(value.protocolMajorVer());
        fields.add(value.protocolMinorVer());
        fields.add(value.minUtxo());
        fields.add(value.minPoolCost());
        fields.add(value.nonce());
        // Field 21 is reserved. Named cost-model operations are a derived API projection of the
        // ledger's positional arrays; authenticating both representations made a real Conway
        // snapshot exceed the 8 KiB on-chain proof envelope without adding ledger information.
        fields.add(null);
        fields.add(compactCostModels(value));
        fields.add(rational(value.priceMem()));
        fields.add(rational(value.priceStep()));
        fields.add(value.maxTxExMem());
        fields.add(value.maxTxExSteps());
        fields.add(value.maxBlockExMem());
        fields.add(value.maxBlockExSteps());
        fields.add(value.maxValSize());
        fields.add(value.collateralPercent());
        fields.add(value.maxCollateralInputs());
        fields.add(value.coinsPerUtxoSize());
        fields.add(value.coinsPerUtxoWord());
        fields.add(rational(value.pvtMotionNoConfidence()));
        fields.add(rational(value.pvtCommitteeNormal()));
        fields.add(rational(value.pvtCommitteeNoConfidence()));
        fields.add(rational(value.pvtHardForkInitiation()));
        fields.add(rational(value.pvtPPSecurityGroup()));
        fields.add(rational(value.dvtMotionNoConfidence()));
        fields.add(rational(value.dvtCommitteeNormal()));
        fields.add(rational(value.dvtCommitteeNoConfidence()));
        fields.add(rational(value.dvtUpdateToConstitution()));
        fields.add(rational(value.dvtHardForkInitiation()));
        fields.add(rational(value.dvtPPNetworkGroup()));
        fields.add(rational(value.dvtPPEconomicGroup()));
        fields.add(rational(value.dvtPPTechnicalGroup()));
        fields.add(rational(value.dvtPPGovGroup()));
        fields.add(rational(value.dvtTreasuryWithdrawal()));
        fields.add(value.committeeMinSize());
        fields.add(value.committeeMaxTermLength());
        fields.add(value.govActionLifetime());
        fields.add(value.govActionDeposit());
        fields.add(value.drepDeposit());
        fields.add(value.drepActivity());
        fields.add(rational(value.minFeeRefScriptCostPerByte()));
        try {
            return CBOR.writeValueAsBytes(fields);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Unable to encode canonical protocol parameters", failure);
        }
    }

    /**
     * Strictly validate a persisted canonical value and bind its embedded epoch.
     * This is intentionally a validator rather than a second mutable DTO codec:
     * proof consumers can authenticate the frozen bytes without losing fields.
     */
    public static byte[] validate(long expectedEpoch, byte[] canonical) {
        if (expectedEpoch < 0 || canonical == null || canonical.length == 0
                || canonical.length > 8 * 1024) {
            throw new IllegalArgumentException("Invalid canonical protocol parameters");
        }
        try {
            List<?> fields = CBOR.readValue(canonical, List.class);
            if (fields.size() != 56
                    || !(fields.get(0) instanceof Number version)
                    || version.longValue() != VERSION
                    || !(fields.get(1) instanceof Number epoch)
                    || epoch.longValue() != expectedEpoch
                    || !Arrays.equals(canonical, CBOR.writeValueAsBytes(fields))) {
                throw new IllegalArgumentException("Invalid canonical protocol parameters");
            }
            return canonical.clone();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid canonical protocol parameters", failure);
        }
    }

    private static List<BigInteger> rational(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        BigInteger numerator = normalized.unscaledValue();
        int scale = normalized.scale();
        if (scale < 0) {
            return List.of(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }
        return List.of(numerator, BigInteger.TEN.pow(scale));
    }

    private static Map<String, List<Long>> sortedLists(Map<String, List<Long>> input) {
        return input != null ? new TreeMap<>(input) : null;
    }

    private static Map<String, List<Long>> compactCostModels(ProtocolParamsSnapshot value) {
        Map<String, List<Long>> raw = value.costModelsRaw();
        if (raw == null || raw.isEmpty()) {
            raw = CostModelUtil.canonicalRawCostModelsTyped(value.costModels());
        }
        return sortedLists(raw);
    }
}
