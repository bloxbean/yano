package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Final authenticated-map v1 action, evidence, and one-time consumption contract. */
public final class AuthenticatedMapAuthorizationContract {
    public static final int CODEC_VERSION = 1;
    public static final String APPROVAL_PAYLOAD_DOMAIN =
            "yano.authenticated-map.action.v1";
    public static final int NO_EVIDENCE_HANDLE = 0;
    public static final int EVIDENCE_ACTOR = 0;
    public static final int EVIDENCE_APPROVAL = 1;
    public static final int SIGNATURE_ED25519 = 0;

    private static final byte[] ACTION_DOMAIN =
            "yano:authenticated-map:action:v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACTOR_DOMAIN =
            "yano:authenticated-map:actor-authorization:v1\0"
                    .getBytes(StandardCharsets.US_ASCII);

    private AuthenticatedMapAuthorizationContract() {
    }

    public static byte[] encodeAction(MapActionV1 action) {
        Objects.requireNonNull(action, "action");
        Array mutations = new Array();
        action.mutations().forEach(mutation -> mutations.add(encodeMutation(mutation)));
        Array assignments = new Array();
        action.authorizations().forEach(assignment -> assignments.add(
                encodeAssignment(assignment)));
        Array value = new Array();
        value.add(new UnsignedInteger(CODEC_VERSION));
        value.add(new UnsignedInteger(action.batch() ? 1 : 0));
        value.add(mutations);
        value.add(assignments);
        return StdlibContractCbor.encode(value);
    }

    public static MapActionV1 decodeAction(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 4).getDataItems();
        requireVersion(values.get(0));
        int kind = StdlibContractCbor.uintInt(values.get(1));
        if (kind > 1) throw malformed();
        Array mutations = StdlibContractCbor.array(
                values.get(2), AuthenticatedMapContract.MAX_BATCH_ITEMS);
        Array assignments = StdlibContractCbor.array(
                values.get(3), AuthenticatedMapContract.MAX_BATCH_ITEMS);
        MapActionV1 decoded = new MapActionV1(kind == 1,
                mutations.getDataItems().stream()
                        .map(AuthenticatedMapAuthorizationContract::decodeMutation)
                        .toList(),
                assignments.getDataItems().stream()
                        .map(AuthenticatedMapAuthorizationContract::decodeAssignment)
                        .toList());
        requireCanonical(bytes, encodeAction(decoded));
        return decoded;
    }

    public static byte[] actionCommitment(MapActionV1 action) {
        byte[] encoded = encodeAction(action);
        byte[] preimage = new byte[ACTION_DOMAIN.length + encoded.length];
        System.arraycopy(ACTION_DOMAIN, 0, preimage, 0, ACTION_DOMAIN.length);
        System.arraycopy(encoded, 0, preimage, ACTION_DOMAIN.length, encoded.length);
        return Blake2bUtil.blake2bHash256(preimage);
    }

    public static byte[] encodeCommand(AuthenticatedMapCommandV1 command) {
        Objects.requireNonNull(command, "command");
        Array evidence = new Array();
        command.evidence().forEach(item -> {
            Array wrapped = new Array();
            wrapped.add(new UnsignedInteger(item.kind()));
            wrapped.add(new ByteString(item.encode()));
            evidence.add(wrapped);
        });
        Array value = new Array();
        value.add(new UnsignedInteger(CODEC_VERSION));
        value.add(new ByteString(encodeAction(command.action())));
        value.add(evidence);
        byte[] encoded = StdlibContractCbor.encode(value);
        if (encoded.length > AuthenticatedMapContract.MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("authenticated-map command exceeds maximum bytes");
        }
        return encoded;
    }

    public static AuthenticatedMapCommandV1 decodeCommand(byte[] bytes) {
        if (bytes == null || bytes.length > AuthenticatedMapContract.MAX_BATCH_BYTES) {
            throw malformed();
        }
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
        requireVersion(values.get(0));
        Array evidence = StdlibContractCbor.array(values.get(2),
                RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS);
        List<AuthorizationEvidenceV1> decodedEvidence = new ArrayList<>();
        for (co.nstant.in.cbor.model.DataItem item : evidence.getDataItems()) {
            List<co.nstant.in.cbor.model.DataItem> wrapped =
                    StdlibContractCbor.array(item, 2).getDataItems();
            if (wrapped.size() != 2) throw malformed();
            int kind = StdlibContractCbor.uintInt(wrapped.get(0));
            byte[] encoded = StdlibContractCbor.bytes(wrapped.get(1));
            decodedEvidence.add(switch (kind) {
                case EVIDENCE_ACTOR -> MapActorAuthorizationV1.decode(encoded);
                case EVIDENCE_APPROVAL -> MapApprovalReferenceV1.decode(encoded);
                default -> throw malformed();
            });
        }
        AuthenticatedMapCommandV1 decoded = new AuthenticatedMapCommandV1(
                decodeAction(StdlibContractCbor.bytes(values.get(1))), decodedEvidence);
        requireCanonical(bytes, encodeCommand(decoded));
        return decoded;
    }

    public record MapActionV1(
            boolean batch,
            List<AuthenticatedMapContract.Mutation> mutations,
            List<AuthorizationAssignmentV1> authorizations
    ) {
        public MapActionV1 {
            mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
            authorizations = List.copyOf(
                    Objects.requireNonNull(authorizations, "authorizations"));
            new AuthenticatedMapContract.Command(batch, mutations);
            if (authorizations.size() != mutations.size()) {
                throw new IllegalArgumentException(
                        "every map mutation requires one authorization assignment");
            }
            for (int index = 0; index < authorizations.size(); index++) {
                if (authorizations.get(index).mutationIndex() != index) {
                    throw new IllegalArgumentException(
                            "authorization assignments must cover ordered mutation indexes");
                }
            }
        }

        @Override public List<AuthenticatedMapContract.Mutation> mutations() {
            return List.copyOf(mutations);
        }
        @Override public List<AuthorizationAssignmentV1> authorizations() {
            return List.copyOf(authorizations);
        }

        public static MapActionV1 basic(
                AuthenticatedMapContract.Command command,
                List<Integer> authorizationKinds
        ) {
            if (authorizationKinds == null
                    || authorizationKinds.size() != command.mutations().size()) {
                throw new IllegalArgumentException("basic authorization kind count is invalid");
            }
            List<AuthorizationAssignmentV1> assignments = new ArrayList<>();
            for (int index = 0; index < authorizationKinds.size(); index++) {
                assignments.add(new AuthorizationAssignmentV1(
                        index, authorizationKinds.get(index), "", NO_EVIDENCE_HANDLE));
            }
            return new MapActionV1(command.batch(), command.mutations(), assignments);
        }

        public static MapActionV1 open(AuthenticatedMapContract.Command command) {
            return basic(command, java.util.Collections.nCopies(
                    command.mutations().size(), AuthenticatedMapContract.AUTH_OPEN));
        }
    }

    public record AuthorizationAssignmentV1(
            int mutationIndex,
            int authorizationKind,
            String policyId,
            int evidenceHandle
    ) {
        public AuthorizationAssignmentV1 {
            policyId = optionalId(policyId, "policyId");
            if (mutationIndex < 0
                    || authorizationKind < AuthenticatedMapContract.AUTH_OPEN
                    || authorizationKind > AuthenticatedMapContract.AUTH_APPROVAL
                    || evidenceHandle < NO_EVIDENCE_HANDLE
                    || evidenceHandle > RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS) {
                throw new IllegalArgumentException("invalid map authorization assignment");
            }
            boolean governed = authorizationKind == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                    || authorizationKind == AuthenticatedMapContract.AUTH_APPROVAL;
            if (governed) {
                if (policyId.isEmpty() || evidenceHandle == NO_EVIDENCE_HANDLE) {
                    throw new IllegalArgumentException("governed assignment requires evidence");
                }
            } else if (!policyId.isEmpty() || evidenceHandle != NO_EVIDENCE_HANDLE) {
                throw new IllegalArgumentException("basic assignment cannot carry evidence");
            }
        }
    }

    public sealed interface AuthorizationEvidenceV1
            permits MapActorAuthorizationV1, MapApprovalReferenceV1 {
        int kind();
        byte[] actionCommitment();
        List<Integer> coveredMutationIndexes();
        String policyId();
        long policyRevision();
        byte[] encode();
    }

    public record MapActorAuthorizationV1(
            byte[] authorizationId,
            String chainId,
            byte[] genesisId,
            byte[] actionCommitment,
            List<Integer> coveredMutationIndexes,
            String policyId,
            long policyRevision,
            String actorId,
            long actorRevision,
            String keyId,
            byte[] publicKey,
            long issuedHeight,
            long deadlineHeight,
            int signatureAlgorithm,
            byte[] signature
    ) implements AuthorizationEvidenceV1 {
        public MapActorAuthorizationV1 {
            authorizationId = exact(authorizationId, 32, "authorizationId");
            chainId = RoleWorkflowIdentifiers.chainId(chainId);
            genesisId = exact(genesisId, 32, "genesisId");
            actionCommitment = exact(actionCommitment, 32, "actionCommitment");
            coveredMutationIndexes = indexes(coveredMutationIndexes);
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
            keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
            publicKey = exact(publicKey, 32, "publicKey");
            signature = exact(signature, 64, "signature");
            if (policyRevision < 1 || actorRevision < 1 || issuedHeight < 1
                    || deadlineHeight <= issuedHeight
                    || signatureAlgorithm != SIGNATURE_ED25519) {
                throw new IllegalArgumentException("invalid actor authorization");
            }
        }

        @Override public int kind() { return EVIDENCE_ACTOR; }
        @Override public byte[] authorizationId() { return authorizationId.clone(); }
        @Override public byte[] genesisId() { return genesisId.clone(); }
        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
        @Override public List<Integer> coveredMutationIndexes() {
            return List.copyOf(coveredMutationIndexes);
        }
        @Override public byte[] publicKey() { return publicKey.clone(); }
        @Override public byte[] signature() { return signature.clone(); }

        public byte[] unsignedStatement() {
            Array covered = uintArray(coveredMutationIndexes);
            Array value = new Array();
            value.add(new UnsignedInteger(CODEC_VERSION));
            value.add(new ByteString(authorizationId));
            value.add(new UnicodeString(chainId));
            value.add(new ByteString(genesisId));
            value.add(new ByteString(actionCommitment));
            value.add(covered);
            value.add(new UnicodeString(policyId));
            value.add(new UnsignedInteger(policyRevision));
            value.add(new UnicodeString(actorId));
            value.add(new UnsignedInteger(actorRevision));
            value.add(new UnicodeString(keyId));
            value.add(new ByteString(publicKey));
            value.add(new UnsignedInteger(issuedHeight));
            value.add(new UnsignedInteger(deadlineHeight));
            value.add(new UnsignedInteger(signatureAlgorithm));
            return StdlibContractCbor.encode(value);
        }

        public byte[] signingPreimage() {
            byte[] statement = unsignedStatement();
            return ByteBuffer.allocate(ACTOR_DOMAIN.length + Integer.BYTES + statement.length)
                    .put(ACTOR_DOMAIN).putInt(statement.length).put(statement).array();
        }

        public byte[] statementDigest() { return sha256(unsignedStatement()); }

        public boolean verifyClaimedKey() {
            return RoleWorkflowEd25519.verify(signature, signingPreimage(), publicKey);
        }

        @Override
        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(CODEC_VERSION));
            value.add(new ByteString(unsignedStatement()));
            value.add(new ByteString(signature));
            return StdlibContractCbor.encode(value);
        }

        public static MapActorAuthorizationV1 sign(
                byte[] authorizationId,
                String chainId,
                byte[] genesisId,
                byte[] actionCommitment,
                List<Integer> coveredMutationIndexes,
                String policyId,
                long policyRevision,
                String actorId,
                long actorRevision,
                String keyId,
                byte[] publicKey,
                long issuedHeight,
                long deadlineHeight,
                byte[] privateSeed
        ) {
            MapActorAuthorizationV1 unsigned = new MapActorAuthorizationV1(
                    authorizationId, chainId, genesisId, actionCommitment,
                    coveredMutationIndexes, policyId, policyRevision, actorId,
                    actorRevision, keyId, publicKey, issuedHeight, deadlineHeight,
                    SIGNATURE_ED25519, new byte[64]);
            return new MapActorAuthorizationV1(
                    authorizationId, chainId, genesisId, actionCommitment,
                    coveredMutationIndexes, policyId, policyRevision, actorId,
                    actorRevision, keyId, publicKey, issuedHeight, deadlineHeight,
                    SIGNATURE_ED25519,
                    RoleWorkflowEd25519.sign(unsigned.signingPreimage(), privateSeed));
        }

        public static MapActorAuthorizationV1 decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> wrapper =
                    StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
            requireVersion(wrapper.get(0));
            byte[] unsigned = StdlibContractCbor.bytes(wrapper.get(1));
            List<co.nstant.in.cbor.model.DataItem> values =
                    StdlibContractCbor.decodeArray(unsigned, 15).getDataItems();
            requireVersion(values.get(0));
            Array covered = StdlibContractCbor.array(values.get(5),
                    RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES);
            MapActorAuthorizationV1 decoded = new MapActorAuthorizationV1(
                    StdlibContractCbor.bytes(values.get(1), 32),
                    StdlibContractCbor.text(values.get(2)),
                    StdlibContractCbor.bytes(values.get(3), 32),
                    StdlibContractCbor.bytes(values.get(4), 32),
                    covered.getDataItems().stream().map(StdlibContractCbor::uintInt).toList(),
                    StdlibContractCbor.text(values.get(6)),
                    StdlibContractCbor.uint(values.get(7)),
                    StdlibContractCbor.text(values.get(8)),
                    StdlibContractCbor.uint(values.get(9)),
                    StdlibContractCbor.text(values.get(10)),
                    StdlibContractCbor.bytes(values.get(11), 32),
                    StdlibContractCbor.uint(values.get(12)),
                    StdlibContractCbor.uint(values.get(13)),
                    StdlibContractCbor.uintInt(values.get(14)),
                    StdlibContractCbor.bytes(wrapper.get(2), 64));
            requireCanonical(unsigned, decoded.unsignedStatement());
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record MapApprovalReferenceV1(
            String proposalId,
            byte[] actionCommitment,
            List<Integer> coveredMutationIndexes,
            String policyId,
            long policyRevision
    ) implements AuthorizationEvidenceV1 {
        public MapApprovalReferenceV1 {
            proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
            actionCommitment = exact(actionCommitment, 32, "actionCommitment");
            coveredMutationIndexes = indexes(coveredMutationIndexes);
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            if (policyRevision < 1) throw new IllegalArgumentException("invalid policy revision");
        }

        @Override public int kind() { return EVIDENCE_APPROVAL; }
        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
        @Override public List<Integer> coveredMutationIndexes() {
            return List.copyOf(coveredMutationIndexes);
        }

        @Override
        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(CODEC_VERSION));
            value.add(new UnicodeString(proposalId));
            value.add(new ByteString(actionCommitment));
            value.add(uintArray(coveredMutationIndexes));
            value.add(new UnicodeString(policyId));
            value.add(new UnsignedInteger(policyRevision));
            return StdlibContractCbor.encode(value);
        }

        public static MapApprovalReferenceV1 decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    StdlibContractCbor.decodeArray(bytes, 6).getDataItems();
            requireVersion(values.get(0));
            Array covered = StdlibContractCbor.array(values.get(3),
                    RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES);
            MapApprovalReferenceV1 decoded = new MapApprovalReferenceV1(
                    StdlibContractCbor.text(values.get(1)),
                    StdlibContractCbor.bytes(values.get(2), 32),
                    covered.getDataItems().stream().map(StdlibContractCbor::uintInt).toList(),
                    StdlibContractCbor.text(values.get(4)),
                    StdlibContractCbor.uint(values.get(5)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record AuthenticatedMapCommandV1(
            MapActionV1 action,
            List<AuthorizationEvidenceV1> evidence
    ) {
        public AuthenticatedMapCommandV1 {
            Objects.requireNonNull(action, "action");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            if (evidence.size() > RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS) {
                throw new IllegalArgumentException("too many authorization evidence items");
            }
            validateEvidence(action, evidence);
        }

        @Override public List<AuthorizationEvidenceV1> evidence() {
            return List.copyOf(evidence);
        }

        public int cryptoWorkUnits() {
            return (int) evidence.stream()
                    .filter(item -> item.kind() == EVIDENCE_ACTOR)
                    .count();
        }
    }

    public record DirectConsumptionV1(
            String actorId,
            byte[] authorizationId,
            byte[] actionCommitment,
            long appliedHeight,
            byte[] messageId,
            List<Integer> mutationIndexes,
            String policyId,
            long policyRevision,
            long actorRevision,
            String organizationId,
            long organizationRevision,
            String role,
            String keyId,
            byte[] statementDigest,
            byte[] signatureDigest
    ) {
        public DirectConsumptionV1 {
            actorId = RoleWorkflowIdentifiers.id(actorId, "actorId");
            authorizationId = exact(authorizationId, 32, "authorizationId");
            actionCommitment = exact(actionCommitment, 32, "actionCommitment");
            messageId = exact(messageId, 32, "messageId");
            mutationIndexes = indexes(mutationIndexes);
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            organizationId = RoleWorkflowIdentifiers.id(organizationId, "organizationId");
            role = RoleWorkflowIdentifiers.role(role);
            keyId = RoleWorkflowIdentifiers.id(keyId, "keyId");
            statementDigest = exact(statementDigest, 32, "statementDigest");
            signatureDigest = exact(signatureDigest, 32, "signatureDigest");
            if (appliedHeight < 1 || policyRevision < 1 || actorRevision < 1
                    || organizationRevision < 1) {
                throw new IllegalArgumentException("invalid direct-consumption record");
            }
        }

        @Override public byte[] authorizationId() { return authorizationId.clone(); }
        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
        @Override public byte[] messageId() { return messageId.clone(); }
        @Override public List<Integer> mutationIndexes() { return List.copyOf(mutationIndexes); }
        @Override public byte[] statementDigest() { return statementDigest.clone(); }
        @Override public byte[] signatureDigest() { return signatureDigest.clone(); }

        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(CODEC_VERSION));
            value.add(new UnicodeString(actorId));
            value.add(new ByteString(authorizationId));
            value.add(new ByteString(actionCommitment));
            value.add(new UnsignedInteger(appliedHeight));
            value.add(new ByteString(messageId));
            value.add(uintArray(mutationIndexes));
            value.add(new UnicodeString(policyId));
            value.add(new UnsignedInteger(policyRevision));
            value.add(new UnsignedInteger(actorRevision));
            value.add(new UnicodeString(organizationId));
            value.add(new UnsignedInteger(organizationRevision));
            value.add(new UnicodeString(role));
            value.add(new UnicodeString(keyId));
            value.add(new ByteString(statementDigest));
            value.add(new ByteString(signatureDigest));
            return StdlibContractCbor.encode(value);
        }

        public static DirectConsumptionV1 decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    StdlibContractCbor.decodeArray(bytes, 16).getDataItems();
            requireVersion(values.get(0));
            DirectConsumptionV1 decoded = new DirectConsumptionV1(
                    StdlibContractCbor.text(values.get(1)),
                    StdlibContractCbor.bytes(values.get(2), 32),
                    StdlibContractCbor.bytes(values.get(3), 32),
                    StdlibContractCbor.uint(values.get(4)),
                    StdlibContractCbor.bytes(values.get(5), 32),
                    decodeIndexes(values.get(6)),
                    StdlibContractCbor.text(values.get(7)),
                    StdlibContractCbor.uint(values.get(8)),
                    StdlibContractCbor.uint(values.get(9)),
                    StdlibContractCbor.text(values.get(10)),
                    StdlibContractCbor.uint(values.get(11)),
                    StdlibContractCbor.text(values.get(12)),
                    StdlibContractCbor.text(values.get(13)),
                    StdlibContractCbor.bytes(values.get(14), 32),
                    StdlibContractCbor.bytes(values.get(15), 32));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    public record ApprovalConsumptionV1(
            String proposalId,
            byte[] actionCommitment,
            long appliedHeight,
            byte[] messageId,
            List<Integer> mutationIndexes,
            String policyId,
            long policyRevision
    ) {
        public ApprovalConsumptionV1 {
            proposalId = RoleWorkflowIdentifiers.id(proposalId, "proposalId");
            actionCommitment = exact(actionCommitment, 32, "actionCommitment");
            messageId = exact(messageId, 32, "messageId");
            mutationIndexes = indexes(mutationIndexes);
            policyId = RoleWorkflowIdentifiers.id(policyId, "policyId");
            if (appliedHeight < 1 || policyRevision < 1) {
                throw new IllegalArgumentException("invalid approval-consumption record");
            }
        }

        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
        @Override public byte[] messageId() { return messageId.clone(); }
        @Override public List<Integer> mutationIndexes() { return List.copyOf(mutationIndexes); }

        public byte[] encode() {
            Array value = new Array();
            value.add(new UnsignedInteger(CODEC_VERSION));
            value.add(new UnicodeString(proposalId));
            value.add(new ByteString(actionCommitment));
            value.add(new UnsignedInteger(appliedHeight));
            value.add(new ByteString(messageId));
            value.add(uintArray(mutationIndexes));
            value.add(new UnicodeString(policyId));
            value.add(new UnsignedInteger(policyRevision));
            return StdlibContractCbor.encode(value);
        }

        public static ApprovalConsumptionV1 decode(byte[] bytes) {
            List<co.nstant.in.cbor.model.DataItem> values =
                    StdlibContractCbor.decodeArray(bytes, 8).getDataItems();
            requireVersion(values.get(0));
            ApprovalConsumptionV1 decoded = new ApprovalConsumptionV1(
                    StdlibContractCbor.text(values.get(1)),
                    StdlibContractCbor.bytes(values.get(2), 32),
                    StdlibContractCbor.uint(values.get(3)),
                    StdlibContractCbor.bytes(values.get(4), 32),
                    decodeIndexes(values.get(5)),
                    StdlibContractCbor.text(values.get(6)),
                    StdlibContractCbor.uint(values.get(7)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    private static void validateEvidence(
            MapActionV1 action,
            List<AuthorizationEvidenceV1> evidence
    ) {
        byte[] commitment = actionCommitment(action);
        Map<Integer, List<Integer>> expectedCoverage = new HashMap<>();
        for (AuthorizationAssignmentV1 assignment : action.authorizations()) {
            if (assignment.evidenceHandle() == NO_EVIDENCE_HANDLE) continue;
            if (assignment.evidenceHandle() > evidence.size()) {
                throw new IllegalArgumentException("authorization evidence handle is out of range");
            }
            AuthorizationEvidenceV1 selected = evidence.get(assignment.evidenceHandle() - 1);
            int expectedKind = assignment.authorizationKind()
                    == AuthenticatedMapContract.AUTH_GOVERNED_ROLE
                    ? EVIDENCE_ACTOR : EVIDENCE_APPROVAL;
            if (selected.kind() != expectedKind
                    || !selected.policyId().equals(assignment.policyId())) {
                throw new IllegalArgumentException("authorization evidence kind/policy mismatch");
            }
            expectedCoverage.computeIfAbsent(assignment.evidenceHandle(), ignored ->
                    new ArrayList<>()).add(assignment.mutationIndex());
        }

        Set<String> actorClaims = new HashSet<>();
        Set<String> proposals = new HashSet<>();
        for (int index = 0; index < evidence.size(); index++) {
            int handle = index + 1;
            AuthorizationEvidenceV1 item = evidence.get(index);
            List<Integer> expected = expectedCoverage.get(handle);
            if (expected == null || !expected.equals(item.coveredMutationIndexes())
                    || !Arrays.equals(commitment, item.actionCommitment())) {
                throw new IllegalArgumentException("authorization evidence coverage is not exact");
            }
            if (item instanceof MapActorAuthorizationV1 actor) {
                String claim = actor.actorId() + ":" + java.util.HexFormat.of()
                        .formatHex(actor.authorizationId());
                if (!actorClaims.add(claim)) {
                    throw new IllegalArgumentException("duplicate direct authorization claim");
                }
            } else if (item instanceof MapApprovalReferenceV1 approval
                    && !proposals.add(approval.proposalId())) {
                throw new IllegalArgumentException("duplicate approval proposal reference");
            }
        }
    }

    private static Array encodeMutation(AuthenticatedMapContract.Mutation mutation) {
        Array item = new Array();
        item.add(new UnsignedInteger(mutation.operation()));
        item.add(new UnicodeString(mutation.collectionId()));
        item.add(new ByteString(mutation.applicationKey()));
        item.add(new ByteString(mutation.value()));
        item.add(new UnsignedInteger(mutation.expectedRevision()));
        item.add(new ByteString(mutation.expectedValueHash()));
        item.add(new ByteString(mutation.newController()));
        return item;
    }

    private static AuthenticatedMapContract.Mutation decodeMutation(
            co.nstant.in.cbor.model.DataItem item
    ) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.array(item, 7).getDataItems();
        if (values.size() != 7) throw malformed();
        return new AuthenticatedMapContract.Mutation(
                StdlibContractCbor.uintInt(values.get(0)),
                StdlibContractCbor.text(values.get(1)),
                StdlibContractCbor.bytes(values.get(2)),
                StdlibContractCbor.bytes(values.get(3)),
                StdlibContractCbor.uint(values.get(4)),
                StdlibContractCbor.bytes(values.get(5)),
                StdlibContractCbor.bytes(values.get(6)));
    }

    private static Array encodeAssignment(AuthorizationAssignmentV1 assignment) {
        Array item = new Array();
        item.add(new UnsignedInteger(assignment.mutationIndex()));
        item.add(new UnsignedInteger(assignment.authorizationKind()));
        item.add(new UnicodeString(assignment.policyId()));
        item.add(new UnsignedInteger(assignment.evidenceHandle()));
        return item;
    }

    private static AuthorizationAssignmentV1 decodeAssignment(
            co.nstant.in.cbor.model.DataItem item
    ) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.array(item, 4).getDataItems();
        if (values.size() != 4) throw malformed();
        return new AuthorizationAssignmentV1(
                StdlibContractCbor.uintInt(values.get(0)),
                StdlibContractCbor.uintInt(values.get(1)),
                StdlibContractCbor.text(values.get(2)),
                StdlibContractCbor.uintInt(values.get(3)));
    }

    private static List<Integer> indexes(List<Integer> values) {
        if (values == null || values.isEmpty()
                || values.size() > RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES) {
            throw new IllegalArgumentException("invalid covered mutation indexes");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid covered mutation indexes");
        }
        List<Integer> sorted = values.stream().sorted().toList();
        if (sorted.stream().anyMatch(value -> value < 0
                || value >= AuthenticatedMapContract.MAX_BATCH_ITEMS)
                || sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("invalid covered mutation indexes");
        }
        return sorted;
    }

    private static Array uintArray(List<Integer> values) {
        Array array = new Array();
        values.forEach(value -> array.add(new UnsignedInteger(value)));
        return array;
    }

    private static List<Integer> decodeIndexes(co.nstant.in.cbor.model.DataItem value) {
        return StdlibContractCbor.array(value,
                        RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES)
                .getDataItems().stream().map(StdlibContractCbor::uintInt).toList();
    }

    private static String optionalId(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value.isEmpty() ? value : RoleWorkflowIdentifiers.id(value, name);
    }

    private static byte[] exact(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must contain " + length + " bytes");
        }
        return value.clone();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireVersion(co.nstant.in.cbor.model.DataItem value) {
        if (StdlibContractCbor.uintInt(value) != CODEC_VERSION) throw malformed();
    }

    private static void requireCanonical(byte[] actual, byte[] canonical) {
        if (!Arrays.equals(actual, canonical)) throw malformed();
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
                "invalid canonical governed authenticated-map contract value");
    }
}
