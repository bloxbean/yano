package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountStateReadStore;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.account.OpCertCounterState;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerStateHeaderValidationLedgerViewProviderTest {

    @Test
    void opCertStateUsesPersistedCounterForIssuerHash() {
        byte[] issuerVkey = HexUtil.decodeHexString("11".repeat(32));
        String issuerHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(issuerVkey));
        var ledgerStateProvider = new MinimalLedgerStateProvider() {
            @Override
            public Optional<OpCertCounterState> getOpCertCounterState(String requestedIssuerHash) {
                if (!issuerHash.equals(requestedIssuerHash)) {
                    return Optional.empty();
                }
                return Optional.of(new OpCertCounterState(issuerHash, 5, 100, 20, "aa".repeat(32)));
            }
        };
        var provider = new LedgerStateHeaderValidationLedgerViewProvider(
                ledgerStateProvider,
                new AccountStateReadStore() {},
                new MinimalEpochParamProvider());

        Optional<HeaderValidationLedgerViewProvider.OpCertStateView> view =
                provider.opCertStateFor(headerWithIssuer(issuerVkey));

        assertThat(view).hasValue(new HeaderValidationLedgerViewProvider.OpCertStateView(issuerHash, 5L));
    }

    @Test
    void compatModeLeavesMissingOpCertCounterUnavailable() {
        byte[] issuerVkey = HexUtil.decodeHexString("11".repeat(32));
        var provider = new LedgerStateHeaderValidationLedgerViewProvider(
                new MinimalLedgerStateProvider(),
                new AccountStateReadStore() {},
                new MinimalEpochParamProvider(),
                false);

        assertThat(provider.opCertStateFor(headerWithIssuer(issuerVkey))).isEmpty();
    }

    @Test
    void strictModeTreatsRegisteredIssuerWithMissingCounterAsZero() {
        byte[] issuerVkey = HexUtil.decodeHexString("11".repeat(32));
        String issuerHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(issuerVkey));
        var ledgerStateProvider = new MinimalLedgerStateProvider() {
            @Override
            public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash, int epoch) {
                return issuerHash.equals(poolHash)
                        ? Optional.of(new LedgerStateProvider.PoolParams(
                        BigInteger.ZERO, 0, BigInteger.ZERO, BigInteger.ZERO,
                        "", java.util.Set.of(), null))
                        : Optional.empty();
            }
        };
        var provider = new LedgerStateHeaderValidationLedgerViewProvider(
                ledgerStateProvider,
                new AccountStateReadStore() {},
                new MinimalEpochParamProvider(),
                true);

        assertThat(provider.opCertStateFor(headerWithIssuer(issuerVkey)))
                .hasValue(new HeaderValidationLedgerViewProvider.OpCertStateView(issuerHash, 0L));
    }

    private static ShelleyHeaderView headerWithIssuer(byte[] issuerVkey) {
        return new ShelleyHeaderView(
                new byte[0],
                new byte[0],
                new byte[0],
                0,
                0,
                new byte[0],
                issuerVkey,
                new byte[0],
                new byte[0],
                new byte[0],
                new byte[0],
                new byte[0],
                0,
                new byte[0],
                new byte[0],
                0,
                0,
                new byte[0],
                9,
                0);
    }

    private static class MinimalLedgerStateProvider implements LedgerStateProvider {
        @Override public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<String> getDelegatedPool(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) { return Optional.empty(); }
        @Override public boolean isStakeCredentialRegistered(int credType, String credentialHash) { return false; }
        @Override public BigInteger getTotalDeposited() { return BigInteger.ZERO; }
        @Override public boolean isPoolRegistered(String poolHash) { return false; }
        @Override public Optional<BigInteger> getPoolDeposit(String poolHash) { return Optional.empty(); }
        @Override public Optional<Long> getPoolRetirementEpoch(String poolHash) { return Optional.empty(); }
    }

    private static class MinimalEpochParamProvider implements EpochParamProvider {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    }
}
