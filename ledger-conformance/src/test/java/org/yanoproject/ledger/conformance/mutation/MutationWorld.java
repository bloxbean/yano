package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.LongStream;

/**
 * The ledger state every mutant is validated against: a preprod-like Conway PV 10 world at slot
 * {@value #SLOT} (epoch 0 of a single Conway era, like the Amaru corpus's era history), with
 *
 * <ul>
 *   <li>{@link #KEY_INPUT}: 100 ADA at {@code dev-42}'s enterprise address,</li>
 *   <li>{@link #SCRIPT_INPUT}: 10 ADA at the enterprise address of {@link #ALWAYS_SUCCEEDS} (PlutusV3, no datum,
 *       CIP-69),</li>
 *   <li>{@link #collateralInput(int)} 0–3: 5 ADA each at {@code dev-42}'s address.</li>
 * </ul>
 *
 * <p>The protocol parameters are preprod's Conway values (the same numbers as Amaru's
 * {@code preprod-conway-v10} parameters) with CCL's PlutusV3 cost model.</p>
 */
public final class MutationWorld {

    public static final long SLOT = 100_000;
    public static final Network NETWORK = Networks.testnet();

    /** {@code (program 1.1.0 (lam ctx (con unit ())))}: accepts any script context. */
    public static final PlutusV3Script ALWAYS_SUCCEEDS = PlutusV3Script.builder()
            .type("PlutusScriptV3")
            .cborHex("46450101002499")
            .build();

    public static final TransactionInput KEY_INPUT = input('1', 0);
    public static final TransactionInput SCRIPT_INPUT = input('2', 0);

    public static final BigInteger KEY_INPUT_LOVELACE = BigInteger.valueOf(100_000_000);
    public static final BigInteger SCRIPT_INPUT_LOVELACE = BigInteger.valueOf(10_000_000);
    public static final BigInteger COLLATERAL_LOVELACE = BigInteger.valueOf(5_000_000);

    private static final long SYSTEM_START_MS = 1_654_041_600_000L; // preprod
    private static final long EPOCH_LENGTH = 432_000;

    private MutationWorld() {
    }

    private static TransactionInput input(char hexDigit, int index) {
        char[] id = new char[64];
        Arrays.fill(id, hexDigit);
        return new TransactionInput(new String(id), index);
    }

    /** @return the {@code i}-th collateral UTxO (0–3) */
    public static TransactionInput collateralInput(int i) {
        return input('3', i);
    }

    public static String scriptAddress() {
        return AddressProvider.getEntAddress(ALWAYS_SUCCEEDS, NETWORK).toBech32();
    }

    /** @return the base state */
    public static InMemoryLedgerView view() {
        InMemoryLedgerView.Builder view = InMemoryLedgerView.builder().protocolParams(protocolParams());
        String owner = TestKey.DEV_42.enterpriseAddress(NETWORK);
        view.utxo(KEY_INPUT.getTransactionId(), KEY_INPUT.getIndex(), output(owner, KEY_INPUT_LOVELACE));
        view.utxo(SCRIPT_INPUT.getTransactionId(), SCRIPT_INPUT.getIndex(),
                output(scriptAddress(), SCRIPT_INPUT_LOVELACE));
        for (int i = 0; i < 4; i++) {
            TransactionInput collateral = collateralInput(i);
            view.utxo(collateral.getTransactionId(), collateral.getIndex(), output(owner, COLLATERAL_LOVELACE));
        }
        return view.build();
    }

    static TransactionOutput output(String address, BigInteger lovelace) {
        return TransactionOutput.builder().address(address).value(Value.builder().coin(lovelace).build()).build();
    }

    public static ValidationEnv env() {
        return new ValidationEnv(SLOT, SLOT / EPOCH_LENGTH, 10, 0, NetworkId.TESTNET,
                new SlotConfig(1000, 0, SYSTEM_START_MS), new byte[32]);
    }

    /** A single Conway era from slot 0, preprod's global parameters (as the corpus's era history). */
    public static AmaruScenario.Network network() {
        AmaruScenario.EraSummary conway = new AmaruScenario.EraSummary(new AmaruScenario.EraBound(0, 0, 0), null,
                EPOCH_LENGTH, 1000, 7);
        return new AmaruScenario.Network("preprod", 1, 129_600, List.of(conway),
                new AmaruScenario.GlobalParameters(2160, 10, 20, BigInteger.valueOf(45_000_000_000_000_000L), 129_600,
                        62, SYSTEM_START_MS));
    }

    public static ProtocolParams protocolParams() {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        costModels.put("PlutusV3", LongStream.of(CostModelUtil.plutusV3Costs).boxed().toList());
        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155_381)
                .maxBlockSize(90_112)
                .maxTxSize(16_384)
                .maxBlockHeaderSize(1_100)
                .keyDeposit("2000000")
                .poolDeposit("500000000")
                .eMax(18)
                .nOpt(500)
                .a0(new BigDecimal("0.3"))
                .rho(new BigDecimal("0.003"))
                .tau(new BigDecimal("0.2"))
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .minPoolCost("340000000")
                .coinsPerUtxoSize("4310")
                .costModelsRaw(costModels)
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .maxBlockExMem("62000000")
                .maxBlockExSteps("20000000000")
                .maxValSize("5000")
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .pvtMotionNoConfidence(new BigDecimal("0.51"))
                .pvtCommitteeNormal(new BigDecimal("0.51"))
                .pvtCommitteeNoConfidence(new BigDecimal("0.51"))
                .pvtHardForkInitiation(new BigDecimal("0.51"))
                .pvtPPSecurityGroup(new BigDecimal("0.51"))
                .dvtMotionNoConfidence(new BigDecimal("0.51"))
                .dvtCommitteeNormal(new BigDecimal("0.67"))
                .dvtCommitteeNoConfidence(new BigDecimal("0.67"))
                .dvtUpdateToConstitution(new BigDecimal("0.6"))
                .dvtHardForkInitiation(new BigDecimal("0.75"))
                .dvtPPNetworkGroup(new BigDecimal("0.6"))
                .dvtPPEconomicGroup(new BigDecimal("0.67"))
                .dvtPPTechnicalGroup(new BigDecimal("0.67"))
                .dvtPPGovGroup(new BigDecimal("0.75"))
                .dvtTreasuryWithdrawal(new BigDecimal("0.67"))
                .committeeMinSize(7)
                .committeeMaxTermLength(146)
                .govActionLifetime(6)
                .govActionDeposit(BigInteger.valueOf(100_000_000_000L))
                .drepDeposit(BigInteger.valueOf(500_000_000))
                .drepActivity(20)
                .minFeeRefScriptCostPerByte(new BigDecimal("15"))
                .build();
    }
}
