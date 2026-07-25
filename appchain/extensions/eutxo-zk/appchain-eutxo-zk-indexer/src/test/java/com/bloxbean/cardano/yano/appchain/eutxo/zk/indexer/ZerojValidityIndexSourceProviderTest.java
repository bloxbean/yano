package com.bloxbean.cardano.yano.appchain.eutxo.zk.indexer;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityIndexSourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZerojValidityIndexSourceProviderTest {
    @TempDir
    Path temporary;

    @Test
    void sourceIsLazyBeforeCeremonyAndFailsClosedOnIdentityMismatch()
            throws Exception {
        EutxoValidityIndexSourceProvider provider =
                ServiceLoader.load(EutxoValidityIndexSourceProvider.class)
                        .findFirst().orElseThrow();
        var source = provider.open(
                temporary, "payments", "devnet").orElseThrow();
        assertThat(source.batches()).isEmpty();

        Files.writeString(temporary.resolve("state.json"), """
                {
                  "schemaVersion": "yano-eutxo-validity-lifecycle-v1",
                  "chainId": "another-chain",
                  "network": "devnet"
                }
                """);
        assertThatThrownBy(source::batches)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another chain or network");
    }
}
