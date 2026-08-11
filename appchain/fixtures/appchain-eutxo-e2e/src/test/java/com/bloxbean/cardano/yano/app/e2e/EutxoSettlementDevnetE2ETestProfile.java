package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementBootstrapPlan;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-UTXO-009 SP-M6 effect-path gate profile: a v3 settlement chain whose
 * ENTIRE config — machine, observers, and the wired executor stack — is
 * static, because the bootstrap identity is deterministic: devnet faucet
 * UTxOs are synthetic with {@code txHash = blake2b256(addressUtf8 ‖
 * int64BE(nonce))} and the nonce counts from 1 on a fresh node, so the
 * first two faucet grants to the fixed operator address ARE the one-shot
 * bootstrap seeds, and the full deploy identity follows from the plan.
 */
public final class EutxoSettlementDevnetE2ETestProfile extends DevnetTestProfile {
    static final String CHAIN_ID = "payments-settlement";
    static final String PREFIX = "yano.app-chain.chains[0].";

    /** App-chain member: the node signs with this seed; pub = member id. */
    static final String MEMBER_SIGNING_SEED = "01".repeat(32);
    static final String MEMBER =
            "8a88e3dd7409f195fd52db2d3cba5d72"
                    + "ca6709bf1d94121bf3748801b40f6f5c";

    /** L1 operator: raw Ed25519 key (fees, collateral, bootstrap). */
    static final byte[] OPERATOR_SEED = filled(0x0B, 32);
    static final String OPERATOR_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(Blake2bUtil.blake2bHash224(
                    KeyGenUtil.getPublicKeyFromPrivateKey(OPERATOR_SEED))),
            Networks.testnet()).getAddress();

    /** L1 payout destination: raw Ed25519 key (enterprise). */
    static final byte[] PAYOUT_SEED = filled(0x0C, 32);
    static final String PAYOUT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(Blake2bUtil.blake2bHash224(
                    KeyGenUtil.getPublicKeyFromPrivateKey(PAYOUT_SEED))),
            Networks.testnet()).getAddress();

    static final String ROOT_TOKEN = "YanoSettleRoot";
    static final long DEPOSIT_LOVELACE = 12_000_000L;

    /**
     * The DETERMINISTIC seed transaction: it spends the genesis UTxO of the
     * devnet mnemonic's account 0 enterprise address (genesis UTxOs sit at
     * {@code blake2b256(addressBytes)#0} — a pure function of the genesis
     * file), pays the two one-shot seeds and the operator's working funds
     * with a FIXED fee, and is signed by the account — so its bytes, its id,
     * and therefore the ENTIRE deploy identity are computable before the
     * node exists. The test submits these exact bytes as its first act.
     */
    static final byte[] SEED_TX_BYTES = buildSeedTx();
    static final String SEED_TX_ID =
            com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(SEED_TX_BYTES);
    static final EutxoOutpoint SEED_ZERO = new EutxoOutpoint(SEED_TX_ID, 0);
    static final EutxoOutpoint SEED_ONE = new EutxoOutpoint(SEED_TX_ID, 1);

    static final SettlementBootstrapPlan PLAN = SettlementBootstrapPlan.plan(
            SEED_ZERO, SEED_ONE,
            new SettlementBootstrapPlan.Config(
                    CHAIN_ID, 0, Networks.testnet(),
                    ROOT_TOKEN.getBytes(StandardCharsets.UTF_8),
                    List.of(MEMBER), 1, 0, 86_400));

    private static byte[] buildSeedTx() {
        try {
            var genesisAccount = new com.bloxbean.cardano.client.account.Account(
                    Networks.testnet(), BaseE2ETest.MNEMONIC, 0);
            var address = new com.bloxbean.cardano.client.address.Address(
                    genesisAccount.enterpriseAddress());
            var genesisJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(java.nio.file.Files.readString(
                            DevnetTestProfile.configFile("shelley-genesis.json")));
            String addressHex = HexFormat.of().formatHex(address.getBytes());
            java.math.BigInteger genesisLovelace = new java.math.BigInteger(
                    genesisJson.get("initialFunds").get(addressHex).asText());
            String genesisTxHash = HexFormat.of().formatHex(
                    Blake2bUtil.blake2bHash256(address.getBytes()));

            java.math.BigInteger fee = java.math.BigInteger.valueOf(200_000L);
            java.math.BigInteger seedValue =
                    java.math.BigInteger.valueOf(5_000_000L);
            java.math.BigInteger operatorValue = genesisLovelace
                    .subtract(seedValue).subtract(seedValue).subtract(fee);
            var body = com.bloxbean.cardano.client.transaction.spec
                    .TransactionBody.builder()
                    .inputs(List.of(new com.bloxbean.cardano.client.transaction
                            .spec.TransactionInput(genesisTxHash, 0)))
                    .outputs(List.of(
                            output(OPERATOR_ADDRESS, seedValue),
                            output(OPERATOR_ADDRESS, seedValue),
                            output(OPERATOR_ADDRESS, operatorValue)))
                    .fee(fee)
                    .build();
            var unsigned = com.bloxbean.cardano.client.transaction.spec
                    .Transaction.builder()
                    .body(body)
                    .witnessSet(new com.bloxbean.cardano.client.transaction
                            .spec.TransactionWitnessSet())
                    .build();
            return genesisAccount.sign(unsigned).serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot build the deterministic seed transaction", failure);
        }
    }

    private static com.bloxbean.cardano.client.transaction.spec.TransactionOutput
            output(String address, java.math.BigInteger lovelace) {
        return com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                .builder()
                .address(address)
                .value(com.bloxbean.cardano.client.transaction.spec.Value
                        .fromCoin(lovelace))
                .build();
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config =
                new LinkedHashMap<>(super.getConfigOverrides());
        config.put("yano.plugins.enabled", "true");
        config.put("yano.app-chain.enabled", "true");
        // Fresh app-chain state per run — the default appchain-chainstate/ dir
        // would leak L2 state across devnet restarts.
        config.put("yano.app-chain.storage.path",
                DevnetTestProfile.TEMP_STORAGE_DIR
                        .resolve("appchain-chainstate").toString());
        config.put(PREFIX + "chain-id", CHAIN_ID);
        config.put(PREFIX + "signing-key", MEMBER_SIGNING_SEED);
        config.put(PREFIX + "members", MEMBER);
        config.put(PREFIX + "sequencer.proposer", MEMBER);
        config.put(PREFIX + "threshold", "1");
        config.put(PREFIX + "block.interval-ms", "500");
        config.put(PREFIX + "block.max-messages", "16");
        config.put(PREFIX + "state-machine", "eutxo-ledger");
        config.put(PREFIX + "anchor.enabled", "false");
        config.put(PREFIX + "l1.stability-depth", "2");

        config.put(PREFIX + "machines.eutxo.profile", EutxoProfile.V3.id());
        config.put(PREFIX + "machines.eutxo.expected-profile-digest",
                EutxoProfile.V3.digestHex());
        config.put(PREFIX + "machines.eutxo.network", "devnet");
        config.put(PREFIX + "machines.eutxo.bridge.observer-id",
                "bridge-deposits");
        config.put(PREFIX + "machines.eutxo.bridge.vault-address",
                PLAN.vaultAddress());
        config.put(PREFIX + "machines.eutxo.bridge.vault-script-hash",
                HexFormat.of().formatHex(PLAN.vaultScriptHash()));
        config.put(PREFIX + "machines.eutxo.bridge.confirmation-observer-id",
                "bridge-withdrawals");
        config.put(PREFIX + "machines.eutxo.bridge.withdrawal-address",
                withdrawalL2Address());
        config.put(PREFIX + "machines.eutxo.bridge.epoch", "0");
        config.put(PREFIX + "machines.eutxo.bridge.max-withdrawal-lovelace",
                Long.toString(DEPOSIT_LOVELACE));
        config.put(PREFIX + "machines.eutxo.bridge.max-pending-withdrawals",
                "16");
        config.put(PREFIX + "machines.eutxo.bridge.withdrawals-paused",
                "false");
        // The gate settles a batch of ONE: the soft cap fires the effect in
        // the claim's own block (the chain only mints blocks for messages,
        // so an elapsed-window trigger would wait forever on a quiet chain).
        config.put(PREFIX + "machines.eutxo.bridge.params.soft-batch-cap", "1");

        config.put(PREFIX + "observers.bridge-deposits.type",
                "eutxo-vault-deposit-v1");
        config.put(PREFIX + "observers.bridge-deposits.chain-id", CHAIN_ID);
        config.put(PREFIX + "observers.bridge-deposits.vault-address",
                PLAN.vaultAddress());
        config.put(PREFIX + "observers.bridge-deposits.vault-script-hash",
                HexFormat.of().formatHex(PLAN.vaultScriptHash()));
        config.put(PREFIX + "observers.bridge-deposits.max-lovelace",
                Long.toString(DEPOSIT_LOVELACE * 2));
        config.put(PREFIX + "observers.bridge-withdrawals.type",
                "eutxo-batch-withdrawal-confirmation-v1");
        config.put(PREFIX + "observers.bridge-withdrawals.chain-id", CHAIN_ID);
        config.put(PREFIX + "observers.bridge-withdrawals.bridge-epoch", "0");
        config.put(PREFIX + "observers.bridge-withdrawals.vault-address",
                PLAN.vaultAddress());

        config.put(PREFIX + "effects.enabled", "true");
        config.put(PREFIX + "effects.executor.enabled", "true");
        String executor = PREFIX + "effects.executors.eutxo-settlement.";
        config.put(executor + "owner", "true");
        config.put(executor + "vault-address", PLAN.vaultAddress());
        config.put(executor + "shard-address", PLAN.shardAddress());
        config.put(executor + "root-address", PLAN.rootAddress());
        config.put(executor + "root-unit", PLAN.rootThreadPolicyIdHex()
                + HexFormat.of().formatHex(
                ROOT_TOKEN.getBytes(StandardCharsets.UTF_8)));
        config.put(executor + "shard-thread-policy-id",
                PLAN.shardThreadPolicyIdHex());
        config.put(executor + "operator-address", OPERATOR_ADDRESS);
        config.put(executor + "operator-seed",
                HexFormat.of().formatHex(OPERATOR_SEED));
        config.put(executor + "vault-script",
                PLAN.vaultScript().getCborHex());
        config.put(executor + "shard-script",
                PLAN.shardScript().getCborHex());
        config.put(executor + "round-timeout-ms", "15000");
        return config;
    }

    /** The L2 address whose incoming funds FORM withdrawal claims. */
    static String withdrawalL2Address() {
        return com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet
                .fromSeed(filled(0x0D, 32)).address();
    }

    static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
