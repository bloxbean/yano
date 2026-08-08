package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * ADR-UTXO-009: deploys a settlement identity's L1 side — the two one-shot
 * mints (root NFT + 16 shard thread tokens) and the genesis outputs (root
 * thread with the federation datum, 16 empty-root shard threads, the vault
 * fund) — through a node's Blockfrost-compatible API. Idempotent: if the
 * root thread already sits at the plan's root address, the bootstrap is
 * done and nothing is submitted.
 *
 * <p>Devnet: {@link #bootstrapShowcaseDevnet(String)} first submits the
 * DETERMINISTIC seed transaction ({@link ShowcaseSettlementPlan}) so the
 * packaged static chain config matches. Preprod: callers fund two seed
 * UTxOs themselves, compute the plan from them, and call
 * {@link #bootstrap(String, SettlementBootstrapPlan, byte[], String)} —
 * then apply the config block from
 * {@link ShowcaseSettlementPlan#configProperties}.
 */
public final class SettlementBootstrapWorkflow {
    public static final long ROOT_LOVELACE = 5_000_000L;
    public static final long THREAD_LOVELACE = 2_000_000L;
    public static final long VAULT_GENESIS_LOVELACE = 20_000_000L;
    /** Nullifier shards (one thread token each). */
    public static final int SHARD_COUNT = 16;
    /** Headroom for the three bootstrap transactions' fees and collateral. */
    public static final long FEE_HEADROOM_LOVELACE = 5_000_000L;

    /**
     * Lovelace the operator address must hold before {@code bootstrap} can
     * run: every genesis output plus fee headroom. The two one-shot seed
     * UTxOs are spent by the mints but their value returns as change, so they
     * are not counted twice.
     */
    public static long requiredFundingLovelace() {
        return ROOT_LOVELACE
                + (long) SHARD_COUNT * THREAD_LOVELACE
                + VAULT_GENESIS_LOVELACE
                + FEE_HEADROOM_LOVELACE;
    }

    private SettlementBootstrapWorkflow() {
    }

    /** Devnet showcase bootstrap: deterministic seed tx + full deploy. */
    public static String bootstrapShowcaseDevnet(String apiBase)
            throws Exception {
        BackendService backend = new BFBackendService(apiBase, "demo");
        if (bootstrapped(backend, ShowcaseSettlementPlan.PLAN)) {
            return "already-bootstrapped";
        }
        Result<String> seed = backend.getTransactionService()
                .submitTransaction(ShowcaseSettlementPlan.SEED_TX_BYTES);
        if (!seed.isSuccessful()) {
            throw new IllegalStateException(
                    "seed transaction rejected: " + seed.getResponse());
        }
        if (!ShowcaseSettlementPlan.SEED_TX_ID.equals(seed.getValue())) {
            throw new IllegalStateException(
                    "seed transaction id drifted from the packaged plan");
        }
        awaitUtxo(backend, ShowcaseSettlementPlan.OPERATOR_ADDRESS,
                ShowcaseSettlementPlan.SEED_TX_ID);
        return bootstrap(apiBase, ShowcaseSettlementPlan.PLAN,
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoOutpoint(ShowcaseSettlementPlan.SEED_TX_ID, 0),
                new com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoOutpoint(ShowcaseSettlementPlan.SEED_TX_ID, 1),
                ShowcaseSettlementPlan.OPERATOR_SEED,
                ShowcaseSettlementPlan.OPERATOR_ADDRESS);
    }

    /**
     * Deploy {@code plan}'s L1 side. The operator key funds fees/collateral
     * and must already own the plan's two seed UTxOs.
     */
    public static String bootstrap(
            String apiBase, SettlementBootstrapPlan plan,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint rootSeed,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint shardSeed,
            byte[] operatorSeed, String operatorAddress) throws Exception {
        Objects.requireNonNull(plan, "plan");
        BackendService backend = new BFBackendService(apiBase, "demo");
        if (bootstrapped(backend, plan)) {
            return "already-bootstrapped";
        }
        QuickTxBuilder quickTx = new QuickTxBuilder(backend);
        SecretKey operator = SecretKey.create(operatorSeed);

        String rootUnit = plan.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(
                ShowcaseSettlementPlan.ROOT_TOKEN.getBytes());
        submit(quickTx, operatorAddress, operator, new ScriptTx()
                .collectFrom(utxoAt(backend, operatorAddress, rootSeed))
                .mintAsset(plan.rootThreadPolicy(),
                        List.of(new Asset(ShowcaseSettlementPlan.ROOT_TOKEN,
                                BigInteger.ONE)),
                        BigIntPlutusData.of(0),
                        operatorAddress));
        List<Asset> shardAssets = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            shardAssets.add(new Asset(
                    "0x" + HexFormat.of().formatHex(new byte[] {(byte) index}),
                    BigInteger.ONE));
        }
        submit(quickTx, operatorAddress, operator, new ScriptTx()
                .collectFrom(utxoAt(backend, operatorAddress, shardSeed))
                .mintAsset(plan.shardThreadPolicy(), shardAssets,
                        BigIntPlutusData.of(0), operatorAddress));

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
        String genesisTx = submitPlain(quickTx, operatorAddress, operator,
                genesis.from(operatorAddress));
        awaitUtxo(backend, plan.rootAddress(), genesisTx);
        return genesisTx;
    }

    /** The root thread at the plan's root address = bootstrap complete. */
    public static boolean bootstrapped(
            BackendService backend, SettlementBootstrapPlan plan)
            throws Exception {
        String rootUnit = plan.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(
                ShowcaseSettlementPlan.ROOT_TOKEN.getBytes());
        return new DefaultUtxoSupplier(backend.getUtxoService())
                .getAll(plan.rootAddress()).stream()
                .anyMatch(utxo -> utxo.getAmount().stream().anyMatch(
                        amount -> rootUnit.equals(amount.getUnit())));
    }

    // ------------------------------------------------------------------

    /** Resolve a one-shot seed outpoint from the operator's UTxO set. */
    private static Utxo utxoAt(
            BackendService backend, String operatorAddress,
            com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint seed)
            throws Exception {
        return new DefaultUtxoSupplier(backend.getUtxoService())
                .getAll(operatorAddress).stream()
                .filter(utxo -> seed.transactionId().equals(utxo.getTxHash())
                        && utxo.getOutputIndex() == seed.index())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "seed UTxO " + seed
                                + " not found at the operator address"));
    }

    private static void submit(QuickTxBuilder quickTx, String operatorAddress,
                               SecretKey operator, ScriptTx tx)
            throws Exception {
        Result<String> result = quickTx.compose(tx)
                .feePayer(operatorAddress)
                .withSigner(SignerProviders.signerFrom(operator))
                .completeAndWait();
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    "bootstrap transaction failed: " + result.getResponse());
        }
    }

    private static String submitPlain(QuickTxBuilder quickTx,
                                      String operatorAddress,
                                      SecretKey operator, Tx tx)
            throws Exception {
        Result<String> result = quickTx.compose(tx)
                .withSigner(SignerProviders.signerFrom(operator))
                .completeAndWait();
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    "bootstrap genesis failed: " + result.getResponse());
        }
        return result.getValue();
    }

    private static void awaitUtxo(BackendService backend, String address,
                                  String transactionId) throws Exception {
        var supplier = new DefaultUtxoSupplier(backend.getUtxoService());
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (supplier.getAll(address).stream().anyMatch(
                    utxo -> transactionId.equals(utxo.getTxHash()))) {
                return;
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException(
                "transaction " + transactionId + " did not appear at " + address);
    }
}
