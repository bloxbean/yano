package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.NullifierShardMirror;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementBootstrapPlan;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTransactionFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.CHAIN_ID;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.DEPOSIT_LOVELACE;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.OPERATOR_ADDRESS;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.OPERATOR_SEED;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.PAYOUT_ADDRESS;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.PLAN;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.ROOT_TOKEN;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.SEED_ONE;
import static com.bloxbean.cardano.yano.app.e2e.EutxoSettlementDevnetE2ETestProfile.SEED_ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-UTXO-009 SP-M6 — the CLOSING gate: the wired settlement stack drives a
 * withdrawal through the FULL effect path on a live devnet chain. An L1
 * deposit mirrors into the v3 app chain; an L2 withdrawal forms a claim; the
 * machine's N-or-T trigger emits the {@code l1.settlement} effect; the
 * factory-built executor resolves the batch from committed state, runs the
 * co-sign round (single-member federation completes with the node's own
 * signature), assembles the Settle transaction with the node's own evaluator,
 * pays the operator witness, submits; the BATCH confirmation observer sees
 * the vault spend and the ledger confirms the claim, reconciling the
 * reserve. The test only funds, bootstraps, deposits and withdraws — every
 * settlement step in between is the wired node acting on its own.
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(EutxoSettlementDevnetE2ETestProfile.class)
class EutxoSettlementEffectPathDevnetE2ETest extends BaseE2ETest {
    private static final Logger log =
            LoggerFactory.getLogger(EutxoSettlementEffectPathDevnetE2ETest.class);
    private static final long ROOT_LOVELACE = 5_000_000L;
    private static final long THREAD_LOVELACE = 2_000_000L;
    private static final long VAULT_GENESIS_LOVELACE = 20_000_000L;
    private static final long PAYOUT_LOVELACE = 10_000_000L; // 12 − 2 flat fee
    private static final long BOUNTY_LOVELACE = 2_000_000L;

    private static final EutxoTestWallet ALICE = EutxoTestWallet.fromSeed(
            EutxoSettlementDevnetE2ETestProfile.filled(0x0E, 32));

    private EutxoClient eutxoClient;

    @Override
    protected int getAccountBaseIndex() {
        return 240;
    }

    @BeforeAll
    void setUpClient() {
        eutxoClient = new EutxoClient(AppChainClient.builder(baseUrl)
                .chainId(CHAIN_ID)
                .build());
    }

    @Test
    void aClaimSettlesEndToEndThroughTheEffectPath() throws Exception {
        // --- 1. Submit the DETERMINISTIC seed transaction the profile's
        //        whole config was computed from (spends the genesis UTxO of
        //        the devnet mnemonic's account 0).
        Result<String> seedResult = backendService.getTransactionService()
                .submitTransaction(
                        EutxoSettlementDevnetE2ETestProfile.SEED_TX_BYTES);
        assertTrue(seedResult.isSuccessful(),
                "seed transaction rejected: " + seedResult.getResponse());
        assertEquals(EutxoSettlementDevnetE2ETestProfile.SEED_TX_ID,
                seedResult.getValue(), "seed transaction id is deterministic");
        checkIfUtxoAvailable(
                EutxoSettlementDevnetE2ETestProfile.SEED_TX_ID,
                OPERATOR_ADDRESS);
        assertTrue(utxoSupplier.getAll(OPERATOR_ADDRESS).stream().anyMatch(
                        utxo -> SEED_ZERO.transactionId().equals(utxo.getTxHash())
                                && utxo.getOutputIndex() == 0),
                "deterministic seed 0 materialized");
        assertTrue(utxoSupplier.getAll(OPERATOR_ADDRESS).stream().anyMatch(
                        utxo -> SEED_ONE.transactionId().equals(utxo.getTxHash())
                                && utxo.getOutputIndex() == 1),
                "deterministic seed 1 materialized");

        // --- 2. Bootstrap the settlement identity (operator raw key). ----
        bootstrapIdentity(PLAN);
        log.info("settlement identity live: vault={}", PLAN.vaultAddress());

        // --- 3. L1 deposit -> mirrored L2 funds for ALICE. ----------------
        EutxoVaultDatum depositDatum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                CHAIN_ID,
                ALICE.address(),
                EutxoSettlementDevnetE2ETestProfile.filled(0x21, 32),
                new EutxoOutpoint("44".repeat(32), 0),
                10_000_000L);
        String depositTx = submitOperator(new Tx()
                .payToContract(PLAN.vaultAddress(),
                        List.of(Amount.lovelace(
                                BigInteger.valueOf(DEPOSIT_LOVELACE))),
                        PlutusData.deserialize(depositDatum.encode()))
                .from(OPERATOR_ADDRESS));
        log.info("deposit submitted: {}", depositTx);
        await("mirrored L2 deposit", 60_000, () ->
                !eutxoClient.utxos(ALICE.address()).isEmpty());

        // --- 4. L2 withdrawal -> claim (payout to the L1 payout address). -
        EutxoOutpoint mirrored =
                eutxoClient.utxos(ALICE.address()).getFirst().outpoint();
        byte[] withdrawalNonce =
                EutxoSettlementDevnetE2ETestProfile.filled(0x22, 32);
        EutxoWithdrawalDatum withdrawalDatum = new EutxoWithdrawalDatum(
                1, CHAIN_ID, 0, PAYOUT_ADDRESS, withdrawalNonce);
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirrored,
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(EutxoSettlementDevnetE2ETestProfile
                                .withdrawalL2Address())
                        .value(Value.fromCoin(
                                BigInteger.valueOf(DEPOSIT_LOVELACE)))
                        .inlineDatum(com.bloxbean.cardano.client.plutus.spec
                                .PlutusData.deserialize(withdrawalDatum.encode()))
                        .build()),
                0, 0);
        String withdrawalTxId = TransactionUtil.getTxHash(
                EutxoTransactionFixtures.serialize(withdrawal));
        eutxoClient.submit(EutxoTransactionFixtures.serialize(withdrawal));
        log.info("L2 withdrawal submitted: {}", withdrawalTxId);

        // The claim the machine forms (v2 ABI, flat 2 ADA fee split).
        EutxoWithdrawalClaim expectedClaim = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2,
                CHAIN_ID, 0,
                new EutxoOutpoint(withdrawalTxId, 0),
                PAYOUT_ADDRESS,
                BigInteger.valueOf(PAYOUT_LOVELACE),
                withdrawalNonce,
                0, 1,
                BigInteger.valueOf(BOUNTY_LOVELACE));
        String claimId = expectedClaim.claimId();
        await("claim recorded", 30_000, () ->
                eutxoClient.withdrawalSnapshot(claimId).value().isPresent());

        // --- 5. The node does the rest: effect -> co-sign -> settle -> ---
        //        batch confirmation -> claim CONFIRMED.
        await("claim CONFIRMED through the effect path", 120_000, () ->
                eutxoClient.withdrawalSnapshot(claimId).value()
                        .map(EutxoWithdrawalRecord::status)
                        .filter(status -> status
                                == EutxoWithdrawalRecord.Status.CONFIRMED)
                        .isPresent());

        // --- 6. L1 outcomes of the effect-driven settlement. --------------
        List<Utxo> payoutUtxos = utxoSupplier.getAll(PAYOUT_ADDRESS);
        assertEquals(BigInteger.valueOf(PAYOUT_LOVELACE),
                payoutUtxos.stream()
                        .flatMap(utxo -> utxo.getAmount().stream())
                        .filter(amount -> "lovelace".equals(amount.getUnit()))
                        .map(Amount::getQuantity)
                        .reduce(BigInteger.ZERO, BigInteger::add),
                "positional payout landed at the destination");

        // Vault conservation: genesis 20 + deposit 12 − (payout 10 + bounty 2).
        BigInteger vaultBalance = utxoSupplier.getAll(PLAN.vaultAddress())
                .stream()
                .flatMap(utxo -> utxo.getAmount().stream())
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(BigInteger.valueOf(VAULT_GENESIS_LOVELACE
                        + DEPOSIT_LOVELACE - PAYOUT_LOVELACE - BOUNTY_LOVELACE),
                vaultBalance, "vault remainder conserved");

        // The claim's shard root advanced away from the empty root.
        int shard = HexFormat.of().parseHex(claimId)[31] & 0x0F;
        String shardUnit = PLAN.shardThreadPolicyIdHex()
                + HexFormat.of().formatHex(new byte[] {(byte) shard});
        Utxo shardThread = utxoSupplier.getAll(PLAN.shardAddress()).stream()
                .filter(utxo -> utxo.getAmount().stream().anyMatch(
                        amount -> shardUnit.equals(amount.getUnit())))
                .findFirst().orElseThrow();
        EutxoShardDatum shardDatum = EutxoShardDatum.decode(
                HexFormat.of().parseHex(shardThread.getInlineDatum()));
        assertTrue(!java.util.Arrays.equals(
                        NullifierShardMirror.emptyRoot(),
                        shardDatum.nullifierRoot()),
                "the claim was nullified on-chain");
        log.info("EFFECT-PATH GATE COMPLETE: claim {} settled by the node "
                + "(shard {} root {})", claimId, shard,
                HexFormat.of().formatHex(shardDatum.nullifierRoot()));
    }

    // ------------------------------------------------------------------

    private void await(String what, long timeoutMillis, Supplier<Boolean> check)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(check.get())) {
                    return;
                }
            } catch (RuntimeException retryable) {
                // queries can 404/timeout while the chain catches up
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private void bootstrapIdentity(SettlementBootstrapPlan plan)
            throws Exception {
        Utxo rootSeed = operatorUtxo(SEED_ZERO);
        Utxo shardSeed = operatorUtxo(SEED_ONE);
        String rootUnit = plan.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(ROOT_TOKEN.getBytes());
        String rootMintTx = submitOperatorScript(new ScriptTx()
                .collectFrom(rootSeed)
                .mintAsset(plan.rootThreadPolicy(),
                        List.of(new Asset(ROOT_TOKEN, BigInteger.ONE)),
                        BigIntPlutusData.of(0),
                        OPERATOR_ADDRESS));
        checkIfUtxoAvailable(rootMintTx, OPERATOR_ADDRESS);
        List<Asset> shardAssets = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            shardAssets.add(new Asset(
                    "0x" + HexFormat.of().formatHex(new byte[] {(byte) index}),
                    BigInteger.ONE));
        }
        String shardMintTx = submitOperatorScript(new ScriptTx()
                .collectFrom(shardSeed)
                .mintAsset(plan.shardThreadPolicy(),
                        shardAssets,
                        BigIntPlutusData.of(0),
                        OPERATOR_ADDRESS));
        checkIfUtxoAvailable(shardMintTx, OPERATOR_ADDRESS);
        Tx genesis = new Tx()
                .payToContract(plan.rootAddress(),
                        List.of(Amount.lovelace(
                                        BigInteger.valueOf(ROOT_LOVELACE)),
                                new Amount(rootUnit, BigInteger.ONE)),
                        PlutusData.deserialize(plan.initialRootDatum()))
                .payToContract(plan.vaultAddress(),
                        List.of(Amount.lovelace(BigInteger.valueOf(
                                VAULT_GENESIS_LOVELACE))),
                        BigIntPlutusData.of(0));
        for (int index = 0; index < 16; index++) {
            String unit = plan.shardThreadPolicyIdHex()
                    + HexFormat.of().formatHex(new byte[] {(byte) index});
            genesis = genesis.payToContract(plan.shardAddress(),
                    List.of(Amount.lovelace(
                                    BigInteger.valueOf(THREAD_LOVELACE)),
                            new Amount(unit, BigInteger.ONE)),
                    PlutusData.deserialize(
                            plan.shardDatums().get(index).encode()));
        }
        String genesisTx = submitOperator(genesis.from(OPERATOR_ADDRESS));
        checkIfUtxoAvailable(genesisTx, plan.rootAddress());
    }

    private Utxo operatorUtxo(EutxoOutpoint outpoint) {
        return utxoSupplier.getAll(OPERATOR_ADDRESS).stream()
                .filter(utxo -> outpoint.transactionId().equals(utxo.getTxHash())
                        && utxo.getOutputIndex() == outpoint.index())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "operator utxo " + outpoint + " not found"));
    }

    private String submitOperator(Tx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(
                        SecretKey.create(OPERATOR_SEED)))
                .complete();
        assertTrue(result.isSuccessful(),
                "operator transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }

    private String submitOperatorScript(ScriptTx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .feePayer(OPERATOR_ADDRESS)
                .withSigner(SignerProviders.signerFrom(
                        SecretKey.create(OPERATOR_SEED)))
                .complete();
        assertTrue(result.isSuccessful(),
                "operator script transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }
}
