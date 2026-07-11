package com.bloxbean.cardano.yano.runtime.sync.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Ledger-scoped evidence needed by validation stages that go beyond header-local crypto.
 */
public interface HeaderValidationLedgerViewProvider {

    static HeaderValidationLedgerViewProvider none() {
        return EmptyHeaderValidationLedgerViewProvider.INSTANCE;
    }

    default Optional<LeaderStakeView> leaderStakeFor(ShelleyHeaderView header) {
        return Optional.empty();
    }

    default Optional<ProtocolView> protocolViewFor(ShelleyHeaderView header) {
        return Optional.empty();
    }

    default Optional<OpCertStateView> opCertStateFor(ShelleyHeaderView header) {
        return Optional.empty();
    }

    record LeaderStakeView(String poolHash,
                           String registeredVrfKeyHash,
                           BigInteger poolStake,
                           BigInteger totalStake,
                           BigDecimal activeSlotCoeff) {
    }

    record ProtocolView(int epoch,
                        int protocolMajor,
                        int protocolMinor,
                        Integer maxBlockHeaderSize) {
    }

    record OpCertStateView(String poolHash,
                           Long expectedCounter) {
    }

    final class EmptyHeaderValidationLedgerViewProvider implements HeaderValidationLedgerViewProvider {
        private static final EmptyHeaderValidationLedgerViewProvider INSTANCE =
                new EmptyHeaderValidationLedgerViewProvider();

        private EmptyHeaderValidationLedgerViewProvider() {
        }
    }
}
