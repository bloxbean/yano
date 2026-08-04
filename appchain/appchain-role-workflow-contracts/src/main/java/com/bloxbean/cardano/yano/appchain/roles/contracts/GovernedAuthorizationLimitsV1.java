package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.List;

/**
 * Genesis-bound resource profile for governed authorization, expiry, and queries.
 * Values may be lowered by genesis but cannot exceed the frozen v1 maxima.
 */
public record GovernedAuthorizationLimitsV1(
        int maximumEvidenceItemsPerCommand,
        int maximumCoveredIndexesPerEvidence,
        int maximumGenesisOrganizations,
        int maximumGenesisActors,
        int maximumGenesisKeys,
        int maximumGenesisPolicies,
        int maximumGenesisRecordBytes,
        int maximumPendingGovernance,
        int maximumPendingApprovals,
        int maximumPendingPerActor,
        int maximumPendingPerPolicy,
        int maximumPendingPerAuthority,
        int maximumPendingPerDeadline,
        int maximumExpiryWorkPerBlock,
        int maximumAuthoritySupersessionWork,
        int maximumQueryPageSize,
        int maximumCryptoWorkUnitsPerBlock
) {
    private static final int FIELD_COUNT = 18;

    public GovernedAuthorizationLimitsV1 {
        bounded(maximumEvidenceItemsPerCommand,
                RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS);
        bounded(maximumCoveredIndexesPerEvidence,
                RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES);
        bounded(maximumGenesisOrganizations,
                RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS);
        bounded(maximumGenesisActors, RoleWorkflowLimits.MAX_GENESIS_ACTORS);
        bounded(maximumGenesisKeys, RoleWorkflowLimits.MAX_GENESIS_KEYS);
        bounded(maximumGenesisPolicies, RoleWorkflowLimits.MAX_GENESIS_POLICIES);
        bounded(maximumGenesisRecordBytes, RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES);
        bounded(maximumPendingGovernance, RoleWorkflowLimits.MAX_PENDING_MUTATIONS);
        bounded(maximumPendingApprovals, RoleWorkflowLimits.MAX_PENDING_PROPOSALS);
        bounded(maximumPendingPerActor, RoleWorkflowLimits.MAX_PENDING_PER_ACTOR);
        bounded(maximumPendingPerPolicy, RoleWorkflowLimits.MAX_PENDING_PER_POLICY);
        bounded(maximumPendingPerAuthority, RoleWorkflowLimits.MAX_PENDING_PER_AUTHORITY);
        bounded(maximumPendingPerDeadline, RoleWorkflowLimits.MAX_PENDING_PER_DEADLINE);
        bounded(maximumExpiryWorkPerBlock, RoleWorkflowLimits.MAX_EXPIRY_WORK_PER_BLOCK);
        bounded(maximumAuthoritySupersessionWork,
                RoleWorkflowLimits.MAX_AUTHORITY_SUPERSESSION_WORK);
        bounded(maximumQueryPageSize, RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE);
        bounded(maximumCryptoWorkUnitsPerBlock,
                RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK);
        if (maximumPendingPerActor > maximumPendingGovernance
                || maximumPendingPerPolicy > maximumPendingApprovals
                || maximumPendingPerAuthority > maximumPendingGovernance
                || maximumPendingPerDeadline > maximumExpiryWorkPerBlock
                || maximumPendingPerAuthority > maximumAuthoritySupersessionWork) {
            throw OrganizationRecordV1.invalid();
        }
    }

    public static GovernedAuthorizationLimitsV1 defaults() {
        return new GovernedAuthorizationLimitsV1(
                RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS,
                RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES,
                RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS,
                RoleWorkflowLimits.MAX_GENESIS_ACTORS,
                RoleWorkflowLimits.MAX_GENESIS_KEYS,
                RoleWorkflowLimits.MAX_GENESIS_POLICIES,
                RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES,
                RoleWorkflowLimits.MAX_PENDING_MUTATIONS,
                RoleWorkflowLimits.MAX_PENDING_PROPOSALS,
                RoleWorkflowLimits.MAX_PENDING_PER_ACTOR,
                RoleWorkflowLimits.MAX_PENDING_PER_POLICY,
                RoleWorkflowLimits.MAX_PENDING_PER_AUTHORITY,
                RoleWorkflowLimits.MAX_PENDING_PER_DEADLINE,
                RoleWorkflowLimits.MAX_EXPIRY_WORK_PER_BLOCK,
                RoleWorkflowLimits.MAX_AUTHORITY_SUPERSESSION_WORK,
                RoleWorkflowLimits.MAX_QUERY_PAGE_SIZE,
                RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK);
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(maximumEvidenceItemsPerCommand));
        value.add(new UnsignedInteger(maximumCoveredIndexesPerEvidence));
        value.add(new UnsignedInteger(maximumGenesisOrganizations));
        value.add(new UnsignedInteger(maximumGenesisActors));
        value.add(new UnsignedInteger(maximumGenesisKeys));
        value.add(new UnsignedInteger(maximumGenesisPolicies));
        value.add(new UnsignedInteger(maximumGenesisRecordBytes));
        value.add(new UnsignedInteger(maximumPendingGovernance));
        value.add(new UnsignedInteger(maximumPendingApprovals));
        value.add(new UnsignedInteger(maximumPendingPerActor));
        value.add(new UnsignedInteger(maximumPendingPerPolicy));
        value.add(new UnsignedInteger(maximumPendingPerAuthority));
        value.add(new UnsignedInteger(maximumPendingPerDeadline));
        value.add(new UnsignedInteger(maximumExpiryWorkPerBlock));
        value.add(new UnsignedInteger(maximumAuthoritySupersessionWork));
        value.add(new UnsignedInteger(maximumQueryPageSize));
        value.add(new UnsignedInteger(maximumCryptoWorkUnitsPerBlock));
        return RoleWorkflowCbor.encode(value);
    }

    public static GovernedAuthorizationLimitsV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, FIELD_COUNT).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        GovernedAuthorizationLimitsV1 decoded = new GovernedAuthorizationLimitsV1(
                uint(values, 1), uint(values, 2), uint(values, 3), uint(values, 4),
                uint(values, 5), uint(values, 6), uint(values, 7), uint(values, 8),
                uint(values, 9), uint(values, 10), uint(values, 11), uint(values, 12),
                uint(values, 13), uint(values, 14), uint(values, 15), uint(values, 16),
                uint(values, 17));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static int uint(List<co.nstant.in.cbor.model.DataItem> values, int index) {
        return RoleWorkflowCbor.uintInt(values.get(index));
    }

    private static void bounded(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw OrganizationRecordV1.invalid();
        }
    }
}
