package org.yanoproject.runtime.blockproducer;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.account.AccountStateReadStore;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerStakeDataProviderTest {

    private static final String POOL = "aa".repeat(28);
    private static final String OTHER_POOL = "bb".repeat(28);

    /** Snapshot label -> (pool -> stake); the total is the sum, as the read store scans it. */
    private static AccountStateReadStore snapshots(Map<Integer, Map<String, BigInteger>> byLabel) {
        return new AccountStateReadStore() {
            @Override
            public Optional<BigInteger> getTotalActiveStake(int epoch) {
                return Optional.ofNullable(byLabel.get(epoch))
                        .map(pools -> pools.values().stream().reduce(BigInteger.ZERO, BigInteger::add));
            }

            @Override
            public Optional<PoolStake> getPoolActiveStake(int epoch, String poolHash) {
                return Optional.ofNullable(byLabel.get(epoch))
                        .map(pools -> pools.get(poolHash))
                        .map(amount -> new PoolStake(epoch, poolHash, amount));
            }
        };
    }

    @Test
    void leadersOfEpochEAreElectedWithSnapshotEMinusTwo() {
        var provider = new LedgerStakeDataProvider(snapshots(Map.of(
                98, Map.of(POOL, BigInteger.valueOf(1), OTHER_POOL, BigInteger.valueOf(2)),
                99, Map.of(POOL, BigInteger.valueOf(7), OTHER_POOL, BigInteger.valueOf(9)),
                100, Map.of(POOL, BigInteger.valueOf(70), OTHER_POOL, BigInteger.valueOf(90)))));

        assertThat(provider.getPoolStake(POOL, 100)).isEqualTo(1);
        assertThat(provider.getTotalStake(100)).isEqualTo(3);
        assertThat(provider.getPoolStake(POOL, 101)).isEqualTo(7);
        assertThat(provider.getTotalStake(101)).isEqualTo(16);
    }

    @Test
    void aPoolOutsideAnExistingSnapshotHasZeroStakeAndAMissingSnapshotIsUnknown() {
        var provider = new LedgerStakeDataProvider(snapshots(Map.of(
                10, Map.of(OTHER_POOL, BigInteger.TEN))));

        assertThat(provider.getPoolStake(POOL, 12)).isZero();
        assertThat(provider.getPoolStake(POOL, 13)).isNull();
        assertThat(provider.getTotalStake(13)).isNull();
    }

    @Test
    void anUnreadableSnapshotIsUnknown() {
        var provider = new LedgerStakeDataProvider(new AccountStateReadStore() {
            @Override
            public Optional<BigInteger> getTotalActiveStake(int epoch) {
                throw new IllegalStateException("Epoch snapshot " + epoch + " is still BUILDING");
            }
        });

        assertThat(provider.getPoolStake(POOL, 5)).isNull();
        assertThat(provider.getTotalStake(5)).isNull();
    }

    @Test
    void relativeStakeIsTheExactRatioFlooredToTheLedgerFixedPoint() {
        // fromRational (1 % 3) :: FixedPoint (34 digits) floors.
        assertThat(SlotLeaderCheck.relativeStake(BigInteger.ONE, BigInteger.valueOf(3)))
                .isEqualTo(new BigDecimal("0." + "3".repeat(34)));
        assertThat(SlotLeaderCheck.relativeStake(BigInteger.TWO, BigInteger.valueOf(3)))
                .isEqualTo(new BigDecimal("0." + "6".repeat(34)));
        // Mainnet-sized values: floor(pool * 10^34 / total) / 10^34, exactly.
        BigInteger pool = new BigInteger("64123456789012");
        BigInteger total = new BigInteger("21700000000000001");
        BigDecimal expected = new BigDecimal(pool.multiply(BigInteger.TEN.pow(34)).divide(total), 34);
        assertThat(SlotLeaderCheck.relativeStake(pool, total)).isEqualTo(expected);
        assertThat(SlotLeaderCheck.relativeStake(total, total)).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(SlotLeaderCheck.relativeStake(BigInteger.ZERO, total)).isZero();
        assertThat(SlotLeaderCheck.relativeStake(pool, BigInteger.ZERO)).isZero();
        assertThat(SlotLeaderCheck.relativeStake(null, total)).isZero();
    }
}
