package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RegistryMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.internal.GovernedMutationProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.OverlayState;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Threshold-governed, append-revision domain organization/actor/key registry. */
public final class DomainActorRegistryComponent implements CompositeComponent {
    public static final String COMPONENT_ID = "domain-actors";
    public static final String TOPIC = "actors.command.v1";
    public static final String QUERY_ORGANIZATION = "organization";
    public static final String QUERY_ORGANIZATION_CURRENT = "organization-current";
    public static final String QUERY_ACTOR = "actor";
    public static final String QUERY_ACTOR_CURRENT = "actor-current";

    private final ComponentDescriptor descriptor;
    private final String chainId;
    private final GovernedMutationProcessor governance;

    public DomainActorRegistryComponent(ComponentDescriptor descriptor, String chainId,
                                        RoleWorkflowGovernanceConfig governanceConfig) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        this.chainId = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.id(chainId, "chainId");
        this.governance = new GovernedMutationProcessor(governanceConfig);
        if (!descriptor.componentId().equals(COMPONENT_ID)
                || !descriptor.topics().equals(java.util.List.of(TOPIC))) {
            throw new IllegalArgumentException("invalid domain actor registry descriptor");
        }
    }

    @Override public ComponentDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            GovernedMutationCommandV1 command =
                    GovernedMutationCommandV1.decode(message.getBody());
            if (command instanceof GovernedMutationCommandV1.Propose proposed) {
                RegistryMutationV1.decode(proposed.mutation());
            }
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_ACTOR_REGISTRY_COMMAND");
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter ownState, AppEffectEmitter effects) {
        OverlayState state = new OverlayState(ownState);
        GovernedMutationProcessor.MutationHandler handler =
                new GovernedMutationProcessor.MutationHandler() {
                    @Override public void validate(byte[] mutation) {
                        RegistryMutationV1.decode(mutation);
                    }

                    @Override public boolean activate(byte[] mutation, long height,
                                                      AppStateWriter writer) {
                        return activateMutation(RegistryMutationV1.decode(mutation), writer);
                    }
                };
        for (AppMessage message : block.messages()) {
            try {
                governance.apply(GovernedMutationCommandV1.decode(message.getBody()),
                        message.getSender(), block.height(), state, handler);
            } catch (IllegalArgumentException malformed) {
                // Admission is repeated during apply; invalid finalized input is a no-op.
            }
        }
    }

    @Override
    public byte[] query(String localPath, byte[] params, AppQueryContext ownState) {
        QueryRef ref = queryRef(params);
        if (QUERY_ORGANIZATION_CURRENT.equals(localPath)) {
            if (ref.revision != 0) throw currentQueryRevision();
            return RoleState.pointerBytes(ownState,
                    RoleWorkflowKeys.organizationCurrent(ref.id));
        }
        if (QUERY_ACTOR_CURRENT.equals(localPath)) {
            if (ref.revision != 0) throw currentQueryRevision();
            return RoleState.pointerBytes(ownState, RoleWorkflowKeys.actorCurrent(ref.id));
        }
        if (QUERY_ORGANIZATION.equals(localPath)) {
            long revision = ref.revision != 0 ? ref.revision
                    : RoleState.pointer(ownState, RoleWorkflowKeys.organizationCurrent(ref.id));
            return revision == 0 ? new byte[0] : ownState.get(
                    RoleWorkflowKeys.organizationRevision(ref.id, revision)).orElse(new byte[0]);
        }
        if (QUERY_ACTOR.equals(localPath)) {
            long revision = ref.revision != 0 ? ref.revision
                    : RoleState.pointer(ownState, RoleWorkflowKeys.actorCurrent(ref.id));
            return revision == 0 ? new byte[0] : ownState.get(
                    RoleWorkflowKeys.actorRevision(ref.id, revision)).orElse(new byte[0]);
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unsupported domain actor query");
    }

    private static AppQueryException currentQueryRevision() {
        return new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "current-pointer queries do not accept a revision");
    }

    private boolean activateMutation(RegistryMutationV1 mutation, AppStateWriter state) {
        if (mutation instanceof RegistryMutationV1.PutOrganization put) {
            OrganizationRecordV1 record = put.organization();
            long current = RoleState.pointer(state,
                    RoleWorkflowKeys.organizationCurrent(record.organizationId()));
            if (record.revision() != current + 1) return false;
            state.put(RoleWorkflowKeys.organizationRevision(
                    record.organizationId(), record.revision()), record.encode());
            RoleState.pointer(state, RoleWorkflowKeys.organizationCurrent(
                    record.organizationId()), record.revision());
            return true;
        }
        RegistryMutationV1.PutActor put = (RegistryMutationV1.PutActor) mutation;
        ActorRecordV1 record = put.actor();
        OrganizationRecordV1 organization = currentOrganization(state, record.organizationId());
        if (organization == null
                || (record.status() == RecordStatus.ACTIVE
                && organization.status() != RecordStatus.ACTIVE)) return false;
        long currentRevision = RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(record.actorId()));
        if (record.revision() != currentRevision + 1) return false;
        ActorRecordV1 prior = currentRevision == 0 ? null : actor(state, record.actorId(), currentRevision);
        if (!validKeyEvolution(record, prior, put)) return false;
        state.put(RoleWorkflowKeys.actorRevision(record.actorId(), record.revision()), record.encode());
        RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(record.actorId()), record.revision());
        return true;
    }

    private boolean validKeyEvolution(ActorRecordV1 record, ActorRecordV1 prior,
                                      RegistryMutationV1.PutActor mutation) {
        if (prior != null && prior.keys().stream()
                .anyMatch(old -> record.key(old.keyId()) == null)) return false;
        long newKeys = 0;
        for (ActorKeyEpochV1 key : record.keys()) {
            ActorKeyEpochV1 old = prior != null ? prior.key(key.keyId()) : null;
            boolean newKey = old == null;
            if (old != null && !validExistingKeyEvolution(old, key)) return false;
            if (!newKey) continue;
            newKeys++;
            ActorKeyProofV1 proof = mutation.keyProofs().stream()
                    .filter(candidate -> candidate.actorId().equals(record.actorId())
                            && candidate.actorRevision() == record.revision()
                            && candidate.chainId().equals(chainId)
                            && sameKey(candidate.key(), key))
                    .findFirst().orElse(null);
            if (proof == null || !proof.verify()) return false;
        }
        return mutation.keyProofs().size() == newKeys;
    }

    private static boolean validExistingKeyEvolution(ActorKeyEpochV1 oldKey,
                                                     ActorKeyEpochV1 newKey) {
        if (!MessageDigest.isEqual(oldKey.publicKey(), newKey.publicKey())
                || oldKey.validFromHeight() != newKey.validFromHeight()) return false;
        if (oldKey.status() == RecordStatus.REVOKED
                && newKey.status() != RecordStatus.REVOKED) return false;
        return oldKey.validUntilHeight() == 0
                || newKey.validUntilHeight() != 0
                && newKey.validUntilHeight() <= oldKey.validUntilHeight();
    }

    private static boolean sameKey(ActorKeyEpochV1 left, ActorKeyEpochV1 right) {
        return left.keyId().equals(right.keyId())
                && MessageDigest.isEqual(left.publicKey(), right.publicKey())
                && left.validFromHeight() == right.validFromHeight()
                && left.validUntilHeight() == right.validUntilHeight()
                && left.status() == right.status();
    }

    private static OrganizationRecordV1 currentOrganization(AppStateWriter state, String id) {
        long revision = RoleState.pointer(state, RoleWorkflowKeys.organizationCurrent(id));
        if (revision == 0) return null;
        byte[] encoded = state.get(RoleWorkflowKeys.organizationRevision(id, revision)).orElse(null);
        if (encoded == null) throw new IllegalStateException("organization current pointer is dangling");
        try {
            return OrganizationRecordV1.decode(encoded);
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt organization record", corrupt);
        }
    }

    private static ActorRecordV1 actor(AppStateWriter state, String id, long revision) {
        byte[] encoded = state.get(RoleWorkflowKeys.actorRevision(id, revision)).orElse(null);
        if (encoded == null) throw new IllegalStateException("actor current pointer is dangling");
        try {
            return ActorRecordV1.decode(encoded);
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt actor record", corrupt);
        }
    }

    private static QueryRef queryRef(byte[] params) {
        try {
            String value = new String(params, StandardCharsets.US_ASCII);
            String[] fields = value.split("@", -1);
            if (fields.length > 2 || fields[0].isEmpty()) throw new IllegalArgumentException();
            com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers
                    .id(fields[0], "query id");
            long revision = fields.length == 2 ? Long.parseLong(fields[1]) : 0;
            if (revision < 0) throw new IllegalArgumentException();
            return new QueryRef(fields[0], revision);
        } catch (RuntimeException invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "query must be id or id@revision");
        }
    }

    private record QueryRef(String id, long revision) {
    }
}
