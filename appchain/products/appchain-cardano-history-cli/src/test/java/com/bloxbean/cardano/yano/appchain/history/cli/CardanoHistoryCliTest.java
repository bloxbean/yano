package com.bloxbean.cardano.yano.appchain.history.cli;

import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoHistoryCliTest {
    @Test void helpIsASuccessfulOfflineCommand() {
        assertThat(CardanoHistoryCli.run(new String[]{"--help"})).isZero();
        assertThat(CardanoHistoryCli.run(new String[]{"-h"})).isZero();
    }

    @Test void rendersReleasedPresetsWithoutNetworkConfiguration() {
        assertThat(CardanoHistoryCli.run(new String[]{"config", "render",
                "--preset", "params-only-v1"})).isZero();
        assertThat(CardanoHistoryCli.run(new String[]{"config", "render",
                "--preset", "full-v1"})).isZero();
    }

    @Test void networkCommandsNeverAssumeAUrlOrChain() {
        assertThat(CardanoHistoryClient.DEFAULT_EPOCH_PAGE)
                .isEqualTo(CardanoHistoryClient.MAX_EPOCH_PAGE);
        assertThat(CardanoHistoryCli.run(new String[]{"status"}))
                .isEqualTo(CardanoHistoryCli.USAGE);
    }
}
