package com.bloxbean.cardano.yano.appchain.roles.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleWorkflowCliTest {
    @Test
    void signsOfflineFromASeedFileWithoutEmittingTheSeed(@TempDir Path directory) throws Exception {
        Path seed = directory.resolve("actor.seed");
        String encodedSeed = "01".repeat(32);
        Files.writeString(seed, encodedSeed + "\n");
        String publicKey = RoleWorkflowCli.run(new String[]{
                "public-key", "--seed-file", seed.toString()});
        String command = RoleWorkflowCli.run(new String[]{
                "sign", "--action", "propose", "--chain", "demo-chain",
                "--proposal", "evidence-1", "--policy", "evidence-release",
                "--policy-revision", "1", "--payload-domain", "evidence.release.v1",
                "--payload-hash", "22".repeat(32), "--deadline-height", "100",
                "--actor", "manufacturer-a", "--actor-revision", "1",
                "--key", "key-1", "--seed-file", seed.toString()});

        SignedActorCommandV1 decoded = SignedActorCommandV1.decode(
                HexFormat.of().parseHex(command));
        assertThat(decoded.verify(HexFormat.of().parseHex(publicKey))).isTrue();
        assertThat(command).doesNotContain(encodedSeed);
    }

    @Test
    void rejectsInlineSecretsAndUnknownOptions(@TempDir Path directory) throws Exception {
        Path seed = directory.resolve("actor.seed");
        Files.writeString(seed, "01".repeat(32));
        assertThatThrownBy(() -> RoleWorkflowCli.run(new String[]{
                "public-key", "--seed", "01".repeat(32)}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleWorkflowCli.run(new String[]{
                "public-key", "--seed-file", seed.toString(), "--seed-file", seed.toString()}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
