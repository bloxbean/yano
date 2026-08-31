package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MirCreditOptimizationTest {
    @TempDir
    Path tempDir;

    @Test
    void creditsAndRollsBackMirUsingTheValuesCapturedByTheOrderedScan() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            int epoch = 10;
            String reservesCredential = "11".repeat(28);
            String treasuryCredential = "22".repeat(28);
            String deregisteredCredential = "33".repeat(28);
            byte[] reservesAccountKey = DefaultAccountStateStore.accountKey(0, reservesCredential);
            byte[] treasuryAccountKey = DefaultAccountStateStore.accountKey(1, treasuryCredential);
            byte[] deregisteredAccountKey = DefaultAccountStateStore.accountKey(0, deregisteredCredential);
            byte[] reservesRestKey = DefaultAccountStateStore.rewardRestKey(epoch,
                    DefaultAccountStateStore.REWARD_REST_MIR_RESERVES, 0, reservesCredential);
            byte[] treasuryRestKey = DefaultAccountStateStore.rewardRestKey(epoch,
                    DefaultAccountStateStore.REWARD_REST_MIR_TREASURY, 1, treasuryCredential);
            byte[] deregisteredRestKey = DefaultAccountStateStore.rewardRestKey(epoch,
                    DefaultAccountStateStore.REWARD_REST_MIR_RESERVES, 0, deregisteredCredential);

            byte[] reservesAccount = AccountStateCborCodec.encodeStakeAccount(
                    BigInteger.valueOf(100), BigInteger.valueOf(2_000_000));
            byte[] treasuryAccount = AccountStateCborCodec.encodeStakeAccount(
                    BigInteger.valueOf(200), BigInteger.valueOf(2_000_000));
            byte[] reservesRest = AccountStateCborCodec.encodeRewardRest(
                    BigInteger.valueOf(11), epoch - 1, 1);
            byte[] treasuryRest = AccountStateCborCodec.encodeRewardRest(
                    BigInteger.valueOf(22), epoch - 1, 2);
            byte[] deregisteredRest = AccountStateCborCodec.encodeRewardRest(
                    BigInteger.valueOf(33), epoch - 1, 3);

            rocks.db().put(rocks.cfState(), reservesAccountKey, reservesAccount);
            rocks.db().put(rocks.cfState(), treasuryAccountKey, treasuryAccount);
            rocks.db().put(rocks.cfState(), reservesRestKey, reservesRest);
            rocks.db().put(rocks.cfState(), treasuryRestKey, treasuryRest);
            rocks.db().put(rocks.cfState(), deregisteredRestKey, deregisteredRest);

            store.creditMirRewardRest(epoch);

            assertThat(AccountStateCborCodec.decodeStakeAccount(
                    rocks.db().get(rocks.cfState(), reservesAccountKey)).reward())
                    .isEqualTo(BigInteger.valueOf(111));
            assertThat(AccountStateCborCodec.decodeStakeAccount(
                    rocks.db().get(rocks.cfState(), treasuryAccountKey)).reward())
                    .isEqualTo(BigInteger.valueOf(222));
            assertThat(rocks.db().get(rocks.cfState(), deregisteredAccountKey)).isNull();
            assertThat(rocks.db().get(rocks.cfState(), reservesRestKey)).isNull();
            assertThat(rocks.db().get(rocks.cfState(), treasuryRestKey)).isNull();
            assertThat(rocks.db().get(rocks.cfState(), deregisteredRestKey)).isNull();
            assertThat(store.getMirEpochTotal(epoch - 1,
                    DefaultAccountStateStore.REWARD_REST_MIR_RESERVES))
                    .isEqualTo(BigInteger.valueOf(11));
            assertThat(store.getMirEpochTotal(epoch - 1,
                    DefaultAccountStateStore.REWARD_REST_MIR_TREASURY))
                    .isEqualTo(BigInteger.valueOf(22));

            store.rollbackToSlot(store.slotForEpochStart(epoch) - 1);

            assertThat(rocks.db().get(rocks.cfState(), reservesAccountKey))
                    .isEqualTo(reservesAccount);
            assertThat(rocks.db().get(rocks.cfState(), treasuryAccountKey))
                    .isEqualTo(treasuryAccount);
            assertThat(rocks.db().get(rocks.cfState(), reservesRestKey))
                    .isEqualTo(reservesRest);
            assertThat(rocks.db().get(rocks.cfState(), treasuryRestKey))
                    .isEqualTo(treasuryRest);
            assertThat(rocks.db().get(rocks.cfState(), deregisteredRestKey))
                    .isEqualTo(deregisteredRest);
            assertThat(store.getMirEpochTotal(epoch - 1,
                    DefaultAccountStateStore.REWARD_REST_MIR_RESERVES)).isZero();
            assertThat(store.getMirEpochTotal(epoch - 1,
                    DefaultAccountStateStore.REWARD_REST_MIR_TREASURY)).isZero();
        }
    }
}
