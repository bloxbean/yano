package org.yanoproject.ledger.rules.fixtures.tx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeAfter;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 *   <li>{@link #FAIL_SCRIPT_INPUT}: 10 ADA at the enterprise address of {@link #ALWAYS_FAILS};</li>
 *   <li>{@link #V2_SCRIPT_INPUT}: 10 ADA at the address of {@link #ALWAYS_SUCCEEDS_V2}, without a datum;</li>
 *   <li>{@link #DATUM_SCRIPT_INPUT}: 10 ADA at the always-succeeds (PlutusV3) address with the datum hash of
 *       {@link #DATUM} (the integer 42);</li>
 *   <li>{@link #NATIVE_INPUT}: 10 ADA at the address of {@link #NATIVE_SCRIPT} ({@code dev-42} signs);</li>
 *   <li>{@link #TIMELOCK_INPUT}: 10 ADA at the address of {@link #TIMELOCK_SCRIPT} (valid from slot
 *       {@code SLOT + 5000} only);</li>
 *   <li>{@link #MALFORMED_SCRIPT_INPUT}: 10 ADA at the address of {@link #MALFORMED_SCRIPT};</li>
 *   <li>{@link #TRAILING_BYTES_SCRIPT_INPUT} and {@link #UNAVAILABLE_BUILTIN_SCRIPT_INPUT}: 10 ADA each at the
 *       addresses of {@link #TRAILING_BYTES_SCRIPT} and {@link #UNAVAILABLE_BUILTIN_SCRIPT};</li>
 *   <li>{@link #BYRON_INPUT}: 10 ADA at {@code dev-42}'s bootstrap address;</li>
 *   <li>{@link #RICH_INPUT}: 2,000 ADA at {@code dev-42}'s address (enough for DRep and pool deposits).</li>
 * </ul>
 *
 * <p>Certificate state (ADR-056 Phase 4), with the two keys that are not in Amaru's corpus, so that {@code dev-42}
 * and {@code dev-aa} stay unregistered everywhere:</p>
 * <ul>
 *   <li>stake accounts: {@code dev-77} (deposit 2 ADA, balance 0, delegated to {@code dev-77}'s pool and DRep) and
 *       {@code dev-bb} (deposit 2 ADA, balance {@link #REWARD_BALANCE}, delegated to {@code dev-77}'s pool and to
 *       {@code AlwaysAbstain});</li>
 *   <li>pools: {@code dev-77}'s (VRF {@link #POOL_77_VRF}) and {@code dev-bb}'s (VRF {@link #POOL_BB_VRF}), each
 *       owned by its operator, with its operator's testnet reward account;</li>
 *   <li>DRep: {@code dev-77} (deposit 500 ADA, expiry epoch 20);</li>
 *   <li>committee: {@code dev-77} (elected until epoch {@value #COMMITTEE_TERM}, hot key {@code dev-42}),
 *       {@code dev-bb} (elected, resigned) and {@link #UNELECTED_COLD} (no term, hot key {@code dev-aa});</li>
 *   <li>{@code dev-cc}: a stake account with balance {@link #REWARD_BALANCE} and no delegation.</li>
 * </ul>
 *
 * <p>Governance state (ADR-056 Phase 5): the standing proposals {@link #INFO_ACTION} and
 * {@link #PARAMETER_CHANGE_ACTION} (a {@code collateralPercentage} change, outside the stake-pool security group), both
 * proposed in epoch 0 and expiring after epoch {@value #GOV_ACTION_LIFETIME}; no enacted roots; no guardrail script;
 * treasury {@link #TREASURY}; {@link #GOV_INPUT} (250,000 ADA, for a proposal deposit) and
 * {@link #BIG_REFERENCE_SCRIPT_INPUT} (a {@value #BIG_REFERENCE_SCRIPT_SIZE}-byte reference script).</p>
 *
 * <p>The protocol parameters are preprod's Conway values (the same numbers as Amaru's
 * {@code preprod-conway-v10} parameters) with CCL's PlutusV2 and PlutusV3 cost models.</p>
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

    /** An always-succeeding PlutusV2 script: Amaru's corpus script {@code 52c6af0c…}. */
    public static final PlutusV2Script ALWAYS_SUCCEEDS_V2 = PlutusV2Script.builder()
            .type("PlutusScriptV2")
            .cborHex("4746010000222499")
            .build();

    /** A PlutusV3 "script" whose bytes are not a flat-encoded program: not well formed. */
    public static final PlutusV3Script MALFORMED_SCRIPT = PlutusV3Script.builder()
            .type("PlutusScriptV3")
            .cborHex("4401020304")
            .build();

    /** The always-succeeds PlutusV3 program followed by one more byte: a PlutusV3 {@code RemainderError}. */
    public static final PlutusV3Script TRAILING_BYTES_SCRIPT = PlutusV3Script.builder()
            .type("PlutusScriptV3")
            .cborHex("4745010100249900")
            .build();

    /** {@code (program 1.0.0 (lam x (builtin expModInteger)))}: builtin 87 is PlutusV3's only from version 11. */
    public static final PlutusV3Script UNAVAILABLE_BUILTIN_SCRIPT = PlutusV3Script.builder()
            .type("PlutusScriptV3")
            .cborHex("464501000027af")
            .build();

    /** A native script satisfied by {@code dev-42}'s signature. */
    public static final NativeScript NATIVE_SCRIPT = new ScriptPubkey(TestKey.DEV_42.keyHash());
    /** A native script valid only from slot {@code SLOT + 5000}. */
    public static final NativeScript TIMELOCK_SCRIPT = new RequireTimeAfter(SLOT + 5_000);
    /** The datum locking {@link #DATUM_SCRIPT_INPUT}: the integer 42 (hash {@code 9e1199a9…}). */
    public static final PlutusData DATUM = BigIntPlutusData.of(42);

    public static final TransactionInput KEY_INPUT = input('1', 0);
    public static final TransactionInput SCRIPT_INPUT = input('2', 0);
    public static final TransactionInput SCRIPT_COLLATERAL_INPUT = input('4', 0);
    public static final TransactionInput TOKEN_COLLATERAL_INPUT = input('5', 0);
    public static final TransactionInput SMALL_COLLATERAL_INPUT = input('6', 0);
    public static final TransactionInput MANY_ASSETS_INPUT = input('7', 0);
    public static final TransactionInput FAIL_SCRIPT_INPUT = input('8', 0);
    public static final TransactionInput V2_SCRIPT_INPUT = input('a', 0);
    public static final TransactionInput DATUM_SCRIPT_INPUT = input('b', 0);
    public static final TransactionInput NATIVE_INPUT = input('d', 0);
    public static final TransactionInput TIMELOCK_INPUT = input('e', 0);
    public static final TransactionInput MALFORMED_SCRIPT_INPUT = input('f', 0);
    public static final TransactionInput BYRON_INPUT = input('1', 1);
    public static final TransactionInput TRAILING_BYTES_SCRIPT_INPUT = input('9', 1);
    public static final TransactionInput UNAVAILABLE_BUILTIN_SCRIPT_INPUT = input('9', 2);
    public static final TransactionInput RICH_INPUT = input('1', 7);
    public static final BigInteger RICH_INPUT_LOVELACE = BigInteger.valueOf(2_000_000_000L);
    /** 250,000 ADA at {@code dev-42}'s address: enough for a governance action deposit ({@link #GOV_ACTION_DEPOSIT}). */
    public static final TransactionInput GOV_INPUT = input('1', 8);
    public static final BigInteger GOV_INPUT_LOVELACE = BigInteger.valueOf(250_000_000_000L);
    /**
     * 10 ADA at {@code dev-42}'s address with a PlutusV2 reference script of {@value #BIG_REFERENCE_SCRIPT_SIZE} bytes
     * (the always-succeeding V2 program followed by zero bytes, which PlutusV1/V2 ignore), above the 200 KiB
     * {@code maxRefScriptSizePerTx}.
     */
    public static final TransactionInput BIG_REFERENCE_SCRIPT_INPUT = input('1', 9);
    public static final int BIG_REFERENCE_SCRIPT_SIZE = 205_000;

    /** The world's {@code ppGovActionDeposit} (preprod's 100,000 ADA) and {@code ppGovActionLifetime}. */
    public static final BigInteger GOV_ACTION_DEPOSIT = BigInteger.valueOf(100_000_000_000L);
    public static final long GOV_ACTION_LIFETIME = 6;
    /** The treasury of the world's epoch. */
    public static final BigInteger TREASURY = BigInteger.valueOf(1_000_000_000_000L);
    /** A standing {@code InfoAction} proposal (proposed in epoch 0, expires after epoch 6). */
    public static final GovActionId INFO_ACTION = new GovActionId("c0".repeat(32), 0);
    /** A standing parameter change of {@code collateralPercentage} only: outside the stake-pool security group. */
    public static final GovActionId PARAMETER_CHANGE_ACTION = new GovActionId("c0".repeat(32), 1);
    /**
     * The cold credential (a script hash no one holds) of a committee member without a term that has authorised
     * {@code dev-aa}'s key as its hot credential: an unelected member, as in Amaru's scenario 00171.
     */
    public static final CredentialKey UNELECTED_COLD = CredentialKey.script("c1".repeat(28));

    /** The world's {@code ppKeyDeposit}, {@code ppPoolDeposit} and {@code ppDRepDeposit}. */
    public static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000);
    public static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000);
    public static final BigInteger DREP_DEPOSIT = BigInteger.valueOf(500_000_000);
    /** {@code dev-bb}'s reward balance. */
    public static final BigInteger REWARD_BALANCE = BigInteger.valueOf(5_000_000);
    /** The world's {@code ppMinPoolCost}. */
    public static final BigInteger MIN_POOL_COST = BigInteger.valueOf(340_000_000);
    public static final byte[] POOL_77_VRF = filled(32, 0x71);
    public static final byte[] POOL_BB_VRF = filled(32, 0xb1);
    /** A VRF key hash no pool of the world uses. */
    public static final byte[] FRESH_VRF = filled(32, 0x41);
    /** The epoch the elected committee members' terms end. */
    public static final long COMMITTEE_TERM = 100;
    public static final long DREP_EXPIRY = 20;

    /** The chain code of the world's bootstrap addresses (32 zero bytes). */
    public static final byte[] BOOTSTRAP_CHAIN_CODE = new byte[32];
    /** The attributes of the world's bootstrap addresses: {@code {2: bytes(cbor 1)}}, a testnet network magic. */
    public static final byte[] BOOTSTRAP_ATTRIBUTES = HexUtil.decodeHexString("a1024101");

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

    /** The {@code [2, bytes]} script reference of {@link #BIG_REFERENCE_SCRIPT_INPUT}. */
    private static final byte[] BIG_REFERENCE_SCRIPT_REF = bigReferenceScriptRef();

    private MutationWorld() {
    }

    private static byte[] bigReferenceScriptRef() {
        byte[] program = HexUtil.decodeHexString("46010000222499");
        byte[] script = Arrays.copyOf(program, BIG_REFERENCE_SCRIPT_SIZE);
        try {
            Array ref = new Array();
            ref.add(new UnsignedInteger(2));
            ref.add(new ByteString(script));
            return CborSerializationUtil.serialize(ref);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
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

    /** @return small metadata: label 674, one short text */
    public static CBORMetadata smallMetadata() {
        return new CBORMetadata().put(BigInteger.valueOf(674), "ADR-056 mutation matrix");
    }

    /** @return the enterprise address of a script */
    public static String address(Script script) {
        return AddressProvider.getEntAddress(script, NETWORK).toBech32();
    }

    /** @return blake2b-256 of a datum's CCL encoding */
    public static byte[] datumHash(PlutusData datum) {
        try {
            return Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(datum.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the base state */
    public static InMemoryLedgerView view() {
        return builder(protocolParams()).build();
    }

    /** @return the base state at another protocol major version (10 or 11) */
    public static InMemoryLedgerView view(int protocolMajor) {
        return builder(protocolParams(protocolMajor)).build();
    }

    /** @return a key's stake credential */
    public static StakeCredential stakeCredential(TestKey key) {
        return StakeCredential.fromKeyHash(HexUtil.decodeHexString(key.keyHash()));
    }

    /** @return a key's credential (DRep, committee) */
    public static Credential credential(TestKey key) {
        return Credential.fromKey(key.keyHash());
    }

    /** @return a key's credential as a view key */
    public static CredentialKey credentialKey(TestKey key) {
        return CredentialKey.key(key.keyHash());
    }

    /** @return a key's reward account on {@code network}, bech32 */
    public static String rewardAccount(TestKey key, Network network) {
        return AddressProvider.getRewardAddress(credential(key), network).toBech32();
    }

    /**
     * A pool registration whose operator and only owner is {@code operator}, with pledge 0 and margin 0.
     *
     * @param rewardNetwork    the reward account's network
     * @param metadataHashHex  the metadata hash (any length), or null for no metadata
     */
    public static PoolRegistration poolRegistration(TestKey operator, byte[] vrf, BigInteger cost, Network rewardNetwork,
                                                    String metadataHashHex) {
        byte[] reward = AddressProvider.getRewardAddress(credential(operator), rewardNetwork).getBytes();
        Set<String> owners = new LinkedHashSet<>(List.of(operator.keyHash()));
        return PoolRegistration.builder()
                .operator(HexUtil.decodeHexString(operator.keyHash()))
                .vrfKeyHash(vrf.clone())
                .pledge(BigInteger.ZERO)
                .cost(cost)
                .margin(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .rewardAccount(HexUtil.encodeHexString(reward))
                .poolOwners(owners)
                .relays(new ArrayList<>())
                .poolMetadataUrl(metadataHashHex != null ? "https://example.com/pool.json" : null)
                .poolMetadataHash(metadataHashHex)
                .build();
    }

    /** @return the registration of {@code dev-77}'s pool as the world holds it */
    public static PoolRegistration pool77() {
        return poolRegistration(TestKey.DEV_77, POOL_77_VRF, MIN_POOL_COST, NETWORK, null);
    }

    /** @return the pool id of a key's pool */
    public static PoolId poolId(TestKey operator) {
        return new PoolId(operator.keyHash());
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
        utxo(view, V2_SCRIPT_INPUT, output(address(ALWAYS_SUCCEEDS_V2), SCRIPT_INPUT_LOVELACE));
        TransactionOutput withDatum = output(scriptAddress(), SCRIPT_INPUT_LOVELACE);
        withDatum.setDatumHash(datumHash(DATUM));
        utxo(view, DATUM_SCRIPT_INPUT, withDatum);
        utxo(view, NATIVE_INPUT, output(address(NATIVE_SCRIPT), SCRIPT_INPUT_LOVELACE));
        utxo(view, TIMELOCK_INPUT, output(address(TIMELOCK_SCRIPT), SCRIPT_INPUT_LOVELACE));
        utxo(view, MALFORMED_SCRIPT_INPUT, output(address(MALFORMED_SCRIPT), SCRIPT_INPUT_LOVELACE));
        utxo(view, BYRON_INPUT, output(bootstrapAddress(TestKey.DEV_42), SCRIPT_INPUT_LOVELACE));
        utxo(view, TRAILING_BYTES_SCRIPT_INPUT, output(address(TRAILING_BYTES_SCRIPT), SCRIPT_INPUT_LOVELACE));
        utxo(view, UNAVAILABLE_BUILTIN_SCRIPT_INPUT, output(address(UNAVAILABLE_BUILTIN_SCRIPT),
                SCRIPT_INPUT_LOVELACE));
        utxo(view, RICH_INPUT, output(owner, RICH_INPUT_LOVELACE));
        utxo(view, GOV_INPUT, output(owner, GOV_INPUT_LOVELACE));
        TransactionOutput bigReference = output(owner, SCRIPT_INPUT_LOVELACE);
        bigReference.setScriptRef(BIG_REFERENCE_SCRIPT_REF.clone());
        utxo(view, BIG_REFERENCE_SCRIPT_INPUT, bigReference);

        // Certificate state (dev-42 and dev-aa stay unregistered everywhere).
        PoolId pool77 = poolId(TestKey.DEV_77);
        view.pool(pool77(), POOL_DEPOSIT);
        view.pool(poolRegistration(TestKey.DEV_BB, POOL_BB_VRF, MIN_POOL_COST, NETWORK, null), POOL_DEPOSIT);
        view.account(new AccountState(credentialKey(TestKey.DEV_77), KEY_DEPOSIT, BigInteger.ZERO, pool77,
                DRepTarget.credential(credentialKey(TestKey.DEV_77))));
        view.account(new AccountState(credentialKey(TestKey.DEV_BB), KEY_DEPOSIT, REWARD_BALANCE, pool77,
                DRepTarget.ALWAYS_ABSTAIN));
        view.drep(new DRepState(credentialKey(TestKey.DEV_77), DREP_DEPOSIT, DREP_EXPIRY));
        view.committeeMember(new CommitteeMemberState(credentialKey(TestKey.DEV_77), credentialKey(TestKey.DEV_42),
                false, COMMITTEE_TERM));
        view.committeeMember(new CommitteeMemberState(credentialKey(TestKey.DEV_BB), null, true, COMMITTEE_TERM));
        view.committeeMember(new CommitteeMemberState(UNELECTED_COLD, credentialKey(TestKey.DEV_AA), false, null));
        view.account(new AccountState(credentialKey(TestKey.DEV_CC), KEY_DEPOSIT, REWARD_BALANCE, null, null));

        // Governance state (ADR-056 Phase 5): two standing proposals, no enacted roots, no guardrail script.
        view.proposal(new ProposalState(INFO_ACTION, GovActionType.INFO_ACTION, new InfoAction(), null, 0,
                GOV_ACTION_LIFETIME, GOV_ACTION_DEPOSIT, rewardAccount(TestKey.DEV_77, NETWORK), null));
        view.proposal(new ProposalState(PARAMETER_CHANGE_ACTION, GovActionType.PARAMETER_CHANGE_ACTION,
                ParameterChangeAction.builder()
                        .protocolParamUpdate(ProtocolParamUpdate.builder().collateralPercent(150).build())
                        .build(),
                null, 0, GOV_ACTION_LIFETIME, GOV_ACTION_DEPOSIT, rewardAccount(TestKey.DEV_77, NETWORK), Set.of(23)));
        view.treasury(TREASURY);
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

    /**
     * The Byron (bootstrap) address of {@code key} with {@link #BOOTSTRAP_CHAIN_CODE} and
     * {@link #BOOTSTRAP_ATTRIBUTES}: its root is {@code blake2b-224(sha3-256([0, [0, vkey ‖ chain code],
     * attributes]))}, which Haskell's {@code bootstrapWitKeyHash} reproduces from a bootstrap witness.
     *
     * @return the base58 address
     */
    public static String bootstrapAddress(TestKey key) {
        try {
            byte[] spending = new byte[6 + 64 + BOOTSTRAP_ATTRIBUTES.length];
            byte[] prefix = {(byte) 0x83, 0x00, (byte) 0x82, 0x00, 0x58, 0x40};
            System.arraycopy(prefix, 0, spending, 0, 6);
            System.arraycopy(key.verificationKey(), 0, spending, 6, 32);
            System.arraycopy(BOOTSTRAP_CHAIN_CODE, 0, spending, 38, 32);
            System.arraycopy(BOOTSTRAP_ATTRIBUTES, 0, spending, 70, BOOTSTRAP_ATTRIBUTES.length);
            byte[] root = Blake2bUtil.blake2bHash224(MessageDigest.getInstance("SHA3-256").digest(spending));
            byte[] payload = HexUtil.decodeHexString("83581c" + HexUtil.encodeHexString(root)
                    + HexUtil.encodeHexString(BOOTSTRAP_ATTRIBUTES) + "00");
            ByteString wrapped = new ByteString(payload);
            wrapped.setTag(24);
            CRC32 crc = new CRC32();
            crc.update(payload);
            Array address = new Array();
            address.add(wrapped);
            address.add(new UnsignedInteger(crc.getValue()));
            return Base58.encode(CborSerializationUtil.serialize(address));
        } catch (Exception e) {
            throw new IllegalStateException("cannot build a bootstrap address", e);
        }
    }

    public static ValidationEnv env() {
        return env(10);
    }

    /** @return the environment at another protocol major version (10 or 11) */
    public static ValidationEnv env(int protocolMajor) {
        return new ValidationEnv(SLOT, SLOT / EPOCH_LENGTH, protocolMajor, 0, NetworkId.TESTNET,
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

    /** @return the world's parameters at another protocol major version (10 or 11) */
    public static ProtocolParams protocolParams(int protocolMajor) {
        ProtocolParams params = protocolParams();
        params.setProtocolMajorVer(protocolMajor);
        return params;
    }

    public static ProtocolParams protocolParams() {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        costModels.put("PlutusV2", LongStream.of(CostModelUtil.PlutusV2CostModel.getCosts()).boxed().toList());
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
