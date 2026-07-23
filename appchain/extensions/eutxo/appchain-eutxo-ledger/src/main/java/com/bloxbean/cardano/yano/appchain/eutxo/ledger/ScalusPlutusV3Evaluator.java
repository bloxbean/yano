package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.CardanoConstants;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import scalus.bloxbean.EvaluatorMode;
import scalus.bloxbean.NoScriptSupplier;
import scalus.bloxbean.ScalusTransactionEvaluator;
import scalus.cardano.ledger.SlotConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Scalus 0.18.2 adapter for the bounded Plutus V3 spending profile. */
final class ScalusPlutusV3Evaluator implements PlutusV3Evaluator {
    private static final ProtocolParams PROTOCOL_PARAMETERS = protocolParameters();
    private static final SlotConfig SLOT_CONFIG = new SlotConfig(0, 0, 1_000);

    @Override
    public Evaluation evaluate(byte[] transactionCbor, List<EutxoRecord> resolvedInputs) {
        try {
            Set<Utxo> utxos = resolvedInputs.stream()
                    .map(ScalusPlutusV3Evaluator::toUtxo)
                    .collect(Collectors.toUnmodifiableSet());
            UtxoSupplier supplier = new ResolvedUtxoSupplier(utxos);
            var evaluator = new ScalusTransactionEvaluator(
                    SLOT_CONFIG,
                    PROTOCOL_PARAMETERS,
                    supplier,
                    new NoScriptSupplier(),
                    EvaluatorMode.VALIDATE,
                    false);
            var result = evaluator.evaluateTx(transactionCbor, utxos);
            if (!result.isSuccessful()) {
                return Evaluation.reject(
                        "PLUTUS_VALIDATION_FAILED",
                        "Scalus rejected the admitted Plutus V3 script");
            }
            return Evaluation.accept();
        } catch (LinkageError unavailable) {
            return Evaluation.reject(
                    "PLUTUS_ENGINE_UNAVAILABLE",
                    "the selected Scalus runtime is unavailable");
        } catch (Exception rejected) {
            return Evaluation.reject(
                    "PLUTUS_VALIDATION_FAILED",
                    "Scalus rejected the admitted Plutus V3 script");
        }
    }

    private static Utxo toUtxo(EutxoRecord record) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    CborSerializationUtil.deserialize(record.outputCbor()));
            return Utxo.builder()
                    .txHash(record.outpoint().transactionId())
                    .outputIndex(record.outpoint().index())
                    .address(record.address())
                    .amount(List.of(new Amount(
                            CardanoConstants.LOVELACE, output.getValue().getCoin())))
                    .dataHash(output.getDatumHash() == null
                            ? null : java.util.HexFormat.of().formatHex(output.getDatumHash()))
                    .inlineDatum(output.getInlineDatum() == null
                            ? null : output.getInlineDatum().serializeToHex())
                    .referenceScriptHash(null)
                    .build();
        } catch (Exception failure) {
            throw new IllegalStateException("committed EUTxO output cannot be converted", failure);
        }
    }

    private static ProtocolParams protocolParameters() {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        costModels.put("PlutusV3", Arrays.stream(CostModelUtil.PlutusV3CostModel.getCosts())
                .boxed().toList());
        return ProtocolParams.builder()
                .minFeeA(0)
                .minFeeB(0)
                .maxBlockSize(1_048_576)
                .maxTxSize(64 * 1_024)
                .maxBlockHeaderSize(1_100)
                .keyDeposit("0")
                .poolDeposit("0")
                .eMax(18)
                .nOpt(500)
                .a0(BigDecimal.ZERO)
                .rho(BigDecimal.ZERO)
                .tau(BigDecimal.ZERO)
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .minPoolCost("0")
                .costModelsRaw(costModels)
                .priceMem(BigDecimal.ZERO)
                .priceStep(BigDecimal.ZERO)
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .maxBlockExMem("62000000")
                .maxBlockExSteps("40000000000")
                .maxValSize("5000")
                .collateralPercent(BigDecimal.valueOf(150))
                .maxCollateralInputs(3)
                .coinsPerUtxoSize("0")
                .govActionDeposit(BigInteger.ZERO)
                .govActionLifetime(6)
                .drepDeposit(BigInteger.ZERO)
                .drepActivity(20)
                .committeeMinSize(1)
                .committeeMaxTermLength(146)
                .minFeeRefScriptCostPerByte(BigDecimal.ZERO)
                .pvtMotionNoConfidence(BigDecimal.ZERO)
                .pvtCommitteeNormal(BigDecimal.ZERO)
                .pvtCommitteeNoConfidence(BigDecimal.ZERO)
                .pvtHardForkInitiation(BigDecimal.ZERO)
                .pvtPPSecurityGroup(BigDecimal.ZERO)
                .dvtMotionNoConfidence(BigDecimal.ZERO)
                .dvtCommitteeNormal(BigDecimal.ZERO)
                .dvtCommitteeNoConfidence(BigDecimal.ZERO)
                .dvtUpdateToConstitution(BigDecimal.ZERO)
                .dvtHardForkInitiation(BigDecimal.ZERO)
                .dvtPPNetworkGroup(BigDecimal.ZERO)
                .dvtPPEconomicGroup(BigDecimal.ZERO)
                .dvtPPTechnicalGroup(BigDecimal.ZERO)
                .dvtPPGovGroup(BigDecimal.ZERO)
                .dvtTreasuryWithdrawal(BigDecimal.ZERO)
                .build();
    }

    private record ResolvedUtxoSupplier(Set<Utxo> utxos) implements UtxoSupplier {
        private ResolvedUtxoSupplier {
            Objects.requireNonNull(utxos, "utxos");
        }

        @Override
        public List<Utxo> getPage(
                String address,
                Integer numberOfItems,
                Integer page,
                OrderEnum order
        ) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getTxOutput(String transactionId, int index) {
            return utxos.stream()
                    .filter(utxo -> transactionId.equals(utxo.getTxHash())
                            && index == utxo.getOutputIndex())
                    .findFirst();
        }
    }
}
