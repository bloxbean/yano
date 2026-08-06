package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M6: the checked-in deploy artifacts
 * ({@code src/main/resources/META-INF/plutus/*.plutus.json}) are the exact
 * UNPARAMETERIZED templates a deployment parameterizes (the anchor pattern —
 * deploy tooling loads the bundled artifact, never compiles from source).
 * This test pins them: a drift between the bundle and a fresh source compile
 * fails until the bundle is regenerated with
 * {@code -Dyano.regenerate.plutus=true}.
 *
 * <p>Deploy order (the SP-M6 ordering fix): mint the root-thread policy
 * (reused {@code AnchorThreadPolicy} artifact from appchain-anchor-onchain)
 * and {@code ShardThreadPolicy} from seed UTxOs → parameterize the vault
 * (root policy/name, SHARD THREAD POLICY id, prefix, domain, caps) → then
 * the shard validator (shard policy id, VAULT script hash) and the root
 * validator (root policy id/name).
 */
class SettlementArtifactBundleTest
        extends com.bloxbean.cardano.julc.testkit.ContractTest {
    private static final Path RESOURCES =
            Path.of("src/main/resources/META-INF/plutus");
    private static final Pattern CBOR_HEX =
            Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private record Artifact(Class<?> validator, String purpose, String file) {
    }

    private static final List<Artifact> ARTIFACTS = List.of(
            new Artifact(SettlementVaultValidator.class, "spending",
                    "SettlementVaultValidator.plutus.json"),
            new Artifact(NullifierShardValidator.class, "spending",
                    "NullifierShardValidator.plutus.json"),
            new Artifact(SettlementRootValidator.class, "spending",
                    "SettlementRootValidator.plutus.json"),
            new Artifact(ShardThreadPolicy.class, "minting",
                    "ShardThreadPolicy.plutus.json"));

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void bundledArtifactsMatchTheSourceCompile() throws Exception {
        boolean regenerate = Boolean.getBoolean("yano.regenerate.plutus");
        for (Artifact artifact : ARTIFACTS) {
            var compiled = ValidatorTest.compileValidator(artifact.validator());
            assertThat(compiled.hasErrors())
                    .as("%s compiles", artifact.validator().getSimpleName())
                    .isFalse();
            String cborHex = JulcScriptAdapter
                    .fromProgram(compiled.program()).getCborHex();
            Path file = RESOURCES.resolve(artifact.file());
            String json = """
                    {
                      "type": "PlutusScriptV3",
                      "purpose": "%s",
                      "description": "%s (unparameterized template, julc)",
                      "cborHex": "%s"
                    }
                    """.formatted(artifact.purpose(),
                    artifact.validator().getSimpleName(), cborHex);
            if (regenerate || !Files.exists(file)) {
                Files.createDirectories(RESOURCES);
                Files.writeString(file, json);
                System.out.println("[artifacts] wrote " + file);
                continue;
            }
            Matcher matcher = CBOR_HEX.matcher(
                    Files.readString(file, StandardCharsets.UTF_8));
            assertThat(matcher.find())
                    .as("%s carries cborHex", artifact.file()).isTrue();
            assertThat(matcher.group(1))
                    .as("%s matches the source compile (regenerate with "
                            + "-Dyano.regenerate.plutus=true)", artifact.file())
                    .isEqualTo(cborHex);
        }
    }
}
