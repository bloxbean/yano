package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementBootstrapPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-UTXO-009 SP-M6 devnet gate, step 1: the settlement BOOTSTRAP runs end
 * to end on a disposable devnet from the checked-in artifacts — the two
 * one-shot Plutus mints execute on-chain (AnchorThreadPolicy root NFT +
 * ShardThreadPolicy's exact 16 tokens), and the threads land at the resolved
 * validator addresses with the genesis datums the plan computed (root datum
 * with the member set; 16 shard datums at the empty-trie root).
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(EutxoZkDevnetTestProfile.class)
@org.junit.jupiter.api.TestMethodOrder(
        org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class EutxoSettlementBootstrapDevnetE2ETest extends BaseE2ETest {
    private static final Logger log =
            LoggerFactory.getLogger(EutxoSettlementBootstrapDevnetE2ETest.class);
    private static final String ROOT_TOKEN = "YanoSettleRoot";
    private static final long THREAD_LOVELACE = 2_000_000L;
    private static final long ROOT_LOVELACE = 5_000_000L;
    private static final long VAULT_GENESIS_LOVELACE = 20_000_000L;

    private Account operator;
    private Account memberOne;
    private Account memberTwo;
    private Account payout;
    private SettlementBootstrapPlan plan;
    private String rootUnit;

    @Override
    protected int getAccountBaseIndex() {
        return 210;
    }

    @BeforeAll
    void fundOperator() throws Exception {
        operator = getAccount(0);
        memberOne = getAccount(1);
        memberTwo = getAccount(2);
        payout = getAccount(3);
        fundAddress(operator.enterpriseAddress(), 50_000);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void bootstrapMintsThreadsAndGenesisOutputsFromTheCheckedInArtifacts()
            throws Exception {
        // --- 1. Carve two one-shot seed UTxOs at known outpoints ----------
        String seedTx = submit(new Tx()
                .payToAddress(operator.enterpriseAddress(),
                        Amount.lovelace(BigInteger.valueOf(5_000_000L)))
                .payToAddress(operator.enterpriseAddress(),
                        Amount.lovelace(BigInteger.valueOf(5_000_000L)))
                .from(operator.enterpriseAddress()));
        checkIfUtxoAvailable(seedTx, operator.enterpriseAddress());
        Utxo rootSeed = utxoAt(seedTx, 0);
        Utxo shardSeed = utxoAt(seedTx, 1);

        // --- 2. Resolve the full deploy identity from the seeds -----------
        plan = SettlementBootstrapPlan.plan(
                new EutxoOutpoint(seedTx, 0),
                new EutxoOutpoint(seedTx, 1),
                new SettlementBootstrapPlan.Config(
                        "payments-settlement",
                        0,
                        Networks.testnet(),
                        ROOT_TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        List.of(
                                HexFormat.of().formatHex(
                                        memberOne.publicKeyBytes()),
                                HexFormat.of().formatHex(
                                        memberTwo.publicKeyBytes())),
                        2,
                        0,
                        86_400));
        log.info("settlement bootstrap: vault={} shard={} root={}",
                plan.vaultAddress(), plan.shardAddress(), plan.rootAddress());

        // --- 3. One-shot mints (REAL Plutus execution on the devnet) ------
        rootUnit = plan.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(ROOT_TOKEN.getBytes());
        String rootMintTx = submitScript(new ScriptTx()
                .collectFrom(rootSeed)
                .mintAsset(plan.rootThreadPolicy(),
                        List.of(new Asset(ROOT_TOKEN, BigInteger.ONE)),
                        BigIntPlutusData.of(0),
                        operator.enterpriseAddress()));
        checkIfUtxoAvailable(rootMintTx, operator.enterpriseAddress());

        List<Asset> shardAssets = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            shardAssets.add(new Asset(
                    "0x" + HexFormat.of().formatHex(
                            new byte[] {(byte) index}),
                    BigInteger.ONE));
        }
        String shardMintTx = submitScript(new ScriptTx()
                .collectFrom(shardSeed)
                .mintAsset(plan.shardThreadPolicy(),
                        shardAssets,
                        BigIntPlutusData.of(0),
                        operator.enterpriseAddress()));
        checkIfUtxoAvailable(shardMintTx, operator.enterpriseAddress());

        // --- 4. Genesis outputs: threads to their validators, vault fund --
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
        String genesisTx = submit(genesis.from(operator.enterpriseAddress()));

        // --- 5. Verify the deployed state on the L1 -----------------------
        checkIfUtxoAvailable(genesisTx, plan.rootAddress());
        List<Utxo> rootUtxos = utxoSupplier.getAll(plan.rootAddress());
        assertEquals(1, rootUtxos.size(), "one root thread");
        assertTrue(rootUtxos.getFirst().getAmount().stream().anyMatch(
                        amount -> rootUnit.equals(amount.getUnit())
                                && BigInteger.ONE.equals(amount.getQuantity())),
                "root thread carries the NFT");
        assertNotNull(rootUtxos.getFirst().getInlineDatum());
        assertEquals(
                HexFormat.of().formatHex(plan.initialRootDatum()),
                rootUtxos.getFirst().getInlineDatum(),
                "genesis root datum");

        List<Utxo> shardUtxos = utxoSupplier.getAll(plan.shardAddress());
        assertEquals(16, shardUtxos.size(), "sixteen shard threads");
        for (int index = 0; index < 16; index++) {
            String unit = plan.shardThreadPolicyIdHex()
                    + HexFormat.of().formatHex(new byte[] {(byte) index});
            Utxo thread = shardUtxos.stream()
                    .filter(utxo -> utxo.getAmount().stream().anyMatch(
                            amount -> unit.equals(amount.getUnit())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "missing shard thread " + unit));
            EutxoShardDatum datum = EutxoShardDatum.decode(
                    HexFormat.of().parseHex(thread.getInlineDatum()));
            assertEquals(index, datum.shardIndex());
            assertEquals("payments-settlement", datum.chainId());
        }

        List<Utxo> vaultUtxos = utxoSupplier.getAll(plan.vaultAddress());
        assertEquals(1, vaultUtxos.size(), "vault genesis output");
        log.info("settlement bootstrap E2E complete: rootMint={} shardMint={} "
                + "genesis={}", rootMintTx, shardMintTx, genesisTx);
    }

    /**
     * ADR-UTXO-009 SP-M6 devnet gate, step 2: a REAL A2 Settle executes on
     * the bootstrapped state — the SP-M2 vault and shard validators run
     * phase-2 on the devnet: positional payouts, remainder conservation
     * under the batch marker, federation threshold via required signers,
     * paired shard spend with a chained InsertBatch (non-membership proofs
     * from the SP-M4 mirror advancing the on-chain root).
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    void federatedBatchSettleSpendsVaultAndNullifiesClaimsOnChain()
            throws Exception {
        // --- claims for shard 0 (last nibble 0), destination = payout ----
        byte[] claimOne = new byte[32];
        java.util.Arrays.fill(claimOne, (byte) 0x31);
        claimOne[31] = 0x10;
        byte[] claimTwo = new byte[32];
        java.util.Arrays.fill(claimTwo, (byte) 0x42);
        claimTwo[31] = 0x20;
        BigInteger payoutOne = BigInteger.valueOf(3_000_000L);
        BigInteger payoutTwo = BigInteger.valueOf(4_000_000L);
        BigInteger bounty = BigInteger.valueOf(1_000_000L);

        PlutusData destination = destinationData(payout);
        PlutusData vaultRedeemer =
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                        com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(
                                claimData(0, claimOne, destination, payoutOne, bounty),
                                claimData(1, claimTwo, destination, payoutTwo, bounty)));

        // --- shard 0 insert plan from the SP-M4 mirror --------------------
        com.bloxbean.cardano.yano.appchain.eutxo.client.NullifierShardMirror
                mirror = new com.bloxbean.cardano.yano.appchain.eutxo.client
                .NullifierShardMirror();
        var insertPlan = mirror.planInserts(0, List.of(claimOne, claimTwo));
        List<PlutusData> inserts = new ArrayList<>();
        for (var insert : insertPlan.inserts()) {
            inserts.add(com.bloxbean.cardano.client.plutus.spec
                    .ConstrPlutusData.of(0,
                            com.bloxbean.cardano.client.plutus.spec
                                    .BytesPlutusData.of(insert.claimId()),
                            PlutusData.deserialize(insert.proofWire())));
        }
        PlutusData shardRedeemer =
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                        com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(
                                inserts.toArray(new PlutusData[0])));

        // --- locate the live thread/vault UTxOs --------------------------
        Utxo vaultUtxo = utxoSupplier.getAll(plan.vaultAddress()).getFirst();
        String shardUnit = plan.shardThreadPolicyIdHex() + "00";
        Utxo shardUtxo = utxoSupplier.getAll(plan.shardAddress()).stream()
                .filter(utxo -> utxo.getAmount().stream().anyMatch(
                        amount -> shardUnit.equals(amount.getUnit())))
                .findFirst().orElseThrow();
        Utxo rootUtxo = utxoSupplier.getAll(plan.rootAddress()).getFirst();

        // Remainder conservation: 20 ADA in - (7 payout + 2 bounty) = 11 ADA.
        BigInteger remainder = BigInteger.valueOf(VAULT_GENESIS_LOVELACE)
                .subtract(payoutOne).subtract(payoutTwo)
                .subtract(bounty).subtract(bounty);
        com.bloxbean.cardano.yano.appchain.eutxo.contracts
                .EutxoBatchSettlementMarker marker =
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoBatchSettlementMarker(1, List.of(
                        HexFormat.of().formatHex(claimOne),
                        HexFormat.of().formatHex(claimTwo)));
        EutxoShardDatum nextShardDatum =
                plan.shardDatums().getFirst().withRoot(insertPlan.nextRoot());

        String settleTx = submitScriptWithMembers(new ScriptTx()
                .collectFrom(vaultUtxo, vaultRedeemer)
                .collectFrom(shardUtxo, shardRedeemer)
                .readFrom(rootUtxo)
                .payToAddress(payout.enterpriseAddress(),
                        Amount.lovelace(payoutOne))
                .payToAddress(payout.enterpriseAddress(),
                        Amount.lovelace(payoutTwo))
                .payToContract(plan.vaultAddress(),
                        List.of(Amount.lovelace(remainder)),
                        PlutusData.deserialize(marker.encode()))
                .payToContract(plan.shardAddress(),
                        List.of(Amount.lovelace(
                                        BigInteger.valueOf(THREAD_LOVELACE)),
                                new Amount(shardUnit, BigInteger.ONE)),
                        PlutusData.deserialize(nextShardDatum.encode()))
                .attachSpendingValidator(plan.vaultScript())
                .attachSpendingValidator(plan.shardScript()));

        // --- verify the settled state on the L1 ---------------------------
        checkIfUtxoAvailable(settleTx, plan.vaultAddress());
        Utxo settledVault = utxoSupplier.getAll(plan.vaultAddress()).stream()
                .filter(utxo -> settleTx.equals(utxo.getTxHash()))
                .findFirst().orElseThrow();
        assertEquals(remainder, settledVault.getAmount().getFirst()
                .getQuantity(), "vault remainder conserved");

        Utxo settledShard = utxoSupplier.getAll(plan.shardAddress()).stream()
                .filter(utxo -> settleTx.equals(utxo.getTxHash()))
                .findFirst().orElseThrow();
        EutxoShardDatum onChain = EutxoShardDatum.decode(
                HexFormat.of().parseHex(settledShard.getInlineDatum()));
        assertEquals(0, onChain.shardIndex());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                insertPlan.nextRoot(), onChain.nullifierRoot(),
                "on-chain shard root advanced to the mirror's post-insert root");

        long paid = utxoSupplier.getAll(payout.enterpriseAddress()).stream()
                .filter(utxo -> settleTx.equals(utxo.getTxHash()))
                .count();
        assertEquals(2, paid, "both positional payouts landed");
        log.info("A2 settle E2E complete: {} (shard root {} -> {})", settleTx,
                HexFormat.of().formatHex(insertPlan.priorRoot()),
                HexFormat.of().formatHex(insertPlan.nextRoot()));
    }

    private static PlutusData destinationData(Account account) {
        byte[] keyHash = com.bloxbean.cardano.client.crypto.Blake2bUtil
                .blake2bHash224(account.publicKeyBytes());
        return com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                        com.bloxbean.cardano.client.plutus.spec
                                .BytesPlutusData.of(keyHash)),
                com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(1));
    }

    private static PlutusData claimData(
            long sequence, byte[] claimId, PlutusData destination,
            BigInteger payoutLovelace, BigInteger bounty) {
        return com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                BigIntPlutusData.of(0),
                BigIntPlutusData.of(sequence),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
                        .of(claimId),
                destination,
                BigIntPlutusData.of(payoutLovelace),
                BigIntPlutusData.of(bounty));
    }

    private String submitScriptWithMembers(ScriptTx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .feePayer(operator.enterpriseAddress())
                .withRequiredSigners(
                        com.bloxbean.cardano.client.crypto.Blake2bUtil
                                .blake2bHash224(memberOne.publicKeyBytes()),
                        com.bloxbean.cardano.client.crypto.Blake2bUtil
                                .blake2bHash224(memberTwo.publicKeyBytes()))
                .withSigner(SignerProviders.signerFrom(operator))
                .withSigner(SignerProviders.signerFrom(memberOne))
                .withSigner(SignerProviders.signerFrom(memberTwo))
                .complete();
        assertTrue(result.isSuccessful(),
                "A2 settle transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }

    private Utxo utxoAt(String transactionId, int index) {
        return utxoSupplier.getAll(operator.enterpriseAddress()).stream()
                .filter(utxo -> transactionId.equals(utxo.getTxHash())
                        && utxo.getOutputIndex() == index)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "seed outpoint " + transactionId + "#" + index
                                + " not found"));
    }

    private String submit(Tx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(operator))
                .complete();
        assertTrue(result.isSuccessful(),
                "Cardano transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }

    private String submitScript(ScriptTx tx) throws Exception {
        Result<String> result = quickTxBuilder.compose(tx)
                .feePayer(operator.enterpriseAddress())
                .withSigner(SignerProviders.signerFrom(operator))
                .complete();
        assertTrue(result.isSuccessful(),
                "Cardano script transaction failed: " + result.getResponse());
        waitForTransaction(result);
        return result.getValue();
    }
}
