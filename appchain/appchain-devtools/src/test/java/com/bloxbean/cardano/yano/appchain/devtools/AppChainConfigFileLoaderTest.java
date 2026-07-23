package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConfigFileLoaderTest {
    @TempDir
    Path temporary;

    private final AppChainConfigFileLoader loader = new AppChainConfigFileLoader();

    @Test
    void flattensNestedAndIndexedYamlWithoutCoercingLists() throws Exception {
        Path config = write("valid.yml", """
                yano:
                  app-chain:
                    chains[0]:
                      chain-id: orders
                      members: [key-a, key-b]
                      block:
                        max-bytes: 4096
                """);

        var values = loader.load(config);

        assertThat(values)
                .containsEntry("yano.app-chain.chains[0].chain-id", "orders")
                .containsEntry("yano.app-chain.chains[0].block.max-bytes", 4096);
        assertThat(values.get("yano.app-chain.chains[0].members"))
                .isEqualTo(java.util.List.of("key-a", "key-b"));
    }

    @Test
    void rejectsDuplicateAndAmbiguousYamlPaths() throws Exception {
        Path duplicate = write("duplicate.yml", """
                yano:
                  app-chain:
                    chain-id: first
                    chain-id: second
                """);
        Path ambiguous = write("ambiguous.yml", """
                yano:
                  app-chain:
                    block.max-bytes: 10
                    block:
                      max-bytes: 20
                """);
        Path nullAmbiguous = write("null-ambiguous.yml", """
                yano:
                  app-chain:
                    block.max-bytes:
                    block:
                      max-bytes: 20
                """);

        assertThatThrownBy(() -> loader.load(duplicate))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> loader.load(ambiguous))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ambiguous configuration path");
        assertThatThrownBy(() -> loader.load(nullAmbiguous))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ambiguous configuration path");
    }

    private Path write(String name, String value) throws IOException {
        Path target = temporary.resolve(name);
        Files.writeString(target, value);
        return target;
    }
}
