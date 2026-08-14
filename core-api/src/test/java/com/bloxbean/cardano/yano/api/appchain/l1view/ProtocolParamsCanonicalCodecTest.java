package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolParamsCanonicalCodecTest {
    @Test
    void encodesNamedCostModelsAndTypedLeavesDeterministically() {
        Map<String, LinkedHashMap<String, Long>> named = new LinkedHashMap<>();
        Map<String, List<Long>> raw = new LinkedHashMap<>();
        for (int language = 1; language <= 3; language++) {
            LinkedHashMap<String, Long> operations = new LinkedHashMap<>();
            List<Long> values = new ArrayList<>();
            for (int index = 0; index < 300; index++) {
                long value = index * 17L + language;
                operations.put("operation-with-a-deliberately-long-name-" + index, value);
                values.add(value);
            }
            named.put("PlutusV" + language, operations);
            raw.put("PlutusV" + language, values);
        }

        byte[] encoded = ProtocolParamsCanonicalCodec.encode(snapshot(42, named, raw));
        var document = ProtocolParamsCanonicalCodec.decode(42, encoded);

        assertThat(encoded.length).isLessThanOrEqualTo(ProtocolParamsCanonicalCodec.MAX_DOCUMENT_BYTES);
        assertThat(document.fields()).extracting(ProtocolParamsCanonicalCodec.Field::id)
                .contains("cost-models", "cost-model-hash-plutus-v1",
                        "cost-model-hash-plutus-v2", "cost-model-hash-plutus-v3")
                .isSorted();
        var keyDeposit = document.fields().stream()
                .filter(field -> field.id().equals("key-deposit")).findFirst();
        assertThat(keyDeposit).isEmpty();
        assertThat(ProtocolParamsCanonicalCodec.validate(42, encoded)).isEqualTo(encoded);
    }

    @Test
    void derivesCompactLedgerOrderWhenOnlyNamedProjectionIsAvailable() {
        LinkedHashMap<String, Long> model = new LinkedHashMap<>();
        model.put("000", 11L);
        model.put("001", 22L);

        byte[] encoded = ProtocolParamsCanonicalCodec.encode(
                snapshot(7, Map.of("UnknownPlutus", model), null));
        var document = ProtocolParamsCanonicalCodec.decode(7, encoded);

        assertThat(document.fields().stream().filter(field -> field.id().equals("cost-models"))
                .findFirst().orElseThrow().value()).isEqualTo(Map.of("UnknownPlutus",
                        List.of(java.math.BigInteger.valueOf(11), java.math.BigInteger.valueOf(22))));
        assertThat(ProtocolParamsCanonicalCodec.validate(7, encoded)).isEqualTo(encoded);
    }

    @Test
    void createsStableNamedLovelaceLeaf() {
        var value = snapshot(42, Map.of(), Map.of());
        value = new ProtocolParamsSnapshot(value.epoch(), value.minFeeA(), value.minFeeB(),
                value.maxBlockSize(), value.maxTxSize(), value.maxBlockHeaderSize(),
                java.math.BigInteger.valueOf(2_000_000), value.poolDeposit(), value.eMax(),
                value.nOpt(), value.a0(), value.rho(), value.tau(), value.decentralisationParam(),
                value.extraEntropy(), value.protocolMajorVer(), value.protocolMinorVer(),
                value.minUtxo(), value.minPoolCost(), value.nonce(), value.costModels(),
                value.costModelsRaw(), value.priceMem(), value.priceStep(), value.maxTxExMem(),
                value.maxTxExSteps(), value.maxBlockExMem(), value.maxBlockExSteps(),
                value.maxValSize(), value.collateralPercent(), value.maxCollateralInputs(),
                value.coinsPerUtxoSize(), value.coinsPerUtxoWord(), value.pvtMotionNoConfidence(),
                value.pvtCommitteeNormal(), value.pvtCommitteeNoConfidence(),
                value.pvtHardForkInitiation(), value.pvtPPSecurityGroup(),
                value.dvtMotionNoConfidence(), value.dvtCommitteeNormal(),
                value.dvtCommitteeNoConfidence(), value.dvtUpdateToConstitution(),
                value.dvtHardForkInitiation(), value.dvtPPNetworkGroup(),
                value.dvtPPEconomicGroup(), value.dvtPPTechnicalGroup(), value.dvtPPGovGroup(),
                value.dvtTreasuryWithdrawal(), value.committeeMinSize(),
                value.committeeMaxTermLength(), value.govActionLifetime(), value.govActionDeposit(),
                value.drepDeposit(), value.drepActivity(), value.minFeeRefScriptCostPerByte());
        var field = ProtocolParamsCanonicalCodec.field(42,
                ProtocolParamsCanonicalCodec.encode(value), "key-deposit").orElseThrow();
        assertThat(field.typeName()).isEqualTo("lovelace");
        assertThat(field.value()).isEqualTo(java.math.BigInteger.valueOf(2_000_000));
        var decoded = ProtocolParamsCanonicalCodec.decodeLeaf(field.canonicalCbor());
        assertThat(decoded.id()).isEqualTo(field.id());
        assertThat(decoded.type()).isEqualTo(field.type());
        assertThat(decoded.value()).isEqualTo(field.value());
        assertThat(decoded.canonicalCbor()).isEqualTo(field.canonicalCbor());
    }

    private static ProtocolParamsSnapshot snapshot(int epoch,
                                                   Map<String, LinkedHashMap<String, Long>> named,
                                                   Map<String, List<Long>> raw) {
        return new ProtocolParamsSnapshot(
                epoch,
                44,
                155381,
                90112,
                16384,
                1100,
                null,
                null,
                18,
                500,
                null,
                null,
                null,
                null,
                null,
                10,
                2,
                null,
                null,
                null,
                named,
                raw,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                150,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                3,
                146,
                6,
                null,
                null,
                20,
                null
        );
    }
}
