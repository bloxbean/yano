package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-008: the packaged light profile's bridge chain pins PUBLIC
 * deterministic demo identities. This golden test re-derives them from the
 * seed formula and fails when the yml drifts from the derivation (or vice
 * versa), keeping config, docs, and attach-mode tooling on one identity.
 */
class ShowcaseBridgeChainConfigTest {
    private static final Path CONFIG = Path.of(
            "src/main/showcase/config/application-appchain.yml");

    @Test
    void bridgeChainPinsDeterministicDemoIdentities() throws Exception {
        EutxoKeyWallet operator = EutxoKeyWallet.fromSeed(demoSeed("bridge-operator"));
        EutxoKeyWallet payout = EutxoKeyWallet.fromSeed(demoSeed("bridge-payout"));
        ScriptPubkey vault = ScriptPubkey.create(
                VerificationKey.create(operator.verificationKey().getBytes()));
        String vaultAddress = AddressProvider.getEntAddress(
                vault, Networks.testnet()).toBech32();

        String yml = Files.readString(CONFIG);
        assertThat(yml).contains("chain-id: \"payment-chain-l1bridge\"");
        assertThat(yml).contains(
                "vault-address: " + vaultAddress,
                "vault-script-hash: " + vault.getPolicyId(),
                "withdrawal-address: " + payout.address());
        // Deposits credit L2 owners from the inline datum; no virtual genesis
        // may coexist with the bridge (capability-catalog conflict).
        String bridgeChain = yml.substring(yml.indexOf("chains[10]:"));
        assertThat(bridgeChain).doesNotContain("genesis:");
        assertThat(bridgeChain).contains(
                "state-machine: eutxo-ledger",
                "stability-depth: 2",
                "observer-id: bridge-deposits",
                "confirmation-observer-id: bridge-withdrawals",
                "type: eutxo-vault-deposit-v1",
                "type: eutxo-withdrawal-confirmation-v1",
                "profile: yano-eutxo-v2-plutus-v3");
        // The observers and their domain routes only activate when their
        // bundles are allow-listed.
        assertThat(yml).contains(
                "- com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano",
                "- com.bloxbean.cardano.yano.appchain.eutxo.indexer");
    }

    private static byte[] demoSeed(String actor) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                ("yano-showcase-demo-actor:" + actor)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
