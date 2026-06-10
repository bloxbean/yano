package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.genesis.GenesisDelegation;
import com.bloxbean.cardano.yano.api.genesis.GenesisPool;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAccountStateStoreGenesisBootstrapTest {

    private static final String POOL_HASH = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";
    private static final String UNKNOWN_POOL_HASH = "cc".repeat(28);
    private static final String STAKE_HASH = "295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";
    private static final String REWARD_HASH = "11a14edf73b08a0a27cb98b2c57eb37c780df18fcfcf6785ed5df84a";
    private static final String GENESIS_HASH = "aa".repeat(32);
    private static final String ISSUER_VKEY = "113ea03b671f4920210598831952efa2b1709bfd1dd06639c3efab3630ef7257";
    private static final String FUNDED_STAKE_ADDRESS =
            "007290ea8fa9433c1045a4c8473959ad608e6c03a58c7de33bdbd3ce6f295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";

    @TempDir
    Path tempDir;

    @Test
    void genesisStakingBootstrapBooksPoolAndStakeDepositsOnce() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);
            store.setAdaPotTracker(new AdaPotTracker(rocks.db(), rocks.cfState(), true,
                    BigInteger.valueOf(10_000)));

            var event = new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", payload(GENESIS_HASH));
            store.handleGenesisBlock(event);

            assertThat(store.isPoolRegistered(POOL_HASH)).isTrue();
            assertThat(store.getPoolDeposit(POOL_HASH)).contains(BigInteger.valueOf(500));
            assertThat(store.getPoolParams(POOL_HASH, 0))
                    .isPresent()
                    .get()
                    .extracting(params -> params.deposit())
                    .isEqualTo(BigInteger.valueOf(500));

            assertThat(store.isStakeCredentialRegistered(GenesisDelegation.KEY_HASH, STAKE_HASH)).isTrue();
            assertThat(store.getStakeDeposit(GenesisDelegation.KEY_HASH, STAKE_HASH))
                    .contains(BigInteger.valueOf(2));
            assertThat(store.getDelegatedPool(GenesisDelegation.KEY_HASH, STAKE_HASH))
                    .contains(POOL_HASH);
            assertThat(store.isStakeCredentialRegistered(GenesisDelegation.KEY_HASH, REWARD_HASH)).isFalse();
            var rewardCalculator = new EpochRewardCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            assertThat(rewardCalculator.getStakeSnapshot(-1))
                    .containsEntry("0:" + STAKE_HASH,
                            new AccountStateCborCodec.EpochDelegSnapshot(POOL_HASH, BigInteger.valueOf(1_000)));

            assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(502));
            assertThat(store.getAdaPot(0))
                    .isPresent()
                    .get()
                    .satisfies(pot -> {
                        assertThat(pot.deposits()).isEqualTo(BigInteger.valueOf(502));
                        assertThat(pot.reserves()).isEqualTo(BigInteger.valueOf(9_000));
                    });

            store.handleGenesisBlock(event);

            assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(502));
            assertThat(store.getAdaPot(0).orElseThrow().deposits()).isEqualTo(BigInteger.valueOf(502));
        }
    }

    @Test
    void emptyStakingPayloadDoesNotWriteDepositsOrAdaPot() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);
            store.setAdaPotTracker(new AdaPotTracker(rocks.db(), rocks.cfState(), true,
                    BigInteger.valueOf(10_000)));

            var payload = new GenesisBootstrapData(GENESIS_HASH, ShelleyGenesisBootstrap.empty());
            store.handleGenesisBlock(new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", payload));

            assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
            assertThat(store.getTotalDeposited()).isZero();
            assertThat(store.getAdaPot(0)).isEmpty();
        }
    }

    @Test
    void genesisDelegationToUnregisteredPoolFailsClosed() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);
            store.setAdaPotTracker(new AdaPotTracker(rocks.db(), rocks.cfState(), true,
                    BigInteger.valueOf(10_000)));

            assertThatThrownBy(() -> store.handleGenesisBlock(
                    new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb",
                            payload(GENESIS_HASH, UNKNOWN_POOL_HASH))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("references unregistered pool");

            assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
            assertThat(store.getDelegatedPool(GenesisDelegation.KEY_HASH, STAKE_HASH)).isEmpty();
            assertThat(store.getTotalDeposited()).isZero();
            assertThat(store.getAdaPot(0)).isEmpty();
        }
    }

    @Test
    void genesisStakingBootstrapSeedsSlotZeroProducerBlockOnce() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);

            var expectedPool = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                    com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(
                            com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(ISSUER_VKEY)));
            assertThat(expectedPool).isEqualTo(POOL_HASH);

            var event = new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb",
                    payload(GENESIS_HASH), POOL_HASH);
            store.handleGenesisBlock(event);

            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(1);

            store.applyBlock(new BlockAppliedEvent(Era.Conway, 0, 0, "bb", block0()));
            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(1);

            store.handleGenesisBlock(event);
            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(1);
        }
    }

    @Test
    void differentGenesisHashAfterBootstrapFailsClosed() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);

            store.handleGenesisBlock(new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", payload(GENESIS_HASH)));

            assertThatThrownBy(() -> store.handleGenesisBlock(
                    new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", payload("cc".repeat(32)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Genesis staking bootstrap marker mismatch");
        }
    }

    @Test
    void emptyStakingPayloadWithDifferentGenesisHashAfterBootstrapFailsClosed() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);

            store.handleGenesisBlock(new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", payload(GENESIS_HASH)));

            var emptyStakingPayload = new GenesisBootstrapData("cc".repeat(32), ShelleyGenesisBootstrap.empty());
            assertThatThrownBy(() -> store.handleGenesisBlock(
                    new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb", emptyStakingPayload)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Genesis staking bootstrap marker mismatch");
        }
    }

    @Test
    void rollbackAndReplayAfterGenesisBootstrapDoesNotDuplicateGenesisState() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = store(rocks);
            store.setAdaPotTracker(new AdaPotTracker(rocks.db(), rocks.cfState(), true,
                    BigInteger.valueOf(10_000)));
            var event = new GenesisBlockEvent(Era.Conway, 0, 0, 0, "bb",
                    payload(GENESIS_HASH), POOL_HASH);

            store.handleGenesisBlock(event);
            store.applyBlock(new BlockAppliedEvent(Era.Conway, 10, 1, "cc", block(10, 1, "cc")));

            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(2);
            assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(502));

            store.rollbackToSlot(0);

            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(1);
            assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(502));

            store.handleGenesisBlock(event);
            store.applyBlock(new BlockAppliedEvent(Era.Conway, 10, 1, "cc", block(10, 1, "cc")));

            assertThat(store.getPoolBlockCount(0, POOL_HASH)).isEqualTo(2);
            assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(502));
            assertThat(store.getAdaPot(0).orElseThrow().deposits()).isEqualTo(BigInteger.valueOf(502));

            var rewardCalculator = new EpochRewardCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            assertThat(rewardCalculator.getStakeSnapshot(-1))
                    .containsEntry("0:" + STAKE_HASH,
                            new AccountStateCborCodec.EpochDelegSnapshot(POOL_HASH, BigInteger.valueOf(1_000)));
        }
    }

    private static DefaultAccountStateStore store(TestRocksDBHelper rocks) {
        return new DefaultAccountStateStore(
                rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(DefaultAccountStateStoreGenesisBootstrapTest.class),
                true,
                provider());
    }

    private static GenesisBootstrapData payload(String genesisHash) {
        return payload(genesisHash, POOL_HASH);
    }

    private static GenesisBootstrapData payload(String genesisHash, String delegationPoolHash) {
        var pool = new GenesisPool(
                POOL_HASH,
                "c2b62ffa92ad18ffc117ea3abeb161a68885000a466f9c71db5e4731d6630061",
                BigInteger.ZERO,
                BigInteger.valueOf(340),
                BigInteger.ZERO,
                BigInteger.ONE,
                "e0" + REWARD_HASH,
                Set.of(),
                List.of(),
                null,
                null);
        var delegation = new GenesisDelegation(STAKE_HASH, delegationPoolHash);
        var shelley = new ShelleyGenesisBootstrap(
                Map.of(FUNDED_STAKE_ADDRESS, BigInteger.valueOf(1_000)),
                BigInteger.valueOf(10_000),
                BigInteger.valueOf(2),
                BigInteger.valueOf(500),
                List.of(pool),
                List.of(delegation));
        return new GenesisBootstrapData(genesisHash, shelley);
    }

    private static EpochParamProvider provider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500);
            }
        };
    }

    private static Block block0() {
        return block(0, 0, "bb");
    }

    private static Block block(long slot, long blockNumber, String blockHash) {
        return Block.builder()
                .era(Era.Conway)
                .header(BlockHeader.builder()
                        .headerBody(HeaderBody.builder()
                                .slot(slot)
                                .blockNumber(blockNumber)
                                .blockHash(blockHash)
                                .issuerVkey(ISSUER_VKEY)
                                .build())
                        .build())
                .transactionBodies(List.of())
                .invalidTransactions(List.of())
                .build();
    }
}
