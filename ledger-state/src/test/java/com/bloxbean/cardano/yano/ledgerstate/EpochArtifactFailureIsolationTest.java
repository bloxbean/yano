package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.archive.EpochArtifactContributor;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EpochArtifactFailureIsolationTest {
    @TempDir Path directory;

    @Test
    void missingAnchorReportsArchiveFailureButCommitsAdaPot() throws Exception {
        try (var rocks = TestRocksDBHelper.create(directory)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            AtomicReference<String> failure = new AtomicReference<>();
            store.setEpochArtifactContributor(new EpochArtifactContributor() {
                @Override public boolean enabled() { return true; }

                @Override
                public void contributeEpochStake(int epoch, long anchorSlot,
                        long anchorBlockNumber, byte[] anchorBlockHash,
                        long carrierBlockNumber, long rowCount,
                        ProjectionStagingWriter writer) { }

                @Override
                public void contributeAdaPot(int epoch, long anchorSlot,
                        long anchorBlockNumber, byte[] anchorBlockHash,
                        long carrierBlockNumber, long[] values,
                        ProjectionStagingWriter writer) { }

                @Override
                public void captureFailed(Dataset dataset, int epoch,
                        long carrierBlockNumber, RuntimeException cause) {
                    failure.set(dataset + ":" + epoch + ":" + carrierBlockNumber
                            + ":" + cause.getMessage());
                }
            });
            var pot = new AccountStateCborCodec.AdaPot(
                    BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                    BigInteger.valueOf(4), BigInteger.valueOf(5), BigInteger.valueOf(6),
                    BigInteger.valueOf(7), BigInteger.valueOf(8));

            store.contributeAdaPotArtifact(42, pot);

            assertThat(store.getAdaPot(42)).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.treasury()).isEqualTo(BigInteger.ONE);
                assertThat(snapshot.reserves()).isEqualTo(BigInteger.TWO);
            });
            assertThat(failure.get()).startsWith("ADA_POT:42:-1:epoch boundary was not prepared");
        }
    }
}
