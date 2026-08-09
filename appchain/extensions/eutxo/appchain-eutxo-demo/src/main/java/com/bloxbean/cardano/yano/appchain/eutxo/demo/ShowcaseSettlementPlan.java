package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-UTXO-009: the SHOWCASE settlement chain identity — everything the
 * packaged devnet showcase needs, computed statically. Determinism rests on
 * three public facts of the demo environment:
 * <ol>
 *   <li>the devnet genesis funds the demo mnemonic's account-0 enterprise
 *       address at {@code blake2b256(addressBytes)#0},</li>
 *   <li>the cluster's member identities are the fixed demo seeds
 *       {@code byte(i+1) × 32} (public, demo-only),</li>
 *   <li>the showcase actor keys derive from
 *       {@code sha256("yano-showcase-demo-actor:<name>")}.</li>
 * </ol>
 * From those, the one-shot SEED transaction — and therefore the entire
 * deploy identity (policies, vault/shard/root addresses, parameterized
 * scripts) — is a pure function, so the packaged chain config is static and
 * a golden test pins it. On preprod the same machinery runs with RUNTIME
 * seeds instead: {@link #configProperties} renders the chain-config block
 * for whatever plan the bootstrap produced.
 *
 * <p>ALL keys here are PUBLIC demo material. Demo amounts only.
 */
public final class ShowcaseSettlementPlan {
    public static final String CHAIN_ID = "payment-chain-settlement";
    public static final String ROOT_TOKEN = "YanoSettleRoot";
    public static final int THRESHOLD = 2;

    /** Devnet demo mnemonic (public; the devnet genesis funds account 0). */
    public static final String DEVNET_MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple prize happy "
                    + "toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

    /** The 3-node light cluster's member identities (seeds 01/02/03 × 32). */
    public static final List<String> CLUSTER_MEMBERS = List.of(
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1");

    /** L1 operator (fees/collateral/bootstrap): raw Ed25519 demo actor key. */
    public static final byte[] OPERATOR_SEED =
            actorSeed("settlement-operator");
    public static final String OPERATOR_ADDRESS = enterprise(OPERATOR_SEED);

    /** L1 payout destination for demo withdrawals. */
    public static final byte[] PAYOUT_SEED = actorSeed("settlement-payout");
    public static final String PAYOUT_ADDRESS = enterprise(PAYOUT_SEED);

    /** The L2 address whose incoming funds FORM withdrawal claims. */
    public static final byte[] WITHDRAWAL_L2_SEED =
            actorSeed("settlement-withdrawal");

    /** Demo depositor's L2 identity. */
    public static final byte[] DEPOSITOR_L2_SEED =
            actorSeed("settlement-depositor");

    private static final long SEED_LOVELACE = 5_000_000L;
    private static final long SEED_TX_FEE = 200_000L;

    /** The deterministic devnet seed transaction (see class doc). */
    public static final byte[] SEED_TX_BYTES = buildDevnetSeedTx();
    public static final String SEED_TX_ID =
            TransactionUtil.getTxHash(SEED_TX_BYTES);

    /**
     * The showcase fallback delay: the DEVNET profile's relaxed floor
     * (ADR-UTXO-009 §13.2), so the permissionless A3 exit arms within a
     * demo — ~12 seconds at the devnet's 0.2s slots.
     */
    public static final long FALLBACK_DELAY_SLOTS =
            EutxoProfile.V3_DEVNET_FALLBACK_DELAY_MIN_SLOTS;

    /** The packaged showcase plan (devnet-only profile). */
    public static final SettlementBootstrapPlan PLAN =
            SettlementBootstrapPlan.plan(
                    new EutxoOutpoint(SEED_TX_ID, 0),
                    new EutxoOutpoint(SEED_TX_ID, 1),
                    new SettlementBootstrapPlan.Config(
                            CHAIN_ID, 0, Networks.testnet(),
                            ROOT_TOKEN.getBytes(StandardCharsets.UTF_8),
                            CLUSTER_MEMBERS, THRESHOLD, 0,
                            FALLBACK_DELAY_SLOTS, EutxoProfile.V3_DEVNET));

    private ShowcaseSettlementPlan() {
    }

    /**
     * The chain-config properties for a settlement chain built from
     * {@code plan} — the exact keys the v3 machine, the observers, and the
     * {@code eutxo-settlement} executor factory consume. Works for the
     * static devnet plan AND for a preprod plan produced at bootstrap time.
     * {@code owner} stays false here: the cluster launcher grants
     * {@code effects.executors.eutxo-settlement.owner=true} to exactly ONE
     * node (single-owner pinning).
     *
     * <p>Supply EITHER {@code operatorSeed} (inline hex — only ever for the
     * public devnet demo actor) or {@code operatorSeedFile} (the path to an
     * owner-only key file, for every public network).
     */
    public static Map<String, String> configProperties(
            SettlementBootstrapPlan plan, String chainId,
            String withdrawalL2Address, String operatorAddress,
            byte[] operatorSeed, String operatorSeedFile,
            String networkName, String scriptDir) {
        Map<String, String> config = new LinkedHashMap<>();
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF,
                stateGenesisId(plan, chainId));
        config.putAll(new java.util.TreeMap<>(identity.settings()));
        config.put("machines.eutxo.profile", plan.profile().id());
        config.put("machines.eutxo.expected-profile-digest",
                plan.profile().digestHex());
        config.put("machines.eutxo.network", networkName);
        config.put("machines.eutxo.bridge.params.fallback-delay-slots",
                Long.toString(plan.profile().fallbackDelayMinSlots()));
        config.put("machines.eutxo.bridge.observer-id", "bridge-deposits");
        config.put("machines.eutxo.bridge.vault-address", plan.vaultAddress());
        config.put("machines.eutxo.bridge.vault-script-hash",
                HexFormat.of().formatHex(plan.vaultScriptHash()));
        config.put("machines.eutxo.bridge.confirmation-observer-id",
                "bridge-withdrawals");
        config.put("machines.eutxo.bridge.withdrawal-address",
                withdrawalL2Address);
        config.put("machines.eutxo.bridge.epoch", "0");
        config.put("machines.eutxo.bridge.max-withdrawal-lovelace", "50000000");
        config.put("machines.eutxo.bridge.max-pending-withdrawals", "100");
        config.put("machines.eutxo.bridge.withdrawals-paused", "false");
        // Demo cadence: each claim settles in its own batch immediately.
        config.put("machines.eutxo.bridge.params.soft-batch-cap", "1");
        config.put("observers.bridge-deposits.type", "eutxo-vault-deposit-v1");
        config.put("observers.bridge-deposits.chain-id", chainId);
        config.put("observers.bridge-deposits.vault-address",
                plan.vaultAddress());
        config.put("observers.bridge-deposits.vault-script-hash",
                HexFormat.of().formatHex(plan.vaultScriptHash()));
        config.put("observers.bridge-deposits.max-lovelace", "100000000");
        config.put("observers.bridge-withdrawals.type",
                "eutxo-batch-withdrawal-confirmation-v1");
        config.put("observers.bridge-withdrawals.chain-id", chainId);
        config.put("observers.bridge-withdrawals.bridge-epoch", "0");
        config.put("observers.bridge-withdrawals.vault-address",
                plan.vaultAddress());
        config.put("effects.enabled", "true");
        config.put("effects.executor.enabled", "true");
        String executor = "effects.executors.eutxo-settlement.";
        config.put(executor + "owner", "false");
        config.put(executor + "vault-address", plan.vaultAddress());
        config.put(executor + "shard-address", plan.shardAddress());
        config.put(executor + "root-address", plan.rootAddress());
        config.put(executor + "root-unit", plan.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(
                ROOT_TOKEN.getBytes(StandardCharsets.UTF_8)));
        config.put(executor + "shard-thread-policy-id",
                plan.shardThreadPolicyIdHex());
        config.put(executor + "operator-address", operatorAddress);
        if (operatorSeedFile != null && !operatorSeedFile.isBlank()) {
            // A public-network operator's key NEVER enters the chain YAML:
            // the config names the owner-only file, and the wiring reads it.
            config.put(executor + "operator-seed-file", operatorSeedFile.trim());
        } else {
            config.put(executor + "operator-seed",
                    HexFormat.of().formatHex(java.util.Objects.requireNonNull(
                            operatorSeed, "operatorSeed (or operatorSeedFile)")));
        }
        if (scriptDir == null || scriptDir.isBlank()) {
            config.put(executor + "vault-script",
                    plan.vaultScript().getCborHex());
            config.put(executor + "shard-script",
                    plan.shardScript().getCborHex());
        } else {
            // Keep chain config readable: the parameterized validators are
            // ~22 KB of hex, so the bootstrap writes them beside the config
            // and the wiring reads them from there.
            config.put(executor + "vault-script-file",
                    scriptDir + "/settlement-vault.script");
            config.put(executor + "shard-script-file",
                    scriptDir + "/settlement-shard.script");
        }
        config.put(executor + "round-timeout-ms", "15000");
        return config;
    }

    private static byte[] stateGenesisId(SettlementBootstrapPlan plan, String chainId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("yano-showcase-settlement-state-genesis-v1\0"
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update(chainId.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.profile().digestHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.rootThreadPolicyIdHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.shardThreadPolicyIdHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.vaultScriptHash());
            return digest.digest();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Inline-seed, file-script variant — the packaged devnet demo chain. */
    public static Map<String, String> configProperties(
            SettlementBootstrapPlan plan, String chainId,
            String withdrawalL2Address, String operatorAddress,
            byte[] operatorSeed, String networkName, String scriptDir) {
        return configProperties(plan, chainId, withdrawalL2Address,
                operatorAddress, operatorSeed, null, networkName, scriptDir);
    }

    /** Inline-script variant (tests, small configs). */
    public static Map<String, String> configProperties(
            SettlementBootstrapPlan plan, String chainId,
            String withdrawalL2Address, String operatorAddress,
            byte[] operatorSeed, String networkName) {
        return configProperties(plan, chainId, withdrawalL2Address,
                operatorAddress, operatorSeed, null, networkName, null);
    }

    /**
     * The chains[] index a generated block is rendered with before it is
     * spliced into a real config — {@code chain add} renumbers it to the
     * instance's next free slot.
     */
    public static int chainIndexPlaceholder() {
        return 0;
    }

    /**
     * Write the parameterized validators next to a chain's config, for the
     * {@code *-script-file} wiring keys. Returns the directory.
     */
    public static java.nio.file.Path writeScripts(
            SettlementBootstrapPlan plan, java.nio.file.Path directory)
            throws java.io.IOException {
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(
                directory.resolve("settlement-vault.script"),
                plan.vaultScript().getCborHex());
        java.nio.file.Files.writeString(
                directory.resolve("settlement-shard.script"),
                plan.shardScript().getCborHex());
        return directory;
    }

    /**
     * Render the properties as a chains[index] YAML block, NESTED on the
     * dots so it reads like every other chain in the packaged config (and
     * so it cannot depend on how the config layer treats dotted keys).
     */
    public static String yamlBlock(int chainIndex, String chainId,
                                   Map<String, String> properties) {
        Map<String, Object> tree = new java.util.LinkedHashMap<>();
        tree.put("chain-id", chainId);
        tree.put("state-machine", "eutxo-ledger");
        tree.put("membership", new java.util.LinkedHashMap<>(
                Map.of("mode", "governed")));
        tree.put("block", new java.util.LinkedHashMap<>(
                Map.of("interval-ms", 1000)));
        tree.put("l1", new java.util.LinkedHashMap<>(
                Map.of("stability-depth", 2)));
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            insert(tree, entry.getKey().split("\\."), 0, entry.getValue());
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("    chains[").append(chainIndex).append("]:\n");
        render(yaml, tree, "      ");
        return yaml.toString();
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> node, String[] path,
                               int index, String value) {
        String key = path[index];
        if (index == path.length - 1) {
            node.put(key, value);
            return;
        }
        Object child = node.computeIfAbsent(key,
                ignored -> new java.util.LinkedHashMap<String, Object>());
        if (!(child instanceof Map)) {
            throw new IllegalStateException(
                    "settlement config key collides with a scalar: " + key);
        }
        insert((Map<String, Object>) child, path, index + 1, value);
    }

    @SuppressWarnings("unchecked")
    private static void render(StringBuilder yaml, Map<String, Object> node,
                               String indent) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            // Keys with a '[' (none today) or a leading '~' would need
            // quoting; plain config keys do not.
            if (entry.getValue() instanceof Map<?, ?> child) {
                yaml.append(indent).append(key).append(":\n");
                render(yaml, (Map<String, Object>) child, indent + "  ");
            } else {
                yaml.append(indent).append(key).append(": \"")
                        .append(entry.getValue()).append("\"\n");
            }
        }
    }

    static byte[] actorSeed(String actor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("yano-showcase-demo-actor:" + actor)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String enterprise(byte[] seed) {
        return AddressProvider.getEntAddress(
                Credential.fromKey(Blake2bUtil.blake2bHash224(
                        KeyGenUtil.getPublicKeyFromPrivateKey(seed))),
                Networks.testnet()).getAddress();
    }

    private static byte[] buildDevnetSeedTx() {
        try {
            Account genesisAccount = new Account(
                    Networks.testnet(), DEVNET_MNEMONIC, 0);
            Address address = new Address(genesisAccount.enterpriseAddress());
            String genesisTxHash = HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(address.getBytes()));
            // The devnet genesis funds this address with exactly 10,000,000
            // ADA (config/network/devnet/shelley-genesis.json initialFunds);
            // the bootstrap asserts the resulting txid, so drift fails loudly.
            BigInteger genesisLovelace = BigInteger.valueOf(10_000_000_000_000L);
            BigInteger fee = BigInteger.valueOf(SEED_TX_FEE);
            BigInteger seedValue = BigInteger.valueOf(SEED_LOVELACE);
            BigInteger operatorValue = genesisLovelace
                    .subtract(seedValue).subtract(seedValue).subtract(fee);
            TransactionBody body = TransactionBody.builder()
                    .inputs(List.of(new TransactionInput(genesisTxHash, 0)))
                    .outputs(List.of(
                            plainOutput(OPERATOR_ADDRESS, seedValue),
                            plainOutput(OPERATOR_ADDRESS, seedValue),
                            plainOutput(OPERATOR_ADDRESS, operatorValue)))
                    .fee(fee)
                    .build();
            Transaction unsigned = Transaction.builder()
                    .body(body)
                    .witnessSet(new TransactionWitnessSet())
                    .build();
            return genesisAccount.sign(unsigned).serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot build the showcase seed transaction", failure);
        }
    }

    private static TransactionOutput plainOutput(
            String address, BigInteger lovelace) {
        return TransactionOutput.builder()
                .address(address)
                .value(Value.fromCoin(lovelace))
                .build();
    }
}
