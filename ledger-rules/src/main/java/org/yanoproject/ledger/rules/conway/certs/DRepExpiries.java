package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.ledger.rules.conway.ruleset.DRepExpiry;
import org.yanoproject.ledger.rules.view.LedgerView;

/**
 * The implementations of {@link DRepExpiry} ({@code computeDRepExpiryVersioned}, Conway/Rules/GovCert.hs:282-292).
 */
public final class DRepExpiries {

    private DRepExpiries() {
    }

    /**
     * During the bootstrap phase ({@code hardforkConwayBootstrapPhase}): {@code currentEpoch + drepActivity}, ignoring
     * the dormant epochs.
     */
    public static final class Bootstrap implements DRepExpiry {

        @Override
        public long expiry(ProtocolParams pp, long currentEpoch, LedgerView state) {
            return currentEpoch + drepActivity(pp);
        }

        @Override
        public String haskellRef() {
            return "Conway/Rules/GovCert.hs:282-292 (computeDRepExpiryVersioned, hardforkConwayBootstrapPhase: "
                    + "currentEpoch + drepActivity)";
        }
    }

    /**
     * After the bootstrap phase: {@code computeDRepExpiry = currentEpoch + drepActivity - numDormantEpochs}
     * (GovCert.hs:294-306).
     */
    public static final class DormantAdjusted implements DRepExpiry {

        @Override
        public long expiry(ProtocolParams pp, long currentEpoch, LedgerView state) {
            long dormantEpochs = state.dormantEpochs().require("dormant epochs");
            return currentEpoch + drepActivity(pp) - dormantEpochs;
        }

        @Override
        public String haskellRef() {
            return "Conway/Rules/GovCert.hs:282-306 (computeDRepExpiry: currentEpoch + drepActivity - "
                    + "numDormantEpochs)";
        }
    }

    private static long drepActivity(ProtocolParams pp) {
        Integer value = pp.getDrepActivity();
        if (value == null) {
            throw new IllegalArgumentException("Protocol parameter drepActivity is missing");
        }
        return value;
    }
}
