package com.bloxbean.cardano.yano.appchain.history.onchain;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.nio.charset.StandardCharsets;

/**
 * Frozen Cardano History parameters for the reusable MPF validators. Applications compile the
 * generic validators with these exact keys, schemas and predicate tags.
 */
public final class CardanoHistoryOnChainPredicates {
    public static final String APPLICATION_ID = "cardano-history";
    public static final String MPF_PROFILE = "mpf-blake2b256-v1";
    public static final String PARAMS_COMPONENT = "l1-epoch-params-v1";
    public static final String STAKE_COMPONENT = "l1-epoch-stake-v1";
    public static final String GOVERNANCE_COMPONENT = "l1-epoch-governance-v1";
    public static final String STAKE_SERIES = STAKE_COMPONENT + ".distribution";
    public static final String DREP_SERIES = GOVERNANCE_COMPONENT + ".drep-distribution";

    public static final int STAKE_MINIMUM = 0;
    public static final int STAKE_POOL = 1;
    public static final int STAKE_MINIMUM_AND_POOL = 2;
    public static final int STAKE_EXACT_AND_POOL = 3;
    public static final int ABSENT_WITH_COMPLETENESS = 4;
    public static final int PROPOSAL_EXACT = 5;
    public static final int DREP_MINIMUM = 6;
    public static final int DREP_EXACT = 7;

    private CardanoHistoryOnChainPredicates() { }

    public static byte[] parametersKey(long epoch) {
        return component(PARAMS_COMPONENT, EpochParamsContract.stateKey(epoch));
    }

    public static byte[] parameterFieldKey(long epoch, String fieldId) {
        return component(PARAMS_COMPONENT, EpochParamsContract.fieldKey(epoch, fieldId));
    }

    public static byte[] stakeCompletenessKey(long epoch) {
        return component(STAKE_COMPONENT, EpochStakeContract.metaKey(epoch));
    }

    public static byte[] stakeSnapshotKey(int credentialType, byte[] credentialHash) {
        return EpochStakeContract.credentialOrderKey(credentialType, credentialHash);
    }

    public static byte[] proposalKey(long epoch, byte[] transactionId, int index) {
        return component(GOVERNANCE_COMPONENT,
                EpochGovernanceContract.proposalKey(epoch, transactionId, index));
    }

    public static byte[] proposalCompletenessKey(long epoch) {
        return component(GOVERNANCE_COMPONENT, EpochGovernanceContract.proposalMetaKey(epoch));
    }

    public static byte[] drepCompletenessKey(long epoch) {
        return component(GOVERNANCE_COMPONENT, EpochGovernanceContract.drepMetaKey(epoch));
    }

    public static byte[] drepSnapshotKey(int drepType, byte[] drepHash) {
        return EpochGovernanceContract.drepOrderKey(drepType, drepHash);
    }

    public static byte[] applicationIdBytes() {
        return APPLICATION_ID.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] stakeSeriesBytes() {
        return STAKE_SERIES.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] drepSeriesBytes() {
        return DREP_SERIES.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] component(String component, byte[] localKey) {
        return CompositeCommitmentV1.componentKey(component, localKey);
    }
}
