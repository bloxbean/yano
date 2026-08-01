package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AppChainStoragePathsTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultPathUsesTheProcessWorkingDirectory() {
        Path l1Path = tempDir.resolve("node0/chainstate");

        Path resolved = AppChainStoragePaths.resolve(l1Path.toString(), null);

        assertThat(resolved).isEqualTo(Path.of("appchain-state").toAbsolutePath());
    }

    @Test
    void customRelativePathUsesTheProcessWorkingDirectory() {
        Path l1Path = tempDir.resolve("node3/chainstate");

        Path resolved = AppChainStoragePaths.resolve(l1Path.toString(), "custom-app-state");

        assertThat(resolved).isEqualTo(Path.of("custom-app-state").toAbsolutePath());
    }

    @Test
    void absolutePathIsUsedAsConfigured() {
        Path configured = tempDir.resolve("shared/appchains");

        Path resolved = AppChainStoragePaths.resolve(
                tempDir.resolve("node0/chainstate").toString(), configured.toString());

        assertThat(resolved).isEqualTo(configured);
    }

    @Test
    void blankPathIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                        AppChainStoragePaths.resolve(
                                tempDir.resolve("chainstate").toString(), "  "))
                .withMessageContaining(YanoPropertyKeys.AppChain.STORAGE_PATH);
    }

    @Test
    void l1AndAppChainStorageCannotBeTheSameDirectory() {
        Path l1Path = tempDir.resolve("node0/chainstate");

        assertThatIllegalArgumentException().isThrownBy(() ->
                        AppChainStoragePaths.resolve(l1Path.toString(), l1Path.toString()))
                .withMessageContaining("must be different");
    }
}
