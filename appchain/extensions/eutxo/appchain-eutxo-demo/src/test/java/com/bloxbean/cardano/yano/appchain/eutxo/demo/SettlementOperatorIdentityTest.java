package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 §13.2: an operator-supplied settlement key. The seed is a
 * spending key for real funds, so the tests that matter are about where it is
 * allowed to appear.
 */
class SettlementOperatorIdentityTest {
    private static final String SEED = "11".repeat(32);

    @TempDir
    Path directory;

    @Test
    void derivesDistinctActorsFromOneSeed() throws Exception {
        SettlementOperatorIdentity identity =
                SettlementOperatorIdentity.fromKeyFile(keyFile(SEED));

        assertThat(identity.operatorAddress()).startsWith("addr_test1");
        // Payout returns to the key that funded the deployment.
        assertThat(identity.payoutAddress()).isEqualTo(identity.operatorAddress());
        assertThat(Set.of(
                HexFormat.of().formatHex(identity.operatorSeed()),
                HexFormat.of().formatHex(identity.depositorL2Seed()),
                HexFormat.of().formatHex(identity.withdrawalL2Seed())))
                .as("each actor needs its own key")
                .hasSize(3);
    }

    @Test
    void isDeterministicAndDistinctFromTheDemoActors() throws Exception {
        SettlementOperatorIdentity first =
                SettlementOperatorIdentity.fromKeyFile(keyFile(SEED));
        SettlementOperatorIdentity again =
                SettlementOperatorIdentity.fromKeyFile(keyFile(SEED));

        assertThat(again.operatorAddress()).isEqualTo(first.operatorAddress());
        assertThat(first.operatorAddress())
                .isNotEqualTo(SettlementOperatorIdentity.demo().operatorAddress());
    }

    @Test
    void theOperatorSeedNeverReachesTheChainConfig() throws Exception {
        Path key = keyFile(SEED);
        SettlementOperatorIdentity identity =
                SettlementOperatorIdentity.fromKeyFile(key);

        Map<String, String> config = ShowcaseSettlementPlan.configProperties(
                ShowcaseSettlementPlan.PLAN, ShowcaseSettlementPlan.CHAIN_ID,
                SettlementDeployment.withdrawalAddress(identity),
                identity.operatorAddress(),
                null, key.toAbsolutePath().toString(),
                "preprod", directory.toString());

        String executor = "effects.executors.eutxo-settlement.";
        assertThat(config).containsEntry(executor + "operator-seed-file",
                key.toAbsolutePath().toString());
        assertThat(config).doesNotContainKey(executor + "operator-seed");
        assertThat(String.join("\n", config.values()))
                .as("no rendering of the config may embed the raw seed")
                .doesNotContain(SEED);
    }

    @Test
    void rejectsAnythingThatIsNotARawSeed() throws Exception {
        assertThatThrownBy(() -> SettlementOperatorIdentity.fromKeyFile(
                keyFile("not-a-seed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64-hex");
        // A mnemonic is the most likely thing a user reaches for.
        assertThatThrownBy(() -> SettlementOperatorIdentity.fromKeyFile(
                keyFile("test test test test test test test test test test "
                        + "test test test test test test test test test test "
                        + "test test test sauce")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mnemonic");
        assertThatThrownBy(() -> SettlementOperatorIdentity.fromKeyFile(
                directory.resolve("absent.seed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsAKeyOtherUsersCanRead() throws Exception {
        Path key = keyFile(SEED);
        try {
            Files.setPosixFilePermissions(key, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ));
        } catch (UnsupportedOperationException noPosix) {
            return;
        }
        assertThatThrownBy(() -> SettlementOperatorIdentity.fromKeyFile(key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chmod 600");
    }

    /** Public networks get the production floor, never the relaxed demo one. */
    @Test
    void productionProfileCarriesTheRealFallbackFloor() {
        assertThat(EutxoProfile.V3.fallbackDelayMinSlots()).isEqualTo(21_600L);
        assertThat(EutxoProfile.V3.devnetOnly()).isFalse();
        assertThat(EutxoProfile.V3_DEVNET.devnetOnly()).isTrue();
    }

    /**
     * {@code prepare} is run BEFORE any funding exists and typically before
     * the cluster is even up. It must therefore touch no node at all — the
     * address and the amount are pure derivation.
     */
    @Test
    void prepareNeedsNoNode() throws Exception {
        SettlementOperatorIdentity identity =
                SettlementOperatorIdentity.fromKeyFile(keyFile(SEED));

        Map<String, Object> payload =
                SettlementDeployment.prepare(identity, "preprod");

        assertThat(payload).containsEntry(
                "fundThisAddress", identity.operatorAddress());
        assertThat(payload).containsEntry("requiredLovelace",
                SettlementBootstrapWorkflow.requiredFundingLovelace());
        assertThat(payload).containsEntry("minimumUtxos",
                SettlementDeployment.MIN_FUNDING_UTXOS);
        assertThat(payload).containsEntry("profile",
                EutxoProfile.V3.id());
        assertThat(String.valueOf(payload.get("note")))
                .contains("settlement bootstrap");
    }

    @Test
    void fundingRequirementCoversEveryGenesisOutput() {
        long expected = SettlementBootstrapWorkflow.ROOT_LOVELACE
                + 16 * SettlementBootstrapWorkflow.THREAD_LOVELACE
                + SettlementBootstrapWorkflow.VAULT_GENESIS_LOVELACE
                + SettlementBootstrapWorkflow.FEE_HEADROOM_LOVELACE;
        assertThat(SettlementBootstrapWorkflow.requiredFundingLovelace())
                .isEqualTo(expected);
    }

    private Path keyFile(String contents) throws Exception {
        Path key = directory.resolve("operator-" + contents.hashCode() + ".seed");
        Files.writeString(key, contents + "\n");
        try {
            Files.setPosixFilePermissions(key, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException noPosix) {
            // Non-POSIX filesystem.
        }
        return key;
    }
}
