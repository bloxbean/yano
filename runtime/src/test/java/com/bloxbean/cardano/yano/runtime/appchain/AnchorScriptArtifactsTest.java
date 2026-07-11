package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig.AnchorScriptConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Artifact resolution + identity derivation (ADR 008.4 §2.4): the bundled
 * julc artifacts resolve from the classpath and script hash/address derive
 * from the parameterized UPLC — never from source.
 */
class AnchorScriptArtifactsTest {

    private static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    @Test
    void builtinArtifacts_resolveAndParameterize() {
        AnchorScriptArtifacts artifacts = new AnchorScriptArtifacts(AnchorScriptConfig.defaults());

        PlutusV3Script policy = artifacts.threadPolicy(fill(32, 0x5E), 1);
        byte[] policyId = AnchorScriptArtifacts.scriptHash(policy);
        assertThat(policyId).hasSize(28);

        PlutusV3Script validator = artifacts.validator(policyId);
        byte[] scriptHash = AnchorScriptArtifacts.scriptHash(validator);
        assertThat(scriptHash).hasSize(28);
        assertThat(scriptHash).isNotEqualTo(policyId);

        // Distinct seed → distinct policy id (one-shot identity)
        byte[] otherPolicyId = AnchorScriptArtifacts.scriptHash(
                artifacts.threadPolicy(fill(32, 0x5F), 1));
        assertThat(otherPolicyId).isNotEqualTo(policyId);

        // Distinct policy id → distinct validator hash (param baked in)
        byte[] otherScriptHash = AnchorScriptArtifacts.scriptHash(
                artifacts.validator(otherPolicyId));
        assertThat(otherScriptHash).isNotEqualTo(scriptHash);

        String address = AnchorScriptArtifacts.scriptAddress(validator, new Network(0, 42)).getAddress();
        assertThat(address).startsWith("addr_test1w"); // script payment credential
    }

    @Test
    void unknownRef_isRejected() {
        assertThatThrownBy(() -> new AnchorScriptArtifacts(
                new AnchorScriptConfig("aiken:something", "builtin:julc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported anchor script ref");
    }

    @Test
    void fileRef_loadsAikenArtifacts() {
        // The opt-in Aiken artifacts checked in beside their source must load
        // through the SAME production path (ADR 008.4 §2.4)
        String base = "../appchain/onchain/aiken/appchain-anchor/artifacts/";
        AnchorScriptArtifacts aiken = new AnchorScriptArtifacts(new AnchorScriptConfig(
                "file:" + base + "anchor-validator.plutus.json",
                "file:" + base + "thread-policy.plutus.json"));

        byte[] policyId = AnchorScriptArtifacts.scriptHash(aiken.threadPolicy(fill(32, 0x5E), 1));
        assertThat(policyId).hasSize(28);
        byte[] scriptHash = AnchorScriptArtifacts.scriptHash(aiken.validator(policyId));
        assertThat(scriptHash).hasSize(28);

        // Different implementation → different hashes than the julc builtin
        AnchorScriptArtifacts julc = new AnchorScriptArtifacts(AnchorScriptConfig.defaults());
        assertThat(policyId).isNotEqualTo(
                AnchorScriptArtifacts.scriptHash(julc.threadPolicy(fill(32, 0x5E), 1)));
    }

    @Test
    void hexRef_roundTripsAgainstBuiltin() {
        // Feeding the builtin's own cborHex via hex: must produce identical scripts
        String builtinHex = AnchorScriptArtifacts.resolve(
                AnchorScriptConfig.BUILTIN_JULC, AnchorScriptArtifacts.BUILTIN_VALIDATOR_RESOURCE);
        AnchorScriptArtifacts viaHex = new AnchorScriptArtifacts(
                new AnchorScriptConfig("hex:" + builtinHex, "builtin:julc"));
        AnchorScriptArtifacts viaBuiltin = new AnchorScriptArtifacts(AnchorScriptConfig.defaults());

        byte[] policyId = fill(28, 0x0F);
        assertThat(AnchorScriptArtifacts.scriptHash(viaHex.validator(policyId)))
                .isEqualTo(AnchorScriptArtifacts.scriptHash(viaBuiltin.validator(policyId)));
    }
}
