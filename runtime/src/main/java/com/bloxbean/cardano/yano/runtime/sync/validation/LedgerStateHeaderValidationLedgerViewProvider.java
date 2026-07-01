package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Header-validation ledger view backed by Yano's account-state read APIs.
 */
public final class LedgerStateHeaderValidationLedgerViewProvider implements HeaderValidationLedgerViewProvider {
    private final LedgerStateProvider ledgerStateProvider;
    private final AccountStateReadStore accountStateReadStore;
    private final EpochParamProvider epochParamProvider;
    private final boolean strictOpCertCounter;

    public LedgerStateHeaderValidationLedgerViewProvider(LedgerStateProvider ledgerStateProvider,
                                                         AccountStateReadStore accountStateReadStore,
                                                         EpochParamProvider epochParamProvider) {
        this(ledgerStateProvider, accountStateReadStore, epochParamProvider, false);
    }

    public LedgerStateHeaderValidationLedgerViewProvider(LedgerStateProvider ledgerStateProvider,
                                                         AccountStateReadStore accountStateReadStore,
                                                         EpochParamProvider epochParamProvider,
                                                         boolean strictOpCertCounter) {
        this.ledgerStateProvider = Objects.requireNonNull(ledgerStateProvider, "ledgerStateProvider");
        this.accountStateReadStore = Objects.requireNonNull(accountStateReadStore, "accountStateReadStore");
        this.epochParamProvider = Objects.requireNonNull(epochParamProvider, "epochParamProvider");
        this.strictOpCertCounter = strictOpCertCounter;
    }

    @Override
    public Optional<LeaderStakeView> leaderStakeFor(ShelleyHeaderView header) {
        int epoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(header.slot());
        String poolHash = poolHash(header);
        Optional<LedgerStateProvider.PoolParams> poolParams = ledgerStateProvider.getPoolParams(poolHash, epoch);
        if (poolParams.isEmpty()) {
            return Optional.empty();
        }

        var poolStake = accountStateReadStore.getPoolActiveStake(epoch, poolHash);
        var totalStake = accountStateReadStore.getTotalActiveStake(epoch);
        if (poolStake.isEmpty() || totalStake.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new LeaderStakeView(
                poolHash,
                normalize(poolParams.orElseThrow().vrfKeyHash()),
                poolStake.orElseThrow().amount(),
                totalStake.orElseThrow(),
                BigDecimal.valueOf(epochParamProvider.getActiveSlotsCoeff())));
    }

    @Override
    public Optional<ProtocolView> protocolViewFor(ShelleyHeaderView header) {
        int epoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(header.slot());
        return Optional.of(new ProtocolView(
                epoch,
                epochParamProvider.getProtocolMajor(epoch),
                epochParamProvider.getProtocolMinor(epoch),
                epochParamProvider.getMaxBlockHeaderSize(epoch)));
    }

    @Override
    public Optional<OpCertStateView> opCertStateFor(ShelleyHeaderView header) {
        String poolHash = poolHash(header);
        var counter = ledgerStateProvider.getOpCertCounter(poolHash);
        if (counter.isPresent()) {
            return Optional.of(new OpCertStateView(poolHash, counter.getAsLong()));
        }
        if (!strictOpCertCounter) {
            return Optional.empty();
        }

        int epoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(header.slot());
        return ledgerStateProvider.getPoolParams(poolHash, epoch)
                .map(ignored -> new OpCertStateView(poolHash, 0L));
    }

    private static String poolHash(ShelleyHeaderView header) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(header.issuerVkey()));
    }

    static String vrfKeyHash(ShelleyHeaderView header) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(header.vrfVkey()));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
