package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Actor-authenticated governed-mutation command transported by an ordinary member relay. */
public record ActorGovernanceCommandV1(
        Operation operation,
        String mutationId,
        byte[] mutation,
        List<SignedAdministratorStatementV1> authorizations
) {
    public static final String POLICY_TOPIC = SignedActorCommandV1.DEFAULT_TOPIC;
    public static final String ACTOR_REGISTRY_TOPIC = "actors.command.v1";
    private static final byte[] MUTATION_DOMAIN =
            "yano:authenticated-map:governed-mutation:v1\0"
                    .getBytes(StandardCharsets.US_ASCII);

    public ActorGovernanceCommandV1 {
        if (operation == null) throw OrganizationRecordV1.invalid();
        mutationId = RoleWorkflowIdentifiers.id(mutationId, "mutationId");
        mutation = mutation == null ? new byte[0] : mutation.clone();
        authorizations = authorizations == null ? List.of() : authorizations.stream()
                .sorted(Comparator.comparing(entry -> entry.statement().actorId()))
                .toList();
        if (mutation.length > RoleWorkflowLimits.MAX_MUTATION_BYTES
                || authorizations.size() > RoleWorkflowLimits.MAX_ADMINISTRATORS) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
        validateShape(operation, mutation, authorizations);
        validateStatements(operation, mutationId, mutation, authorizations);
    }

    @Override public byte[] mutation() { return mutation.clone(); }
    @Override public List<SignedAdministratorStatementV1> authorizations() {
        return List.copyOf(authorizations);
    }

    public static byte[] mutationHash(byte[] mutation) {
        byte[] body = mutation == null ? new byte[0] : mutation.clone();
        if (body.length == 0 || body.length > RoleWorkflowLimits.MAX_MUTATION_BYTES) {
            throw OrganizationRecordV1.invalid();
        }
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(
                        MUTATION_DOMAIN.length + Integer.BYTES + body.length)
                .put(MUTATION_DOMAIN).putInt(body.length).put(body).array());
    }

    public byte[] encode() {
        Array votes = new Array();
        authorizations.forEach(vote -> votes.add(new ByteString(vote.encode())));
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnsignedInteger(operation.code));
        value.add(new UnicodeString(mutationId));
        value.add(new ByteString(mutation));
        value.add(votes);
        byte[] encoded = RoleWorkflowCbor.encode(value);
        if (encoded.length > RoleWorkflowLimits.MAX_COMMAND_BYTES) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
        return encoded;
    }

    public static ActorGovernanceCommandV1 decode(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, 5).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        Array votes = RoleWorkflowCbor.array(
                values.get(4), RoleWorkflowLimits.MAX_ADMINISTRATORS);
        ActorGovernanceCommandV1 decoded = new ActorGovernanceCommandV1(
                Operation.fromCode(RoleWorkflowCbor.uintInt(values.get(1))),
                RoleWorkflowCbor.text(values.get(2)),
                RoleWorkflowCbor.bytes(values.get(3)),
                votes.getDataItems().stream()
                        .map(RoleWorkflowCbor::bytes)
                        .map(SignedAdministratorStatementV1::decode)
                        .toList());
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static void validateShape(
            Operation operation,
            byte[] mutation,
            List<SignedAdministratorStatementV1> authorizations
    ) {
        if (operation == Operation.PROPOSE
                ? mutation.length == 0 || authorizations.isEmpty()
                : mutation.length != 0) {
            throw OrganizationRecordV1.invalid();
        }
        if (operation == Operation.ACTIVATE
                ? !authorizations.isEmpty()
                : authorizations.isEmpty()) {
            throw OrganizationRecordV1.invalid();
        }
    }

    private static void validateStatements(
            Operation operation,
            String mutationId,
            byte[] mutation,
            List<SignedAdministratorStatementV1> authorizations
    ) {
        if (operation == Operation.ACTIVATE) return;
        AdministratorStatementV1.Decision expected = switch (operation) {
            case PROPOSE -> AdministratorStatementV1.Decision.PROPOSE;
            case APPROVE -> AdministratorStatementV1.Decision.APPROVE;
            case CANCEL -> AdministratorStatementV1.Decision.CANCEL;
            case ACTIVATE -> throw new IllegalStateException("handled above");
        };
        byte[] expectedHash = mutation.length == 0
                ? authorizations.getFirst().statement().mutationHash()
                : mutationHash(mutation);
        AdministratorStatementV1 first = authorizations.getFirst().statement();
        Set<String> actors = new HashSet<>();
        for (SignedAdministratorStatementV1 signed : authorizations) {
            AdministratorStatementV1 statement = signed.statement();
            if (statement.decision() != expected || !statement.mutationId().equals(mutationId)
                    || !Arrays.equals(statement.mutationHash(), expectedHash)
                    || !sameSubject(first, statement) || !actors.add(statement.actorId())) {
                throw OrganizationRecordV1.invalid();
            }
        }
    }

    private static boolean sameSubject(
            AdministratorStatementV1 left,
            AdministratorStatementV1 right
    ) {
        return left.chainId().equals(right.chainId())
                && Arrays.equals(left.genesisId(), right.genesisId())
                && left.authorityId().equals(right.authorityId())
                && left.authorityRevision() == right.authorityRevision()
                && left.notBeforeHeight() == right.notBeforeHeight()
                && left.expiryHeight() == right.expiryHeight();
    }

    public enum Operation {
        PROPOSE(0), APPROVE(1), ACTIVATE(2), CANCEL(3);

        private final int code;

        Operation(int code) { this.code = code; }
        public int code() { return code; }

        static Operation fromCode(int code) {
            for (Operation value : values()) if (value.code == code) return value;
            throw OrganizationRecordV1.invalid();
        }
    }
}
