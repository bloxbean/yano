package org.yanoproject.ledger.rules.fixtures.tx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.LongStream;
import java.util.zip.CRC32;

/**
 * The ledger state every mutant is validated against: a preprod-like Conway PV 10 world at slot
 * {@value #SLOT} (epoch 0 of a single Conway era, like the Amaru corpus's era history), with
 *
 * <ul>
 *   <li>{@link #KEY_INPUT}: 100 ADA at {@code dev-42}'s enterprise address,</li>
 *   <li>{@link #SCRIPT_INPUT}: 10 ADA at the enterprise address of {@link #ALWAYS_SUCCEEDS} (PlutusV3, no datum,
 *       CIP-69),</li>
 *   <li>{@link #collateralInput(int)} 0–3: 5 ADA each at {@code dev-42}'s address;</li>
 *   <li>{@link #SCRIPT_COLLATERAL_INPUT}: 5 ADA at the always-succeeds script's address;</li>
 *   <li>{@link #TOKEN_COLLATERAL_INPUT}: 5 ADA and one {@link #TOKEN_POLICY} token at {@code dev-42}'s address;</li>
 *   <li>{@link #SMALL_COLLATERAL_INPUT}: 0.2 ADA at {@code dev-42}'s address;</li>
 *   <li>{@link #MANY_ASSETS_INPUT}: 10 ADA and {@value #MANY_ASSETS} tokens with 32-byte names at {@code dev-42}'s
 *       address (enough to exceed {@code maxValSize} when they move to one output);</li>
 *   <li>{@link #FAIL_SCRIPT_INPUT}: 10 ADA at the enterprise address of {@link #ALWAYS_FAILS}.</li>
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

    /** {@code (program 1.1.0 (error))}: fails for any script context (Amaru's corpus script {@code 994b345a…}). */
    public static final PlutusV3Script ALWAYS_FAILS = PlutusV3Script.builder()
            .type("PlutusScriptV3")
            .cborHex("454401010061")
            .build();

    public static final TransactionInput KEY_INPUT = input('1', 0);
    public static final TransactionInput SCRIPT_INPUT = input('2', 0);
    public static final TransactionInput SCRIPT_COLLATERAL_INPUT = input('4', 0);
    public static final TransactionInput TOKEN_COLLATERAL_INPUT = input('5', 0);
    public static final TransactionInput SMALL_COLLATERAL_INPUT = input('6', 0);
    public static final TransactionInput MANY_ASSETS_INPUT = input('7', 0);
    public static final TransactionInput FAIL_SCRIPT_INPUT = input('8', 0);

    /** The payment the base transactions make. */
    public static final BigInteger PAYMENT = BigInteger.valueOf(10_000_000);
    /** The base transactions' time to live. */
    public static final long TTL = SLOT + 10_000;

    /** The policy id of the world's tokens (an arbitrary 28-byte hash; nothing mints it). */
    public static final String TOKEN_POLICY = "ab".repeat(28);
    public static final int MANY_ASSETS = 160;

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

    public static String failingScriptAddress() {
        return AddressProvider.getEntAddress(ALWAYS_FAILS, NETWORK).toBech32();
    }

    /** @return the base state */
    public static InMemoryLedgerView view() {
        return builder(protocolParams()).build();
    }

    /**
     * @param params the protocol parameters (for example {@link #protocolParams()} with another version)
     * @return a builder holding the base state, to add to
     */
    public static InMemoryLedgerView.Builder builder(ProtocolParams params) {
        InMemoryLedgerView.Builder view = InMemoryLedgerView.builder().protocolParams(params);
        String owner = TestKey.DEV_42.enterpriseAddress(NETWORK);
        view.utxo(KEY_INPUT.getTransactionId(), KEY_INPUT.getIndex(), output(owner, KEY_INPUT_LOVELACE));
        view.utxo(SCRIPT_INPUT.getTransactionId(), SCRIPT_INPUT.getIndex(),
                output(scriptAddress(), SCRIPT_INPUT_LOVELACE));
        for (int i = 0; i < 4; i++) {
            TransactionInput collateral = collateralInput(i);
            view.utxo(collateral.getTransactionId(), collateral.getIndex(), output(owner, COLLATERAL_LOVELACE));
        }
        utxo(view, SCRIPT_COLLATERAL_INPUT, output(scriptAddress(), COLLATERAL_LOVELACE));
        TransactionOutput token = output(owner, COLLATERAL_LOVELACE);
        token.getValue().setMultiAssets(new ArrayList<>(List.of(assets(1, 3))));
        utxo(view, TOKEN_COLLATERAL_INPUT, token);
        utxo(view, SMALL_COLLATERAL_INPUT, output(owner, BigInteger.valueOf(200_000)));
        TransactionOutput many = output(owner, SCRIPT_INPUT_LOVELACE);
        many.getValue().setMultiAssets(new ArrayList<>(List.of(assets(MANY_ASSETS, 32))));
        utxo(view, MANY_ASSETS_INPUT, many);
        utxo(view, FAIL_SCRIPT_INPUT, output(failingScriptAddress(), SCRIPT_INPUT_LOVELACE));
        return view;
    }

    /** @return the simple base: {@link #KEY_INPUT} pays {@link #PAYMENT} to {@code dev-aa}, change to {@code dev-42} */
    public static TxSpec simpleSpec() {
        TxSpec spec = new TxSpec();
        spec.inputs.add(KEY_INPUT);
        spec.outputs.add(output(TestKey.DEV_AA.enterpriseAddress(NETWORK), PAYMENT));
        spec.changeAddress = TestKey.DEV_42.enterpriseAddress(NETWORK);
        spec.ttl = TTL;
        spec.signers.add(TestKey.DEV_42);
        return spec;
    }

    /**
     * @return the script base: the simple base also spending {@link #SCRIPT_INPUT} with one redeemer, and
     *         {@code collateralInput(0)} as collateral
     */
    public static TxSpec scriptSpec() {
        TxSpec spec = simpleSpec();
        // Inputs are a set ordered by (id, index): KEY_INPUT (11…) sorts before SCRIPT_INPUT (22…), so the
        // spending redeemer points at index 1.
        spec.inputs.add(SCRIPT_INPUT);
        spec.collateral.add(collateralInput(0));
        spec.plutusScripts.add(ALWAYS_SUCCEEDS);
        spec.redeemers.add(spendRedeemer(100_000));
        return spec;
    }

    /** The script base's spending redeemer (index 1) with {@code mem} memory units and 50M steps. */
    public static Redeemer spendRedeemer(long mem) {
        return Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(BigInteger.ONE)
                .data(ConstrPlutusData.of(0))
                .exUnits(ExUnits.builder().mem(BigInteger.valueOf(mem)).steps(BigInteger.valueOf(50_000_000)).build())
                .build();
    }

    public static TransactionOutput output(String address, BigInteger lovelace) {
        return TransactionOutput.builder().address(address).value(Value.builder().coin(lovelace).build()).build();
    }

    private static void utxo(InMemoryLedgerView.Builder view, TransactionInput input, TransactionOutput output) {
        view.utxo(input.getTransactionId(), input.getIndex(), output);
    }

    /** {@code count} tokens of {@link #TOKEN_POLICY}, one each, with distinct names of {@code nameLength} bytes. */
    private static MultiAsset assets(int count, int nameLength) {
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] name = new byte[nameLength];
            Arrays.fill(name, (byte) 0x41);
            name[nameLength - 1] = (byte) i;
            name[nameLength - 2] = (byte) (i >> 8);
            assets.add(new Asset("0x" + HexUtil.encodeHexString(name), BigInteger.ONE));
        }
        return MultiAsset.builder().policyId(TOKEN_POLICY).assets(assets).build();
    }

    /**
     * A Byron (bootstrap) address on a testnet (its attributes carry network magic 1) whose attributes hold
     * {@code extraAttributeBytes} more bytes under an unknown key, so {@code bootstrapAddressAttrsSize} is
     * {@code extraAttributeBytes}.
     *
     * @return the base58 address
     */
    public static String byronAddress(int extraAttributeBytes) {
        try {
            Map attributes = new Map();
            attributes.put(new UnsignedInteger(2), new ByteString(CborSerializationUtil.serialize(new UnsignedInteger(1))));
            if (extraAttributeBytes > 0) {
                attributes.put(new UnsignedInteger(5), new ByteString(new byte[extraAttributeBytes]));
            }
            Array payload = new Array();
            payload.add(new ByteString(new byte[28]));
            payload.add(attributes);
            payload.add(new UnsignedInteger(0));
            byte[] payloadBytes = CborSerializationUtil.serialize(payload);
            ByteString wrapped = new ByteString(payloadBytes);
            wrapped.setTag(24);
            CRC32 crc = new CRC32();
            crc.update(payloadBytes);
            Array address = new Array();
            address.add(wrapped);
            address.add(new UnsignedInteger(crc.getValue()));
            return Base58.encode(CborSerializationUtil.serialize(address));
        } catch (Exception e) {
            throw new IllegalStateException("cannot build a Byron address", e);
        }
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
