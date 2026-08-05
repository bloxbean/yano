package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoDemoWorkspaceTest {
    @TempDir
    Path temporary;

    @Test
    void createsOwnerBoundWorkspaceAndRestartsFromNonSecretManifest() throws Exception {
        Path root = temporary.resolve("demo");
        EutxoDemoWorkspace created = EutxoDemoWorkspace.create(
                options(root), new LedgerDemoScenarioProvider());

        assertThat(created.manifest().memberPublicKeys()).hasSize(3)
                .allMatch(key -> key.matches("[0-9a-f]{64}"));
        assertThat(created.manifest().secretReferences())
                .containsEntry("member0", "secrets/members/node0.env")
                .containsEntry("bobPayoutWallet", "secrets/cardano/payout.seed");
        assertThat(created.manifest().publicIdentities().get("bobAddress"))
                .isNotEqualTo(created.manifest().publicIdentities()
                        .get("bobPayoutAddress"));
        assertThat(created.manifest().publicIdentities().get("payoutAddress"))
                .isEqualTo(created.manifest().publicIdentities()
                        .get("bobPayoutAddress"));
        assertThat(created.root().resolve("secrets/members/node0.env")).isRegularFile();
        String manifest = Files.readString(root.resolve("demo.yaml"));
        String secret = Files.readString(root.resolve("secrets/members/node0.env"))
                .substring("YANO_APPCHAIN_SIGNING_KEY=".length()).trim();
        assertThat(manifest).doesNotContain(secret, "YANO_APPCHAIN_SIGNING_KEY");

        EutxoDemoWorkspace reopened = EutxoDemoWorkspace.open(root);
        assertThat(reopened.manifest()).isEqualTo(created.manifest());
        assertThat(reopened.journal().read()).isEmpty();
    }

    @Test
    void rejectsCollisionsSymlinksAndUnmarkedDirectories() throws Exception {
        Path collision = temporary.resolve("collision");
        Files.createDirectories(collision);
        Files.writeString(collision.resolve("owned.txt"), "user");
        assertThatThrownBy(() -> EutxoDemoWorkspace.create(
                options(collision), new LedgerDemoScenarioProvider()))
                .hasMessageContaining("must not already contain files");

        Path target = temporary.resolve("target");
        Files.createDirectories(target);
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
            assertThatThrownBy(() -> EutxoDemoWorkspace.create(
                    options(link), new LedgerDemoScenarioProvider()))
                    .hasMessageContaining("symbolic link");
        } catch (UnsupportedOperationException unsupported) {
            // Filesystem does not support symlinks.
        }

        assertThatThrownBy(() -> EutxoDemoWorkspace.open(target))
                .hasMessageContaining("verified Yano EUTxO demo workspace");
    }

    @Test
    void journalResumesMatchingRequestsAndRejectsConflictsAndConcurrentOwners()
            throws Exception {
        EutxoDemoWorkspace workspace = EutxoDemoWorkspace.create(
                options(temporary.resolve("journal")), new LedgerDemoScenarioProvider());
        EutxoDemoJournal journal = workspace.journal();

        EutxoDemoJournal.Entry planned = journal.plan("transfer-1", "transfer", "abc");
        EutxoDemoJournal.Entry built = journal.advance(
                "transfer-1", "transfer", "abc", EutxoDemoJournal.State.BUILT,
                Map.of("transactionDigest", "def"), null);
        assertThat(planned.state()).isEqualTo(EutxoDemoJournal.State.PLANNED);
        assertThat(built.publicArtifacts()).containsEntry("transactionDigest", "def");
        assertThat(new EutxoDemoJournal(workspace.root()).read())
                .containsEntry("transfer-1", built);
        assertThatThrownBy(() -> journal.plan("transfer-1", "transfer", "different"))
                .hasMessageContaining("different request");

        Path lock = workspace.root().resolve("runtime/locks/operation.lock");
        try (FileChannel channel = FileChannel.open(lock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThatThrownBy(() -> journal.plan("transfer-2", "transfer", "ghi"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void attachedWorkspaceImportsOperatorSeedAndPinsTarget() throws Exception {
        Path seedFile = temporary.resolve("operator.seed");
        String seedHex = "0102030405060708090a0b0c0d0e0f10"
                + "1112131415161718191a1b1c1d1e1f20";
        Files.writeString(seedFile, seedHex + "\n");
        Path root = temporary.resolve("attached");
        EutxoDemoWorkspace created = EutxoDemoWorkspace.create(
                attachedOptions(root, "http://127.0.0.1:27070", seedFile),
                new BridgeDemoScenarioProvider());

        assertThat(created.manifest().attached()).isTrue();
        assertThat(created.manifest().targetBase())
                .isEqualTo("http://127.0.0.1:27070");
        assertThat(Files.readString(
                root.resolve("secrets/cardano/operator.seed")).trim())
                .isEqualTo(seedHex);
        // Deterministic seed -> deterministic vault identity: this is what
        // lets the workspace settle against the target chain's vault.
        assertThat(created.manifest().publicIdentities().get("vaultAddress"))
                .startsWith("addr_test1");
        assertThat(created.manifest().publicIdentities().get("vaultScriptHash"))
                .matches("[0-9a-f]{56}");
        assertThat(created.manifest().publicIdentities().get("payoutAddress"))
                .isEqualTo("addr_test1vrpz48l78va55y3ewuv7p6na"
                        + "rrtgsw2ajq3ns9xx945e0vsmpxjls");

        EutxoDemoWorkspace reopened = EutxoDemoWorkspace.open(root);
        assertThat(reopened.manifest()).isEqualTo(created.manifest());

        Path second = temporary.resolve("attached-second");
        EutxoDemoWorkspace againFromSameSeed = EutxoDemoWorkspace.create(
                attachedOptions(second, "http://127.0.0.1:27070", seedFile),
                new BridgeDemoScenarioProvider());
        assertThat(againFromSameSeed.manifest().publicIdentities()
                .get("vaultAddress"))
                .isEqualTo(created.manifest().publicIdentities()
                        .get("vaultAddress"));
    }

    @Test
    void attachedWorkspaceRejectsMalformedOperatorSeeds() throws Exception {
        Path malformed = temporary.resolve("bad.seed");
        Files.writeString(malformed, "not-a-seed\n");
        assertThatThrownBy(() -> EutxoDemoWorkspace.create(
                attachedOptions(temporary.resolve("bad"),
                        "http://127.0.0.1:27070", malformed),
                new BridgeDemoScenarioProvider()))
                .hasMessageContaining("64 lowercase hex characters");

        assertThatThrownBy(() -> EutxoDemoWorkspace.create(
                attachedOptions(temporary.resolve("missing"),
                        "http://127.0.0.1:27070",
                        temporary.resolve("absent.seed")),
                new BridgeDemoScenarioProvider()))
                .hasMessageContaining("existing regular file");
    }

    @Test
    void verifiedWorkspaceCanBeResetButMarkerCannotAuthorizeBroadTarget() throws Exception {
        EutxoDemoWorkspace workspace = EutxoDemoWorkspace.create(
                options(temporary.resolve("resettable")), new LedgerDemoScenarioProvider());
        workspace.reset();
        assertThat(workspace.root()).doesNotExist();
    }

    private static EutxoDemoOptions options(Path root) {
        return new EutxoDemoOptions("setup", "ledger", root,
                "payments-eutxo", "payments-eutxo", 3, 1, 7070, 13337,
                null, null, null,
                null, null, null, 20_000_000L, null, null,
                EutxoDemoOptions.Format.TEXT, false, false);
    }

    private static EutxoDemoOptions attachedOptions(
            Path root, String targetBase, Path operatorSeedFile) {
        return new EutxoDemoOptions("setup", "bridge", root,
                "payment-chain-l1bridge", "payment-chain-l1bridge", 3, 1, 7070, 13337,
                targetBase, operatorSeedFile,
                "addr_test1vrpz48l78va55y3ewuv7p6narrtgsw2ajq3ns9xx945e0vsmpxjls",
                null, null, null, 20_000_000L, null, null,
                EutxoDemoOptions.Format.TEXT, false, false);
    }
}
