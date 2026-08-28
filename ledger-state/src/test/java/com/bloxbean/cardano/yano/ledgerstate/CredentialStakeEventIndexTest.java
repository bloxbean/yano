package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialStakeEventIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void answersRegistrationCycleCutoffsWithoutScanningOtherCredentials() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            String hash = "31".repeat(28);
            putEvent(rocks, hash, 10, AccountStateCborCodec.EVENT_REGISTRATION);
            putEvent(rocks, hash, 20, AccountStateCborCodec.EVENT_DEREGISTRATION);
            putEvent(rocks, hash, 30, AccountStateCborCodec.EVENT_REGISTRATION);
            putEvent(rocks, hash, 40, AccountStateCborCodec.EVENT_DEREGISTRATION);
            putEvent(rocks, "41".repeat(28), 15,
                    AccountStateCborCodec.EVENT_DEREGISTRATION);

            var summary = store.getCredentialEventSummary(
                    "0:" + hash, 35, 45, 25, 35);

            assertThat(summary.deregisteredAtStability()).isFalse();
            assertThat(summary.deregisteredAtBoundary()).isTrue();
            assertThat(summary.registeredSince()).isTrue();
            assertThat(summary.registeredUntil()).isTrue();
        }
    }

    private static void putEvent(TestRocksDBHelper rocks, String hash, long slot,
                                 int eventType) throws Exception {
        rocks.db().put(rocks.cfState(),
                DefaultAccountStateStore.credentialStakeEventKey(0, hash, slot, 0, 0),
                AccountStateCborCodec.encodeStakeEvent(eventType));
    }
}
