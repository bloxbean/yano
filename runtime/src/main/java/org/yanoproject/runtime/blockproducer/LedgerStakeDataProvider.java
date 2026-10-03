package org.yanoproject.runtime.blockproducer;

import org.yanoproject.api.account.AccountStateReadStore;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Leader-check stake from Yano's own account state.
 *
 * <p>The Haskell node checks leadership in epoch {@code E} against {@code nesPd}: the ledger view's pool distribution
 * is {@code nes ^. nesPdL} (Babbage/Forecast.hs:60, used by Conway, Conway/Forecast.hs:22-23). At the boundary into
 * {@code E}, NEWEPOCH sets {@code nesPd} to {@code ssStakeMarkPoolDistr} of the snapshots <em>before</em> that
 * boundary's SNAP (Conway/Rules/NewEpoch.hs:178), i.e. the mark snapshot taken at the {@code E-2 -> E-1} boundary,
 * which SNAP then rotates to {@code ssStakeSet} (Shelley/Rules/Snap.hs:95-101): the stake distribution at the end of
 * epoch {@code E-2}. Yano labels a snapshot with the epoch whose end state it captures (the boundary's previous
 * epoch, {@code EpochBoundaryProcessor}), so the leader-check stake of epoch {@code E} is snapshot {@code E-2}, the
 * same label {@code EpochRewardCalculator} reads as {@code stakeEpoch - 2}.</p>
 *
 * <p>{@code calculatePoolDistr} (State/SnapShots.hs:449-465) gives a pool the exact ratio
 * {@code spssStakeRatio = poolStake %. ssTotalActiveStake} (SnapShots.hs:195-198, 401-403): the pool's delegated
 * stake over the snapshot's total active stake. These are the two integers returned here; the producer turns them
 * into the leader-check {@code FixedPoint} with {@link SlotLeaderCheck#relativeStake}.</p>
 */
@Slf4j
public final class LedgerStakeDataProvider implements StakeDataProvider {

    /** Epochs between a snapshot's label and the epoch whose leaders it elects. */
    static final int SNAPSHOT_LAG = 2;

    private final AccountStateReadStore accountState;

    public LedgerStakeDataProvider(AccountStateReadStore accountState) {
        this.accountState = Objects.requireNonNull(accountState, "accountState");
    }

    /** @return the pool's stake, zero when the snapshot exists without the pool, null when it is unreadable */
    @Override
    public BigInteger getPoolStake(String poolHash, int epoch) {
        int snapshot = epoch - SNAPSHOT_LAG;
        try {
            if (accountState.getTotalActiveStake(snapshot).isEmpty()) {
                return null;
            }
            return accountState.getPoolActiveStake(snapshot, poolHash)
                    .map(AccountStateReadStore.PoolStake::amount)
                    .orElse(BigInteger.ZERO);
        } catch (IllegalStateException e) {
            log.warn("Stake snapshot {} is not readable for epoch {}: {}", snapshot, epoch, e.getMessage());
            return null;
        }
    }

    /** @return the snapshot's total active stake, or null when it is missing or unreadable */
    @Override
    public BigInteger getTotalStake(int epoch) {
        int snapshot = epoch - SNAPSHOT_LAG;
        try {
            return accountState.getTotalActiveStake(snapshot).orElse(null);
        } catch (IllegalStateException e) {
            log.warn("Stake snapshot {} is not readable for epoch {}: {}", snapshot, epoch, e.getMessage());
            return null;
        }
    }
}
