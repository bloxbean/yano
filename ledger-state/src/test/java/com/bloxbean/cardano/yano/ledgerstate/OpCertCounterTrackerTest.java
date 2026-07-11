package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.account.OpCertCounterState;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpCertCounterTrackerTest {

    @TempDir
    Path tempDir;

    @Test
    void opCertCounterCodecRoundTripsVersionedValue() {
        var data = new AccountStateCborCodec.OpCertCounterData(
                3,
                1234,
                55,
                "aa".repeat(32));

        byte[] encoded = AccountStateCborCodec.encodeOpCertCounter(data);

        assertThat(AccountStateCborCodec.decodeOpCertCounter(encoded)).isEqualTo(data);
    }

    @Test
    void readsRocksDbBackedCounterByIssuerHash() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            String issuerHash = "11".repeat(28);
            var tracker = new OpCertCounterTracker(rocks.db(), rocks.cfState());

            rocks.db().put(
                    rocks.cfState(),
                    OpCertCounterTracker.opCertCounterKey(issuerHash),
                    AccountStateCborCodec.encodeOpCertCounter(
                            new AccountStateCborCodec.OpCertCounterData(
                                    9,
                                    222,
                                    44,
                                    "22".repeat(32))));

            assertThat(tracker.get(issuerHash)).hasValue(new OpCertCounterState(
                    issuerHash,
                    9,
                    222,
                    44,
                    "22".repeat(32)));
        }
    }
}
