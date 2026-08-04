package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Authenticated actor-governance proposal and retained accepted votes. */
public record GovernedMutationRecordV1(
        String mutationId,
        byte[] mutation,
        byte[] mutationHash,
        String authorityId,
        long authorityRevision,
        byte[] authorityDigest,
        long notBeforeHeight,
        long expiryHeight,
        String proposerActorId,
        long createdHeight,
        List<AcceptedAdministratorVoteV1> approvals,
        List<AcceptedAdministratorVoteV1> terminalAuthorizations,
        Status status,
        long terminalHeight
) {
    public GovernedMutationRecordV1 {
        mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
        mutation = mutation == null ? null : mutation.clone();
        mutationHash = exact(mutationHash, 32);
        authorityId = RoleWorkflowIdentifiers.id(authorityId, "authorityId");
        authorityDigest = exact(authorityDigest, 32);
        proposerActorId = RoleWorkflowIdentifiers.id(proposerActorId, "proposerActorId");
        if (mutation == null || mutation.length == 0
                || mutation.length > RoleWorkflowLimits.MAX_MUTATION_BYTES
                || !MessageDigest.isEqual(
                ActorGovernanceCommandV1.mutationHash(mutation), mutationHash)
                || authorityRevision < 1 || notBeforeHeight < 1
                || expiryHeight <= notBeforeHeight || createdHeight < 1 || status == null) {
            throw OrganizationRecordV1.invalid();
        }
        approvals = canonicalVotes(approvals, false);
        terminalAuthorizations = canonicalVotes(terminalAuthorizations, true);
        validateVotes(mutationId, mutationHash, authorityId, authorityRevision,
                notBeforeHeight, expiryHeight, approvals);
        validateVotes(mutationId, mutationHash, authorityId, authorityRevision,
                notBeforeHeight, expiryHeight, terminalAuthorizations);
        String expectedProposer = proposerActorId;
        if (approvals.stream().noneMatch(vote ->
                vote.authorization().statement().decision()
                        == AdministratorStatementV1.Decision.PROPOSE
                        && vote.authorization().statement().actorId()
                        .equals(expectedProposer))) {
            throw OrganizationRecordV1.invalid();
        }
        if (status == Status.PENDING
                ? terminalHeight != 0 || !terminalAuthorizations.isEmpty()
                : terminalHeight < createdHeight
                || status == Status.CANCELLED && terminalAuthorizations.isEmpty()) {
            throw OrganizationRecordV1.invalid();
        }
    }

    @Override public byte[] mutation() { return mutation.clone(); }
    @Override public byte[] mutationHash() { return mutationHash.clone(); }
    @Override public byte[] authorityDigest() { return authorityDigest.clone(); }
    @Override public List<AcceptedAdministratorVoteV1> approvals() {
        return List.copyOf(approvals);
    }
    @Override public List<AcceptedAdministratorVoteV1> terminalAuthorizations() {
        return List.copyOf(terminalAuthorizations);
    }

    public GovernedMutationRecordV1 withApprovals(
            List<AcceptedAdministratorVoteV1> additional
    ) {
        List<AcceptedAdministratorVoteV1> combined = new ArrayList<>(approvals);
        Set<String> actors = new HashSet<>();
        approvals.forEach(vote -> actors.add(vote.authorization().statement().actorId()));
        for (AcceptedAdministratorVoteV1 vote : additional) {
            if (actors.add(vote.authorization().statement().actorId())) {
                combined.add(vote);
            }
        }
        return new GovernedMutationRecordV1(mutationId, mutation, mutationHash,
                authorityId, authorityRevision, authorityDigest, notBeforeHeight,
                expiryHeight, proposerActorId, createdHeight, combined,
                terminalAuthorizations, status, terminalHeight);
    }

    public GovernedMutationRecordV1 terminal(
            Status next,
            long height,
            List<AcceptedAdministratorVoteV1> terminalVotes
    ) {
        if (next == Status.PENDING) throw OrganizationRecordV1.invalid();
        return new GovernedMutationRecordV1(mutationId, mutation, mutationHash,
                authorityId, authorityRevision, authorityDigest, notBeforeHeight,
                expiryHeight, proposerActorId, createdHeight, approvals,
                terminalVotes, next, height);
    }

    public byte[] encode() {
        Array accepted = new Array();
        approvals.forEach(vote -> accepted.add(new ByteString(vote.encode())));
        Array terminal = new Array();
        terminalAuthorizations.forEach(vote ->
                terminal.add(new ByteString(vote.encode())));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(mutationId));
        value.add(new ByteString(mutation));
        value.add(new ByteString(mutationHash));
        value.add(new UnicodeString(authorityId));
        value.add(new UnsignedInteger(authorityRevision));
        value.add(new ByteString(authorityDigest));
        value.add(new UnsignedInteger(notBeforeHeight));
        value.add(new UnsignedInteger(expiryHeight));
        value.add(new UnicodeString(proposerActorId));
        value.add(new UnsignedInteger(createdHeight));
        value.add(accepted);
        value.add(terminal);
        value.add(new UnsignedInteger(status.code));
        value.add(new UnsignedInteger(terminalHeight));
        return RoleWorkflowCbor.encode(value);
    }

    public static GovernedMutationRecordV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 15).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array approvals = RoleWorkflowCbor.array(
                values.get(11), RoleWorkflowLimits.MAX_ADMINISTRATORS);
        Array terminal = RoleWorkflowCbor.array(
                values.get(12), RoleWorkflowLimits.MAX_ADMINISTRATORS);
        GovernedMutationRecordV1 decoded = new GovernedMutationRecordV1(
                RoleWorkflowCbor.text(values.get(1)),
                RoleWorkflowCbor.bytes(values.get(2)),
                RoleWorkflowCbor.bytes(values.get(3), 32),
                RoleWorkflowCbor.text(values.get(4)),
                RoleWorkflowCbor.uint(values.get(5)),
                RoleWorkflowCbor.bytes(values.get(6), 32),
                RoleWorkflowCbor.uint(values.get(7)),
                RoleWorkflowCbor.uint(values.get(8)),
                RoleWorkflowCbor.text(values.get(9)),
                RoleWorkflowCbor.uint(values.get(10)),
                approvals.getDataItems().stream().map(RoleWorkflowCbor::bytes)
                        .map(AcceptedAdministratorVoteV1::decode).toList(),
                terminal.getDataItems().stream().map(RoleWorkflowCbor::bytes)
                        .map(AcceptedAdministratorVoteV1::decode).toList(),
                Status.fromCode(RoleWorkflowCbor.uintInt(values.get(13))),
                RoleWorkflowCbor.uint(values.get(14)));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static List<AcceptedAdministratorVoteV1> canonicalVotes(
            List<AcceptedAdministratorVoteV1> votes,
            boolean cancellation
    ) {
        if (votes == null
                || votes.size() > RoleWorkflowLimits.MAX_ADMINISTRATORS
                || !cancellation && votes.isEmpty()) {
            throw OrganizationRecordV1.invalid();
        }
        List<AcceptedAdministratorVoteV1> canonical = votes.stream()
                .sorted(Comparator.comparing(vote ->
                        vote.authorization().statement().actorId()))
                .toList();
        if (canonical.stream().map(vote -> vote.authorization().statement().actorId())
                .distinct().count() != canonical.size()) {
            throw OrganizationRecordV1.invalid();
        }
        for (AcceptedAdministratorVoteV1 vote : canonical) {
            boolean cancel = vote.authorization().statement().decision()
                    == AdministratorStatementV1.Decision.CANCEL;
            if (cancel != cancellation) throw OrganizationRecordV1.invalid();
        }
        return canonical;
    }

    private static void validateVotes(
            String mutationId,
            byte[] mutationHash,
            String authorityId,
            long authorityRevision,
            long notBeforeHeight,
            long expiryHeight,
            List<AcceptedAdministratorVoteV1> votes
    ) {
        for (AcceptedAdministratorVoteV1 vote : votes) {
            AdministratorStatementV1 statement = vote.authorization().statement();
            if (!statement.mutationId().equals(mutationId)
                    || !MessageDigest.isEqual(statement.mutationHash(), mutationHash)
                    || !statement.authorityId().equals(authorityId)
                    || statement.authorityRevision() != authorityRevision
                    || statement.notBeforeHeight() != notBeforeHeight
                    || statement.expiryHeight() != expiryHeight) {
                throw OrganizationRecordV1.invalid();
            }
        }
    }

    private static byte[] exact(byte[] value, int length) {
        if (value == null || value.length != length) {
            throw OrganizationRecordV1.invalid();
        }
        return value.clone();
    }

    public enum Status {
        PENDING(0), ACTIVATED(1), CANCELLED(2), EXPIRED(3), FAILED(4), SUPERSEDED(5);

        private final int code;

        Status(int code) { this.code = code; }
        public int code() { return code; }

        static Status fromCode(int code) {
            for (Status value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }
}
