package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoDemoCliTest {
    @TempDir
    Path temporary;

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
