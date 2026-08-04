package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.GovernedCryptoWork;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AcceptedAdministratorVoteV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedAdministratorStatementV1;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Actor-authenticated, member-neutral governance for one component namespace. */
public final class ActorGovernanceProcessor {
    private final String chainId;
    private final byte[] genesisId;
    private final String stableAuthorityId;
    private final GovernedAuthorizationLimitsV1 limits;

    public ActorGovernanceProcessor(
            String chainId,
            byte[] genesisId,
            String stableAuthorityId,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = java.util.Objects.requireNonNull(chainId, "chainId");
        this.genesisId = java.util.Objects.requireNonNull(genesisId, "genesisId").clone();
        this.stableAuthorityId = java.util.Objects.requireNonNull(
                stableAuthorityId, "stableAuthorityId");
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        if (genesisId.length != 32) {
            throw new IllegalArgumentException("authenticated-map genesis id must be 32 bytes");
        }
    }

    public void prepareHeight(
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState
    ) {
        GovernancePendingIndexV1 index = pendingIndex(ownedState);
        if (index.entries().isEmpty()) {
            return;
        }
        AdministratorAuthorityV1 current = currentAuthority(authorityState);
        List<GovernancePendingIndexV1.Entry> expired = index.entries().stream()
                .filter(entry -> entry.expiryHeight() < height).toList();
        if (expired.size() > limits.maximumExpiryWorkPerBlock()) {
            throw new IllegalStateException("governance expiry work exceeds genesis bound");
        }
        List<GovernancePendingIndexV1.Entry> superseded = index.entries().stream()
                .filter(entry -> entry.expiryHeight() >= height)
                .filter(entry -> !entry.authorityId().equals(current.authorityId())
                        || entry.authorityRevision() != current.revision())
                .toList();
        if (superseded.size() > limits.maximumAuthoritySupersessionWork()) {
            throw new IllegalStateException(
                    "governance authority supersession exceeds genesis bound");
        }
        GovernancePendingIndexV1 updated = index;
        for (GovernancePendingIndexV1.Entry entry : expired) {
            updated = terminal(ownedState, updated, entry,
                    GovernedMutationRecordV1.Status.EXPIRED, height, List.of());
        }
        for (GovernancePendingIndexV1.Entry entry : superseded) {
            updated = terminal(ownedState, updated, entry,
                    GovernedMutationRecordV1.Status.SUPERSEDED, height, List.of());
        }
        writeIndex(ownedState, updated);
    }

    public RoleWorkflowResultCode apply(
            ActorGovernanceCommandV1 command,
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState,
            MutationHandler handler
    ) {
        java.util.Objects.requireNonNull(command, "command");
        java.util.Objects.requireNonNull(handler, "handler");
        return switch (command.operation()) {
            case PROPOSE -> propose(command, height, authorityState, ownedState, handler);
            case APPROVE -> approve(command, height, authorityState, ownedState);
            case ACTIVATE -> activate(command, height, authorityState, ownedState, handler);
            case CANCEL -> cancel(command, height, authorityState, ownedState);
        };
    }

    private RoleWorkflowResultCode propose(
            ActorGovernanceCommandV1 command,
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState,
            MutationHandler handler
    ) {
        byte[] key = RoleWorkflowKeys.governedMutation(command.mutationId());
        GovernedMutationRecordV1 existing = ownedState.get(key)
                .map(GovernedMutationRecordV1::decode).orElse(null);
        byte[] mutationHash = ActorGovernanceCommandV1.mutationHash(command.mutation());
        if (existing != null) {
            return MessageDigest.isEqual(existing.mutationHash(), mutationHash)
                    ? RoleWorkflowResultCode.EXACT_REPLAY
                    : RoleWorkflowResultCode.CONFLICT;
        }
        try {
            handler.validate(command.mutation(), authorityState, ownedState);
        } catch (IllegalArgumentException invalid) {
            return RoleWorkflowResultCode.INVALID_PAYLOAD;
        }
        AdministratorAuthorityV1 authority = currentAuthority(authorityState);
        AdministratorStatementV1 subject = command.authorizations().getFirst().statement();
        if (!validProposalSubject(subject, command, mutationHash, authority, height)) {
            return RoleWorkflowResultCode.WRONG_REVISION;
        }
        if (!reserveCrypto(authorityState, height, command.authorizations().size())) {
            return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;
        }
        List<AcceptedAdministratorVoteV1> accepted = verifyVotes(
                command.authorizations(), subject, authority, height, authorityState);
        if (accepted == null) {
            return RoleWorkflowResultCode.GOVERNANCE_PROOF_INVALID;
        }
        GovernancePendingIndexV1 index = pendingIndex(ownedState);
        String proposer = subject.actorId();
        if (!hasCapacity(index, proposer, authority, subject.expiryHeight())) {
            return RoleWorkflowResultCode.CAPACITY_EXCEEDED;
        }
        GovernedMutationRecordV1 record = new GovernedMutationRecordV1(
                command.mutationId(), command.mutation(), mutationHash,
                authority.authorityId(), authority.revision(), authority.digest(),
                subject.notBeforeHeight(), subject.expiryHeight(), proposer, height,
                accepted, List.of(), GovernedMutationRecordV1.Status.PENDING, 0);
        ownedState.put(key, record.encode());
        putMarkers(ownedState, record);
        writeIndex(ownedState, index.add(new GovernancePendingIndexV1.Entry(
                record.mutationId(), record.expiryHeight(), record.authorityId(),
                record.authorityRevision(), record.proposerActorId())));
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private RoleWorkflowResultCode approve(
            ActorGovernanceCommandV1 command,
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState
    ) {
        GovernedMutationRecordV1 record = pendingRecord(command.mutationId(), ownedState);
        if (record == null) return absentOrTerminal(command.mutationId(), ownedState);
        AdministratorAuthorityV1 authority = currentAuthority(authorityState);
        if (!recordMatchesCurrentAuthority(record, authority)) {
            return RoleWorkflowResultCode.SUPERSEDED;
        }
        AdministratorStatementV1 subject = command.authorizations().getFirst().statement();
        if (!matchesRecord(subject, record)) {
            return RoleWorkflowResultCode.CONFLICT;
        }
        if (!reserveCrypto(authorityState, height, command.authorizations().size())) {
            return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;
        }
        List<AcceptedAdministratorVoteV1> accepted = verifyVotes(
                command.authorizations(), subject, authority, height, authorityState);
        if (accepted == null) {
            return RoleWorkflowResultCode.GOVERNANCE_PROOF_INVALID;
        }
        GovernedMutationRecordV1 updated = record.withApprovals(accepted);
        if (updated.approvals().size() == record.approvals().size()) {
            return RoleWorkflowResultCode.EXACT_REPLAY;
        }
        ownedState.put(RoleWorkflowKeys.governedMutation(record.mutationId()),
                updated.encode());
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private RoleWorkflowResultCode activate(
            ActorGovernanceCommandV1 command,
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState,
            MutationHandler handler
    ) {
        GovernedMutationRecordV1 record = pendingRecord(command.mutationId(), ownedState);
        if (record == null) return absentOrTerminal(command.mutationId(), ownedState);
        AdministratorAuthorityV1 authority = currentAuthority(authorityState);
        if (!recordMatchesCurrentAuthority(record, authority)) {
            return RoleWorkflowResultCode.SUPERSEDED;
        }
        if (height < record.notBeforeHeight()) {
            return RoleWorkflowResultCode.NOT_READY;
        }
        if (height > record.expiryHeight()) {
            return RoleWorkflowResultCode.EXPIRED;
        }
        if (record.approvals().size() < authority.distinctActorThreshold()) {
            return RoleWorkflowResultCode.GOVERNANCE_THRESHOLD_NOT_MET;
        }
        boolean applied;
        try {
            applied = handler.activate(record.mutation(), height,
                    authorityState, ownedState);
        } catch (IllegalArgumentException invalid) {
            applied = false;
        }
        GovernancePendingIndexV1 index = pendingIndex(ownedState);
        index = terminal(ownedState, index,
                indexEntry(index, record.mutationId()), applied
                        ? GovernedMutationRecordV1.Status.ACTIVATED
                        : GovernedMutationRecordV1.Status.FAILED,
                height, List.of());
        writeIndex(ownedState, index);
        if (applied) {
            prepareHeight(height, authorityState, ownedState);
        }
        return applied ? RoleWorkflowResultCode.ACCEPTED
                : RoleWorkflowResultCode.CONFLICT;
    }

    private RoleWorkflowResultCode cancel(
            ActorGovernanceCommandV1 command,
            long height,
            AppStateWriter authorityState,
            AppStateWriter ownedState
    ) {
        GovernedMutationRecordV1 record = pendingRecord(command.mutationId(), ownedState);
        if (record == null) return absentOrTerminal(command.mutationId(), ownedState);
        AdministratorAuthorityV1 authority = currentAuthority(authorityState);
        if (!recordMatchesCurrentAuthority(record, authority)) {
            return RoleWorkflowResultCode.SUPERSEDED;
        }
        AdministratorStatementV1 subject = command.authorizations().getFirst().statement();
        if (!matchesRecord(subject, record)) {
            return RoleWorkflowResultCode.CONFLICT;
        }
        if (!reserveCrypto(authorityState, height, command.authorizations().size())) {
            return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;
        }
        List<AcceptedAdministratorVoteV1> accepted = verifyVotes(
                command.authorizations(), subject, authority, height, authorityState);
        if (accepted == null) {
            return RoleWorkflowResultCode.GOVERNANCE_PROOF_INVALID;
        }
        boolean proposerCancel = accepted.stream().anyMatch(vote ->
                vote.authorization().statement().actorId()
                        .equals(record.proposerActorId()));
        if (!proposerCancel && accepted.size() < authority.distinctActorThreshold()) {
            return RoleWorkflowResultCode.GOVERNANCE_THRESHOLD_NOT_MET;
        }
        GovernancePendingIndexV1 index = pendingIndex(ownedState);
        index = terminal(ownedState, index, indexEntry(index, record.mutationId()),
                GovernedMutationRecordV1.Status.CANCELLED, height, accepted);
        writeIndex(ownedState, index);
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private List<AcceptedAdministratorVoteV1> verifyVotes(
            List<SignedAdministratorStatementV1> signedVotes,
            AdministratorStatementV1 subject,
            AdministratorAuthorityV1 authority,
            long height,
            AppStateWriter actorState
    ) {
        List<AcceptedAdministratorVoteV1> accepted = new ArrayList<>();
        for (SignedAdministratorStatementV1 signed : signedVotes) {
            AdministratorStatementV1 statement = signed.statement();
            if (!sameSubject(subject, statement)
                    || !statement.chainId().equals(chainId)
                    || !MessageDigest.isEqual(statement.genesisId(), genesisId)
                    || !authority.administratorActorIds().contains(statement.actorId())
                    || statement.issuedHeight() > height
                    || statement.deadlineHeight() <= height) {
                return null;
            }
            long actorRevision = pointer(actorState,
                    RoleWorkflowKeys.actorCurrent(statement.actorId()));
            if (actorRevision == 0 || actorRevision != statement.actorRevision()) return null;
            ActorRecordV1 actor = actorState.get(RoleWorkflowKeys.actorRevision(
                            statement.actorId(), actorRevision))
                    .map(ActorRecordV1::decode)
                    .orElseThrow(() -> new IllegalStateException(
                            "administrator actor pointer is dangling"));
            if (!actor.actorId().equals(statement.actorId())
                    || actor.revision() != actorRevision) {
                throw new IllegalStateException(
                        "administrator actor pointer is incompatible");
            }
            long organizationRevision = pointer(actorState,
                    RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
            if (organizationRevision == 0) return null;
            OrganizationRecordV1 organization = actorState.get(
                            RoleWorkflowKeys.organizationRevision(
                                    actor.organizationId(), organizationRevision))
                    .map(OrganizationRecordV1::decode)
                    .orElseThrow(() -> new IllegalStateException(
                            "administrator organization pointer is dangling"));
            if (!organization.organizationId().equals(actor.organizationId())
                    || organization.revision() != organizationRevision) {
                throw new IllegalStateException(
                        "administrator organization pointer is incompatible");
            }
            ActorKeyEpochV1 key = actor.key(statement.keyId());
            if (actor.status() != RecordStatus.ACTIVE
                    || organization.status() != RecordStatus.ACTIVE
                    || key == null || !key.activeAt(height)
                    || !MessageDigest.isEqual(key.publicKey(), statement.publicKey())
                    || !signed.verifyClaimedKey()) {
                return null;
            }
            accepted.add(new AcceptedAdministratorVoteV1(
                    signed, organization.organizationId(),
                    organization.revision(), height));
        }
        return accepted;
    }

    private boolean validProposalSubject(
            AdministratorStatementV1 statement,
            ActorGovernanceCommandV1 command,
            byte[] mutationHash,
            AdministratorAuthorityV1 authority,
            long height
    ) {
        long maximumExpiry;
        try {
            maximumExpiry = Math.addExact(
                    height, authority.maximumMutationLifetimeBlocks());
        } catch (ArithmeticException exhausted) {
            maximumExpiry = Long.MAX_VALUE;
        }
        return statement.decision() == AdministratorStatementV1.Decision.PROPOSE
                && statement.chainId().equals(chainId)
                && MessageDigest.isEqual(statement.genesisId(), genesisId)
                && statement.authorityId().equals(authority.authorityId())
                && statement.authorityRevision() == authority.revision()
                && statement.mutationId().equals(command.mutationId())
                && MessageDigest.isEqual(statement.mutationHash(), mutationHash)
                && statement.notBeforeHeight() >= height
                && statement.expiryHeight() <= maximumExpiry;
    }

    private boolean hasCapacity(
            GovernancePendingIndexV1 index,
            String proposer,
            AdministratorAuthorityV1 authority,
            long deadline
    ) {
        return index.entries().size() < limits.maximumPendingGovernance()
                && index.entries().stream().filter(entry ->
                entry.proposerActorId().equals(proposer)).count()
                < limits.maximumPendingPerActor()
                && index.entries().stream().filter(entry ->
                entry.authorityId().equals(authority.authorityId())
                        && entry.authorityRevision() == authority.revision()).count()
                < limits.maximumPendingPerAuthority()
                && index.entries().stream().filter(entry ->
                entry.expiryHeight() == deadline).count()
                < limits.maximumPendingPerDeadline();
    }

    private GovernancePendingIndexV1 terminal(
            AppStateWriter state,
            GovernancePendingIndexV1 index,
            GovernancePendingIndexV1.Entry entry,
            GovernedMutationRecordV1.Status status,
            long height,
            List<AcceptedAdministratorVoteV1> terminalVotes
    ) {
        GovernedMutationRecordV1 record = pendingRecord(entry.mutationId(), state);
        if (record == null) {
            throw new IllegalStateException("governance pending index references terminal state");
        }
        GovernedMutationRecordV1 terminal = record.terminal(
                status, height, terminalVotes);
        state.put(RoleWorkflowKeys.governedMutation(record.mutationId()), terminal.encode());
        deleteMarkers(state, record);
        return index.remove(record.mutationId());
    }

    private static GovernancePendingIndexV1.Entry indexEntry(
            GovernancePendingIndexV1 index,
            String mutationId
    ) {
        return index.entries().stream().filter(entry ->
                        entry.mutationId().equals(mutationId))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "governance pending record is absent from index"));
    }

    private static void putMarkers(
            AppStateWriter state,
            GovernedMutationRecordV1 record
    ) {
        state.put(RoleWorkflowKeys.governanceDeadline(
                record.expiryHeight(), record.mutationId()), record.mutationHash());
        state.put(RoleWorkflowKeys.governanceByActor(
                record.proposerActorId(), record.mutationId()), record.mutationHash());
        state.put(RoleWorkflowKeys.governanceByAuthority(
                record.authorityId(), record.mutationId()), record.mutationHash());
    }

    private static void deleteMarkers(
            AppStateWriter state,
            GovernedMutationRecordV1 record
    ) {
        state.delete(RoleWorkflowKeys.governanceDeadline(
                record.expiryHeight(), record.mutationId()));
        state.delete(RoleWorkflowKeys.governanceByActor(
                record.proposerActorId(), record.mutationId()));
        state.delete(RoleWorkflowKeys.governanceByAuthority(
                record.authorityId(), record.mutationId()));
    }

    private static GovernancePendingIndexV1 pendingIndex(AppStateWriter state) {
        return state.get(RoleWorkflowKeys.governancePendingIndex())
                .map(GovernancePendingIndexV1::decode)
                .orElseGet(GovernancePendingIndexV1::empty);
    }

    private static void writeIndex(
            AppStateWriter state,
            GovernancePendingIndexV1 index
    ) {
        state.put(RoleWorkflowKeys.governancePendingIndex(), index.encode());
    }

    private AdministratorAuthorityV1 currentAuthority(AppStateWriter state) {
        long revision = pointer(state, RoleWorkflowKeys.authorityCurrent(stableAuthorityId));
        if (revision == 0) {
            throw new IllegalStateException("administrator authority pointer is absent");
        }
        AdministratorAuthorityV1 authority = state.get(
                        RoleWorkflowKeys.authorityRevision(stableAuthorityId, revision))
                .map(AdministratorAuthorityV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "administrator authority pointer is dangling"));
        if (!authority.authorityId().equals(stableAuthorityId)
                || authority.revision() != revision) {
            throw new IllegalStateException(
                    "administrator authority pointer is incompatible");
        }
        return authority;
    }

    private boolean reserveCrypto(AppStateWriter state, long height, int units) {
        return GovernedCryptoWork.reserve(state, height, units,
                limits.maximumCryptoWorkUnitsPerBlock());
    }

    private static GovernedMutationRecordV1 pendingRecord(
            String mutationId,
            AppStateWriter state
    ) {
        GovernedMutationRecordV1 record = state.get(
                        RoleWorkflowKeys.governedMutation(mutationId))
                .map(GovernedMutationRecordV1::decode).orElse(null);
        return record != null && record.status() == GovernedMutationRecordV1.Status.PENDING
                ? record : null;
    }

    private static RoleWorkflowResultCode absentOrTerminal(
            String mutationId,
            AppStateWriter state
    ) {
        return state.get(RoleWorkflowKeys.governedMutation(mutationId)).isPresent()
                ? RoleWorkflowResultCode.TERMINAL : RoleWorkflowResultCode.UNKNOWN_RECORD;
    }

    private static boolean recordMatchesCurrentAuthority(
            GovernedMutationRecordV1 record,
            AdministratorAuthorityV1 authority
    ) {
        return record.authorityId().equals(authority.authorityId())
                && record.authorityRevision() == authority.revision()
                && MessageDigest.isEqual(record.authorityDigest(), authority.digest());
    }

    private static boolean matchesRecord(
            AdministratorStatementV1 statement,
            GovernedMutationRecordV1 record
    ) {
        return statement.mutationId().equals(record.mutationId())
                && MessageDigest.isEqual(statement.mutationHash(), record.mutationHash())
                && statement.authorityId().equals(record.authorityId())
                && statement.authorityRevision() == record.authorityRevision()
                && statement.notBeforeHeight() == record.notBeforeHeight()
                && statement.expiryHeight() == record.expiryHeight();
    }

    private static boolean sameSubject(
            AdministratorStatementV1 left,
            AdministratorStatementV1 right
    ) {
        return left.decision() == right.decision()
                && left.chainId().equals(right.chainId())
                && MessageDigest.isEqual(left.genesisId(), right.genesisId())
                && left.authorityId().equals(right.authorityId())
                && left.authorityRevision() == right.authorityRevision()
                && left.mutationId().equals(right.mutationId())
                && MessageDigest.isEqual(left.mutationHash(), right.mutationHash())
                && left.notBeforeHeight() == right.notBeforeHeight()
                && left.expiryHeight() == right.expiryHeight();
    }

    private static long pointer(AppStateWriter state, byte[] key) {
        byte[] encoded = state.get(key).orElse(null);
        if (encoded == null) return 0;
        if (encoded.length != Long.BYTES) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        long revision = ByteBuffer.wrap(encoded).getLong();
        if (revision < 1) throw new IllegalStateException("corrupt role-workflow pointer");
        return revision;
    }

    public interface MutationHandler {
        void validate(
                byte[] mutation,
                AppStateWriter authorityState,
                AppStateWriter ownedState
        );

        boolean activate(
                byte[] mutation,
                long height,
                AppStateWriter authorityState,
                AppStateWriter ownedState
        );
    }
}
