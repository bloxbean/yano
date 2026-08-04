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
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RegistryMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.internal.GovernedMutationProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorGovernanceProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.OverlayState;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/** Threshold-governed, append-revision domain organization/actor/key registry. */
public final class DomainActorRegistryComponent implements CompositeComponent {
    public static final String COMPONENT_ID = RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID;
    public static final String TOPIC = ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC;
    public static final String QUERY_ORGANIZATION = "organization";
    public static final String QUERY_ORGANIZATION_CURRENT = "organization-current";
    public static final String QUERY_ACTOR = "actor";
    public static final String QUERY_ACTOR_CURRENT = "actor-current";
    public static final String QUERY_AUTHORITY = "administrator-authority";
    public static final String QUERY_AUTHORITY_CURRENT = "administrator-authority-current";
    public static final String QUERY_GOVERNANCE_MUTATION = "governance-mutation";
    public static final String QUERY_COMMAND_RESULT = "command-result";
    public static final String QUERY_PENDING_GOVERNANCE = "pending-governance";

    private final ComponentDescriptor descriptor;
    private final String chainId;
    private final GovernedMutationProcessor governance;
    private final GovernedGenesisV1 genesis;
    private final ActorGovernanceProcessor actorGovernance;

    public DomainActorRegistryComponent(ComponentDescriptor descriptor, String chainId,
                                        RoleWorkflowGovernanceConfig governanceConfig) {
        this(descriptor, chainId, governanceConfig, null, null);
    }

    /** Genesis-bound component used by the authenticated-map composite profile. */
    public static DomainActorRegistryComponent genesisBound(
            ComponentDescriptor descriptor,
            String chainId,
            GovernedGenesisV1 genesis,
            byte[] authenticatedMapGenesisId
    ) {
        return new DomainActorRegistryComponent(descriptor, chainId, null,
                genesis, authenticatedMapGenesisId);
    }

    private DomainActorRegistryComponent(
            ComponentDescriptor descriptor,
            String chainId,
            RoleWorkflowGovernanceConfig governanceConfig,
            GovernedGenesisV1 genesis,
            byte[] authenticatedMapGenesisId
    ) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        this.chainId = com.bloxbean.cardano.yano.appchain.roles.contracts
                .RoleWorkflowIdentifiers.chainId(chainId);
        this.governance = governanceConfig == null
                ? null : new GovernedMutationProcessor(governanceConfig);
        this.genesis = genesis;
        this.actorGovernance = genesis == null ? null : new ActorGovernanceProcessor(
                this.chainId, authenticatedMapGenesisId,
                genesis.administratorAuthority().authorityId(), genesis.limits());
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
        if (actorGovernance != null) {
            try {
                ActorGovernanceCommandV1 command =
                        ActorGovernanceCommandV1.decode(message.getBody());
                if (command.authorizations().stream().anyMatch(
                        authorization -> !authorization.verifyClaimedKey())) {
                    return AppStateMachine.AdmissionResult.reject("INVALID_SIGNATURE");
                }
                if (command.operation() == ActorGovernanceCommandV1.Operation.PROPOSE) {
                    RegistryMutationV1.decode(command.mutation());
                }
                return AppStateMachine.AdmissionResult.accept();
            } catch (RuntimeException malformed) {
                return AppStateMachine.AdmissionResult.reject("INVALID_PAYLOAD");
            }
        }
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
        if (actorGovernance != null) {
            actorGovernance.prepareHeight(block.height(), ownState, ownState);
            ActorGovernanceProcessor.MutationHandler handler = actorGovernanceHandler();
            for (AppMessage message : block.messages()) {
                byte[] resultKey = RoleWorkflowKeys.commandResult(message.getMessageId());
                if (ownState.get(resultKey).isPresent()) continue;
                try {
                    ActorGovernanceCommandV1 command =
                            ActorGovernanceCommandV1.decode(message.getBody());
                    RoleWorkflowResultCode result = actorGovernance.apply(
                            command, block.height(), ownState, ownState, handler);
                    ownState.put(resultKey, new RoleCommandResultV1(
                            RoleCommandResultV1.KIND_REGISTRY_GOVERNANCE,
                            command.mutationId(), result, block.height(),
                            message.getMessageId(), RoleCommandResultV1.commandDigest(
                            message.getBody())).encode());
                } catch (IllegalArgumentException malformed) {
                    // Canonically malformed finalized commands are deterministic no-ops.
                }
            }
            return;
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

    private ActorGovernanceProcessor.MutationHandler actorGovernanceHandler() {
        return new ActorGovernanceProcessor.MutationHandler() {
            @Override
            public void validate(
                    byte[] encoded,
                    AppStateWriter authorityState,
                    AppStateWriter ownedState
            ) {
                RegistryMutationV1 mutation = RegistryMutationV1.decode(encoded);
                if (!canActivateMutation(mutation, ownedState, 0)) {
                    throw new IllegalArgumentException("registry mutation is not activatable");
                }
            }

            @Override
            public boolean activate(
                    byte[] encoded,
                    long height,
                    AppStateWriter authorityState,
                    AppStateWriter ownedState
            ) {
                RegistryMutationV1 mutation = RegistryMutationV1.decode(encoded);
                return canActivateMutation(mutation, ownedState, height)
                        && activateMutation(mutation, ownedState);
            }
        };
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
        ActorGovernanceProcessor.verifyPendingState(state, genesis.limits());
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
        if (QUERY_COMMAND_RESULT.equals(localPath)) {
            if (params == null || params.length != 32) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "command-result query requires a 32-byte message id");
            }
            return ownState.get(RoleWorkflowKeys.commandResult(params))
                    .orElse(new byte[0]);
        }
        if (QUERY_PENDING_GOVERNANCE.equals(localPath)) {
            if (actorGovernance == null || genesis == null) {
                throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "actor governance is disabled by genesis");
            }
            RolePendingQueriesV1.PageQuery query;
            try {
                query = RolePendingQueriesV1.PageQuery.decode(params);
            } catch (IllegalArgumentException malformed) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "invalid pending-governance page query");
            }
            if (query.limit() > genesis.limits().maximumQueryPageSize()) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "pending-governance page exceeds genesis limit");
            }
            return ActorGovernanceProcessor.pendingPage(ownState, query,
                    genesis.limits().maximumQueryPageSize()).encode();
        }
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
        if (QUERY_AUTHORITY_CURRENT.equals(localPath)) {
            if (ref.revision != 0) throw currentQueryRevision();
            return RoleState.pointerBytes(ownState,
                    RoleWorkflowKeys.authorityCurrent(ref.id));
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
        if (QUERY_AUTHORITY.equals(localPath)) {
            long revision = ref.revision != 0 ? ref.revision
                    : RoleState.pointer(ownState, RoleWorkflowKeys.authorityCurrent(ref.id));
            return revision == 0 ? new byte[0] : ownState.get(
                    RoleWorkflowKeys.authorityRevision(ref.id, revision))
                    .orElse(new byte[0]);
        }
        if (QUERY_GOVERNANCE_MUTATION.equals(localPath)) {
            if (ref.revision != 0) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "governance-mutation queries do not accept a revision");
            }
            return ownState.get(RoleWorkflowKeys.governedMutation(ref.id))
                    .orElse(new byte[0]);
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unsupported domain actor query");
    }

    private static AppQueryException currentQueryRevision() {
        return new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "current-pointer queries do not accept a revision");
    }

    private boolean activateMutation(RegistryMutationV1 mutation, AppStateWriter state) {
        if (!canActivateMutation(mutation, state, 0)) {
            return false;
        }
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
        if (mutation instanceof RegistryMutationV1.PutActor put) {
            ActorRecordV1 record = put.actor();
            state.put(RoleWorkflowKeys.actorRevision(
                    record.actorId(), record.revision()), record.encode());
            RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(
                    record.actorId()), record.revision());
            return true;
        }
        RegistryMutationV1.PutAuthority put =
                (RegistryMutationV1.PutAuthority) mutation;
        if (genesis == null) return false;
        AdministratorAuthorityV1 authority = put.authority();
        state.put(RoleWorkflowKeys.authorityRevision(
                authority.authorityId(), authority.revision()), authority.encode());
        RoleState.pointer(state, RoleWorkflowKeys.authorityCurrent(
                authority.authorityId()), authority.revision());
        return true;
    }

    private boolean canActivateMutation(
            RegistryMutationV1 mutation,
            AppStateWriter state,
            long height
    ) {
        if (mutation instanceof RegistryMutationV1.PutOrganization put) {
            OrganizationRecordV1 record = put.organization();
            return record.revision() == RoleState.pointer(state,
                    RoleWorkflowKeys.organizationCurrent(record.organizationId())) + 1
                    && state.get(RoleWorkflowKeys.organizationRevision(
                    record.organizationId(), record.revision())).isEmpty()
                    && keepsCurrentAuthoritySatisfiable(mutation, state, height);
        }
        if (mutation instanceof RegistryMutationV1.PutActor put) {
            ActorRecordV1 record = put.actor();
            OrganizationRecordV1 organization = currentOrganization(
                    state, record.organizationId());
            if (organization == null || record.status() == RecordStatus.ACTIVE
                    && organization.status() != RecordStatus.ACTIVE) {
                return false;
            }
            long currentRevision = RoleState.pointer(state,
                    RoleWorkflowKeys.actorCurrent(record.actorId()));
            ActorRecordV1 prior = currentRevision == 0 ? null
                    : actor(state, record.actorId(), currentRevision);
            return record.revision() == currentRevision + 1
                    && state.get(RoleWorkflowKeys.actorRevision(
                    record.actorId(), record.revision())).isEmpty()
                    && validKeyEvolution(record, prior, put)
                    && keepsCurrentAuthoritySatisfiable(mutation, state, height);
        }
        RegistryMutationV1.PutAuthority put =
                (RegistryMutationV1.PutAuthority) mutation;
        if (genesis == null) return false;
        AdministratorAuthorityV1 authority = put.authority();
        AdministratorAuthorityV1 current = currentAuthority(state);
        if (!authority.authorityId().equals(current.authorityId())
                || authority.revision() != current.revision() + 1
                || state.get(RoleWorkflowKeys.authorityRevision(
                authority.authorityId(), authority.revision())).isPresent()) {
            return false;
        }
        long eligible = authority.administratorActorIds().stream()
                .map(actorId -> currentActor(state, actorId))
                .filter(java.util.Objects::nonNull)
                .filter(actor -> actor.status() == RecordStatus.ACTIVE)
                .filter(actor -> {
                    OrganizationRecordV1 organization = currentOrganization(
                            state, actor.organizationId());
                    return organization != null
                            && organization.status() == RecordStatus.ACTIVE;
                })
                .filter(actor -> actor.keys().stream().anyMatch(key -> height == 0
                        ? key.status() == RecordStatus.ACTIVE
                        : key.activeAt(height)))
                .count();
        return eligible >= authority.distinctActorThreshold();
    }

    private boolean keepsCurrentAuthoritySatisfiable(
            RegistryMutationV1 mutation,
            AppStateWriter state,
            long height
    ) {
        if (genesis == null) return true;
        AdministratorAuthorityV1 authority = currentAuthority(state);
        if (authority == null) return false;
        long eligible = authority.administratorActorIds().stream()
                .map(actorId -> candidateActor(mutation, state, actorId))
                .filter(java.util.Objects::nonNull)
                .filter(actor -> actor.status() == RecordStatus.ACTIVE)
                .filter(actor -> {
                    OrganizationRecordV1 organization = candidateOrganization(
                            mutation, state, actor.organizationId());
                    return organization != null
                            && organization.status() == RecordStatus.ACTIVE;
                })
                .filter(actor -> actor.keys().stream().anyMatch(key -> height == 0
                        ? key.status() == RecordStatus.ACTIVE
                        : key.activeAt(height)))
                .count();
        return eligible >= authority.distinctActorThreshold();
    }

    private static ActorRecordV1 candidateActor(
            RegistryMutationV1 mutation,
            AppStateWriter state,
            String actorId
    ) {
        if (mutation instanceof RegistryMutationV1.PutActor put
                && put.actor().actorId().equals(actorId)) {
            return put.actor();
        }
        return currentActor(state, actorId);
    }

    private static OrganizationRecordV1 candidateOrganization(
            RegistryMutationV1 mutation,
            AppStateWriter state,
            String organizationId
    ) {
        if (mutation instanceof RegistryMutationV1.PutOrganization put
                && put.organization().organizationId().equals(organizationId)) {
            return put.organization();
        }
        return currentOrganization(state, organizationId);
    }

    private AdministratorAuthorityV1 currentAuthority(AppStateWriter state) {
        String authorityId = genesis.administratorAuthority().authorityId();
        long revision = RoleState.pointer(
                state, RoleWorkflowKeys.authorityCurrent(authorityId));
        if (revision == 0) return null;
        byte[] encoded = state.get(RoleWorkflowKeys.authorityRevision(
                authorityId, revision)).orElseThrow(() ->
                new IllegalStateException("authority current pointer is dangling"));
        return AdministratorAuthorityV1.decode(encoded);
    }

    private static ActorRecordV1 currentActor(AppStateWriter state, String actorId) {
        long revision = RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(actorId));
        return revision == 0 ? null : actor(state, actorId, revision);
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
