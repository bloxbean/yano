package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
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
import java.util.List;

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
    private final GovernedGenesisV1 genesis;

    public DomainActorRegistryComponent(ComponentDescriptor descriptor, String chainId,
                                        RoleWorkflowGovernanceConfig governanceConfig) {
        this(descriptor, chainId, governanceConfig, null);
    }

    /** Genesis-bound component used by the authenticated-map composite profile. */
    public static DomainActorRegistryComponent genesisBound(
            ComponentDescriptor descriptor,
            String chainId,
            GovernedGenesisV1 genesis
    ) {
        return new DomainActorRegistryComponent(descriptor, chainId, null, genesis);
    }

    private DomainActorRegistryComponent(
            ComponentDescriptor descriptor,
            String chainId,
            RoleWorkflowGovernanceConfig governanceConfig,
            GovernedGenesisV1 genesis
    ) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        this.chainId = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.chainId(chainId);
        this.governance = governanceConfig == null
                ? null : new GovernedMutationProcessor(governanceConfig);
        this.genesis = genesis;
        List<String> expectedTopics = governanceConfig != null || genesis != null
                ? List.of(TOPIC) : List.of();
        if (!descriptor.componentId().equals(COMPONENT_ID)
                || !descriptor.topics().equals(expectedTopics)
                || genesis != null && !genesis.chainId().equals(this.chainId)) {
            throw new IllegalArgumentException("invalid domain actor registry descriptor");
        }
    }

    @Override public ComponentDescriptor descriptor() { return descriptor; }

    @Override
    public void init(AppStateReader ownState, AppChainInfo chain) {
        if (!chainId.equals(chain.chainId())) {
            throw new IllegalStateException("domain actor registry belongs to another chain");
        }
        if (genesis != null && ownState.committedHeight() > 0) {
            verifyGenesis(ownState);
        }
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        if (governance == null) {
            return AppStateMachine.AdmissionResult.reject("GOVERNED_ROUTE_UNSUPPORTED");
        }
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
        if (genesis != null) {
            initializeOrVerify(block.height(), ownState);
        }
        if (governance == null) {
            return;
        }
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

    private void initializeOrVerify(long height, AppStateWriter state) {
        if (height != 1) {
            verifyGenesis(state);
            return;
        }
        AdministratorAuthorityV1 authority = genesis.administratorAuthority();
        requireAbsent(state, RoleWorkflowKeys.authorityCurrent(authority.authorityId()));
        requireAbsent(state, RoleWorkflowKeys.authorityRevision(
                authority.authorityId(), authority.revision()));
        for (OrganizationRecordV1 organization : genesis.organizations()) {
            requireAbsent(state, RoleWorkflowKeys.organizationCurrent(
                    organization.organizationId()));
            requireAbsent(state, RoleWorkflowKeys.organizationRevision(
                    organization.organizationId(), organization.revision()));
        }
        for (GenesisActorV1 actor : genesis.actors()) {
            requireAbsent(state, RoleWorkflowKeys.actorCurrent(actor.actor().actorId()));
            requireAbsent(state, RoleWorkflowKeys.actorRevision(
                    actor.actor().actorId(), actor.actor().revision()));
        }

        state.put(RoleWorkflowKeys.authorityRevision(
                authority.authorityId(), authority.revision()), authority.encode());
        RoleState.pointer(state, RoleWorkflowKeys.authorityCurrent(
                authority.authorityId()), authority.revision());
        for (OrganizationRecordV1 organization : genesis.organizations()) {
            state.put(RoleWorkflowKeys.organizationRevision(
                    organization.organizationId(), organization.revision()),
                    organization.encode());
            RoleState.pointer(state, RoleWorkflowKeys.organizationCurrent(
                    organization.organizationId()), organization.revision());
        }
        for (GenesisActorV1 actor : genesis.actors()) {
            ActorRecordV1 record = actor.actor();
            state.put(RoleWorkflowKeys.actorRevision(
                    record.actorId(), record.revision()), record.encode());
            RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(
                    record.actorId()), record.revision());
        }
    }

    private void verifyGenesis(AppStateReader state) {
        AdministratorAuthorityV1 authority = genesis.administratorAuthority();
        requireExact(state, RoleWorkflowKeys.authorityRevision(
                authority.authorityId(), authority.revision()), authority.encode());
        long authorityRevision = requireCurrent(state,
                RoleWorkflowKeys.authorityCurrent(authority.authorityId()));
        AdministratorAuthorityV1 currentAuthority = decodeAuthority(state,
                authority.authorityId(), authorityRevision);
        if (!currentAuthority.authorityId().equals(authority.authorityId())
                || currentAuthority.revision() != authorityRevision) {
            throw new IllegalStateException("domain actor authority pointer is incompatible");
        }
        for (OrganizationRecordV1 organization : genesis.organizations()) {
            requireExact(state, RoleWorkflowKeys.organizationRevision(
                    organization.organizationId(), organization.revision()),
                    organization.encode());
            long revision = requireCurrent(state, RoleWorkflowKeys.organizationCurrent(
                    organization.organizationId()));
            OrganizationRecordV1 current = decodeOrganization(state,
                    organization.organizationId(), revision);
            if (!current.organizationId().equals(organization.organizationId())
                    || current.revision() != revision) {
                throw new IllegalStateException(
                        "domain actor organization pointer is incompatible");
            }
        }
        for (GenesisActorV1 actor : genesis.actors()) {
            ActorRecordV1 record = actor.actor();
            requireExact(state, RoleWorkflowKeys.actorRevision(
                    record.actorId(), record.revision()), record.encode());
            long revision = requireCurrent(state,
                    RoleWorkflowKeys.actorCurrent(record.actorId()));
            ActorRecordV1 current = decodeActor(state, record.actorId(), revision);
            if (!current.actorId().equals(record.actorId())
                    || current.revision() != revision) {
                throw new IllegalStateException("domain actor pointer is incompatible");
            }
        }
    }

    private static void requireAbsent(AppStateReader state, byte[] key) {
        if (state.get(key).isPresent()) {
            throw new IllegalStateException("domain actor genesis state already exists");
        }
    }

    private static void requireExact(AppStateReader state, byte[] key, byte[] expected) {
        byte[] actual = state.get(key).orElseThrow(() ->
                new IllegalStateException("domain actor genesis state is absent"));
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IllegalStateException("domain actor genesis state is incompatible");
        }
    }

    private static long requireCurrent(AppStateReader state, byte[] key) {
        long revision = RoleState.pointer(state, key);
        if (revision < 1) {
            throw new IllegalStateException("domain actor current pointer is absent");
        }
        return revision;
    }

    private static AdministratorAuthorityV1 decodeAuthority(
            AppStateReader state,
            String authorityId,
            long revision
    ) {
        return decodeCurrent(state,
                RoleWorkflowKeys.authorityRevision(authorityId, revision),
                AdministratorAuthorityV1::decode, "authority");
    }

    private static OrganizationRecordV1 decodeOrganization(
            AppStateReader state,
            String organizationId,
            long revision
    ) {
        return decodeCurrent(state,
                RoleWorkflowKeys.organizationRevision(organizationId, revision),
                OrganizationRecordV1::decode, "organization");
    }

    private static ActorRecordV1 decodeActor(
            AppStateReader state,
            String actorId,
            long revision
    ) {
        return decodeCurrent(state, RoleWorkflowKeys.actorRevision(actorId, revision),
                ActorRecordV1::decode, "actor");
    }

    private static <T> T decodeCurrent(
            AppStateReader state,
            byte[] key,
            java.util.function.Function<byte[], T> decoder,
            String kind
    ) {
        byte[] encoded = state.get(key).orElseThrow(() -> new IllegalStateException(
                "domain actor " + kind + " current pointer is dangling"));
        try {
            return decoder.apply(encoded);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException(
                    "domain actor " + kind + " current record is corrupt", malformed);
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
