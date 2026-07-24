package com.bloxbean.cardano.yano.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.ZerojPoseidonValidityProvider;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-member EUTxO validity chain hosted by the disposable L1 devnet. */
public final class EutxoZkDevnetTestProfile extends DevnetTestProfile {
    static final String CHAIN_ID = "payments";
    static final BigInteger L2_SECRET = BigInteger.valueOf(424242);
    static final long DEPOSIT_LOVELACE = 10_000_000L;

    private static final String PREFIX = "yano.app-chain.chains[0].";
    private static final String MEMBER =
            "8a88e3dd7409f195fd52db2d3cba5d72"
                    + "ca6709bf1d94121bf3748801b40f6f5c";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config =
                new LinkedHashMap<>(super.getConfigOverrides());
        config.put("yaci.plugins.enabled", "true");
        Account genesis = new Account(
                Networks.testnet(), BaseE2ETest.MNEMONIC, 180);
        Account payout = new Account(
                Networks.testnet(), BaseE2ETest.MNEMONIC, 181);
        ScriptPubkey vault = fundsVaultScript(genesis);
        String vaultAddress = AddressProvider.getEntAddress(
                vault, Networks.testnet()).toBech32();
        String vaultScriptHash = fundsVaultScriptHash(vault);

        config.put("yano.app-chain.enabled", "true");
        config.put(PREFIX + "chain-id", CHAIN_ID);
        config.put(PREFIX + "signing-key", "01".repeat(32));
        config.put(PREFIX + "members", MEMBER);
        config.put(PREFIX + "sequencer.proposer", MEMBER);
        config.put(PREFIX + "threshold", "1");
        config.put(PREFIX + "block.interval-ms", "500");
        config.put(PREFIX + "block.max-messages", "16");
        config.put(PREFIX + "state-machine", "eutxo-ledger");
        config.put(PREFIX + "anchor.enabled", "false");
        config.put(PREFIX + "l1.stability-depth", "2");
        config.put(PREFIX + "machines.eutxo.profile",
                EutxoProfile.V1.id());
        config.put(PREFIX + "machines.eutxo.expected-profile-digest",
                EutxoProfile.V1.digestHex());
        config.put(PREFIX + "machines.eutxo.network", "devnet");
        config.put(PREFIX + "machines.eutxo.genesis.l2-address",
                genesis.enterpriseAddress());
        config.put(PREFIX + "machines.eutxo.genesis.l2-public-key",
                HexFormat.of().formatHex(EdDSAJubjub
                        .keypairFromSecret(L2_SECRET).pk().toBytes()));
        config.put(PREFIX + "machines.eutxo.genesis.l2-key-epoch", "1");
        config.put(PREFIX + "machines.eutxo.validity.enabled", "true");
        config.put(PREFIX + "machines.eutxo.validity.provider",
                ZerojPoseidonValidityProvider.ID);
        ZerojPoseidonValidityProvider.requiredIdentitySettings()
                .forEach((key, value) ->
                        config.put(PREFIX + key, value));
        config.put(PREFIX + "machines.eutxo.validity.batch-profile",
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16.id());
        config.put(PREFIX + "machines.eutxo.validity."
                        + "batch-profile-digest",
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16.digest());
        config.put(PREFIX + "machines.eutxo.bridge.observer-id",
                "bridge-deposits");
        config.put(PREFIX + "machines.eutxo.bridge.vault-address",
                vaultAddress);
        config.put(PREFIX + "machines.eutxo.bridge.vault-script-hash",
                vaultScriptHash);
        config.put(PREFIX
                        + "machines.eutxo.bridge.confirmation-observer-id",
                "bridge-withdrawals");
        config.put(PREFIX + "machines.eutxo.bridge.withdrawal-address",
                payout.enterpriseAddress());
        config.put(PREFIX + "machines.eutxo.bridge.epoch", "0");
        config.put(PREFIX
                        + "machines.eutxo.bridge.max-withdrawal-lovelace",
                Long.toString(DEPOSIT_LOVELACE));
        config.put(PREFIX
                        + "machines.eutxo.bridge.max-pending-withdrawals",
                "16");
        config.put(PREFIX
                        + "machines.eutxo.bridge.withdrawals-paused",
                "false");
        config.put(PREFIX + "observers.bridge-deposits.type",
                "eutxo-vault-deposit-v1");
        config.put(PREFIX + "observers.bridge-deposits.chain-id",
                CHAIN_ID);
        config.put(PREFIX + "observers.bridge-deposits.vault-address",
                vaultAddress);
        config.put(PREFIX
                        + "observers.bridge-deposits.vault-script-hash",
                vaultScriptHash);
        config.put(PREFIX + "observers.bridge-deposits.max-lovelace",
                Long.toString(DEPOSIT_LOVELACE));
        config.put(PREFIX + "observers.bridge-withdrawals.type",
                "eutxo-withdrawal-confirmation-v1");
        config.put(PREFIX + "observers.bridge-withdrawals.chain-id",
                CHAIN_ID);
        config.put(PREFIX
                        + "observers.bridge-withdrawals.bridge-epoch",
                "0");
        config.put(PREFIX
                        + "observers.bridge-withdrawals.vault-address",
                vaultAddress);
        return Map.copyOf(config);
    }

    static ScriptPubkey fundsVaultScript(Account operator) {
        try {
            return ScriptPubkey.create(
                    VerificationKey.create(
                            operator.publicKeyBytes()));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot derive the devnet funds vault", failure);
        }
    }

    static String fundsVaultScriptHash(ScriptPubkey vault) {
        try {
            return vault.getPolicyId();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot derive the devnet funds-vault hash", failure);
        }
    }
}
