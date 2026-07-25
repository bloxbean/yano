package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoDemoCliTest {
    @TempDir
    Path temporary;

    @BeforeEach
    void installFakeYanoHome() throws Exception {
        Path home = temporary.resolve("yano-home");
        Files.createDirectories(home);
        Path launcher = home.resolve("yano.sh");
        Files.writeString(launcher, """
                #!/usr/bin/env bash
                set -euo pipefail
                output=""
                previous=""
                for value in "$@"; do
                  if [ "$previous" = "--output" ]; then output="$value"; fi
                  previous="$value"
                done
                if [ -n "$output" ]; then
                  mkdir -p "$output/scripts" "$output/secrets"
                  touch "$output/appchain.yaml" "$output/appchain.lock"
                  for script in start stop; do
                    printf '#!/usr/bin/env bash\\nexit 0\\n' > "$output/scripts/$script"
                    chmod +x "$output/scripts/$script"
                  done
                fi
                exit 0
                """);
        launcher.toFile().setExecutable(true);
        System.setProperty("yano.home", home.toString());
    }

    @AfterEach
    void clearFakeYanoHome() {
        System.clearProperty("yano.home");
    }

    @Test
    void discoversScenariosAndSupportsSetupStatusReset() {
        Path workspace = temporary.resolve("demo");
        Invocation inventory = invoke("scenarios", "--format", "json");
        assertThat(inventory.exit()).isZero();
        assertThat(inventory.out()).contains("\"ledger\"", "\"bridge\"");

        Invocation setup = invoke("setup", "--scenario", "ledger",
                "--workspace", workspace.toString(), "--format", "json");
        assertThat(setup.exit()).isZero();
        assertThat(setup.out()).contains("\"status\":\"EUTXO_DEMO_READY\"")
                .doesNotContain("YANO_APPCHAIN_SIGNING_KEY");

        Invocation status = invoke("status", "--workspace", workspace.toString());
        assertThat(status.exit()).isZero();
        assertThat(status.out()).contains("EUTXO_DEMO_STATUS", "virtual genesis");

        Invocation mismatch = invoke("status", "--scenario", "bridge",
                "--workspace", workspace.toString());
        assertThat(mismatch.exit()).isEqualTo(EutxoDemoCli.EXIT_INVALID);
        assertThat(mismatch.err()).contains("conflicts");

        Invocation unconfirmed = invoke("reset", "--workspace", workspace.toString());
        assertThat(unconfirmed.exit()).isEqualTo(EutxoDemoCli.EXIT_USAGE);
        Invocation reset = invoke("reset", "--yes", "--workspace", workspace.toString());
        assertThat(reset.exit()).isZero();
        assertThat(workspace).doesNotExist();
    }

    @Test
    void refusesOverwriteAndNeverEchoesUnexpectedInputValues() {
        Path workspace = temporary.resolve("occupied");
        workspace.toFile().mkdirs();
        Invocation first = invoke("setup", "--workspace", workspace.toString());
        assertThat(first.exit()).isZero();
        Invocation duplicate = invoke("setup", "--workspace", workspace.toString());
        assertThat(duplicate.exit()).isEqualTo(EutxoDemoCli.EXIT_INVALID);
        assertThat(duplicate.err()).contains("must not already contain files");
    }

    @Test
    void countIsBoundedBeforeWorkspaceOrNetworkAccess() {
        Invocation zero = invoke("round-trip", "--count", "0");
        assertThat(zero.exit()).isEqualTo(EutxoDemoCli.EXIT_USAGE);
        assertThat(zero.err()).contains("--count is outside its supported range");

        Invocation excessive = invoke("round-trip", "--count", "17");
        assertThat(excessive.exit()).isEqualTo(EutxoDemoCli.EXIT_USAGE);
        assertThat(excessive.err()).contains("--count is outside its supported range");
    }

    private Invocation invoke(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = EutxoDemoCli.run(arguments,
                new PrintWriter(out), new PrintWriter(err));
        return new Invocation(exit, out.toString(), err.toString());
    }

    private record Invocation(int exit, String out, String err) {
    }
}
