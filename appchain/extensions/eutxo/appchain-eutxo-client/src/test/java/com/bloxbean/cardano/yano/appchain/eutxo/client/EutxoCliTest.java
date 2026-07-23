package com.bloxbean.cardano.yano.appchain.eutxo.client;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoCliTest {
    @Test
    void noFundsDemoIsOfflineStructuredAndExplicit() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int exit = EutxoCli.run(
                new String[]{"demo"},
                new PrintWriter(out),
                new PrintWriter(err));

        assertThat(exit).isZero();
        assertThat(out.toString())
                .contains("\"mode\":\"no-real-funds\"")
                .contains("\"profile\":\"yano-eutxo-v2-plutus-v3\"");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void inlineApiKeysAndUnknownOptionsAreNotAccepted() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int exit = EutxoCli.run(
                new String[]{"doctor", "--api-key", "secret"},
                new PrintWriter(out),
                new PrintWriter(err));

        assertThat(exit).isEqualTo(EutxoCli.EXIT_USAGE);
        assertThat(err.toString()).contains("unknown option: --api-key");
        assertThat(err.toString()).doesNotContain("secret");
    }
}
