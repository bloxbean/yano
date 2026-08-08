package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.GovernedRoleApprovalWorkflow;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GenesisActorV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedAdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapCompositeAssemblyTest {
    private static final String CHAIN_ID = "authenticated-map-assembly";
    private static final byte[] MEMBER = repeated(0x31);
    private static final byte[] ACTOR_SEED = repeated(0x41);
    private static final byte[] ACTOR_SEED_B = repeated(0x42);
    private static final byte[] ACTOR_SEED_C = repeated(0x43);

    @Test
    void basicGenesisKeepsOneSelectorAndFinalEnvelopeSemantics() {
        AppChainConfig config = config(CHAIN_ID);
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x11), 16, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "products", AuthenticatedMapContract.AUTH_OWNER,
                        true, 64, 4_096)), List.of());
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);

        assertThat(machine.id()).isEqualTo(AuthenticatedMapContract.STATE_MACHINE_ID);
        assertThat(machine.profile().components())
                .extracting(component -> component.componentId())
                .containsExactly(DomainActorRegistryComponent.COMPONENT_ID,
                        RoleAwareApprovalsComponent.COMPONENT_ID,
                        AuthenticatedMapComponent.COMPONENT_ID);
        assertThat(machine.profile().components().getFirst().topics()).isEmpty();
        assertThat(machine.profile().components().getFirst().queryPaths()).isEmpty();
        assertThat(machine.profile().components().get(1).queryPaths()).isEmpty();
        assertThat(machine.profile().workflows())
                .extracting(workflow -> workflow.workflowId())
                .containsExactly(AuthenticatedMapAuthorizationWorkflow.WORKFLOW_ID);

        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        byte[] applicationKey = bytes("sku-1");
        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "products", applicationKey, bytes("metadata-v1")));
        AppMessage finalCommand = message(AuthenticatedMapContract.DEFAULT_TOPIC, 1,
                finalCommand(mutation, AuthenticatedMapContract.AUTH_OWNER));
        assertThat(machine.validateForBlock(finalCommand, 1, state).isAccepted()).isTrue();

        AppMessage legacyCommand = message(AuthenticatedMapContract.DEFAULT_TOPIC, 2,
                AuthenticatedMapContract.encodeCommand(mutation));
        assertThat(machine.validate(legacyCommand).isAccepted()).isFalse();

        execute(machine, block(1, finalCommand), state);
        state.committedHeight = 1;
        byte[] physicalEntry = physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey("products", applicationKey));
        assertThat(state.get(physicalEntry)).isPresent();
        assertThat(state.get(AuthenticatedMapContract.canonicalKey(
                "products", applicationKey))).isEmpty();

        byte[] response = machine.query(AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.encodePointQuery(
                        AuthenticatedMapContract.PointQuery.current(
                                "products", applicationKey)), state);
        assertThat(AuthenticatedMapContract.decodePointResult(response).entry().value())
                .isEqualTo(bytes("metadata-v1"));
        assertThatThrownBy(() -> machine.query(
                "components/domain-actors/actor", bytes("actor-a"), state))
                .isInstanceOf(AppQueryException.class)
                .extracting(failure -> ((AppQueryException) failure).code())
                .isEqualTo(AppQueryException.Code.UNSUPPORTED);

        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
    }

    @Test
    void governedGenesisCommitsOrderInitializesClosureAndAuthorizesDirectRole() {
        AppChainConfig config = config(CHAIN_ID);
        GovernedFixture fixture = governedFixture();
        AuthenticatedMapContract.Genesis basic = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x12), 16, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "placeholder", AuthenticatedMapContract.AUTH_OPEN,
                        false, 64, 4_096)), List.of());
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                basic.chainId(), basic.commitmentProfileId(), basic.formatFingerprint(),
                basic.frameworkConsensusProfileDigest(), basic.membershipCommitment(),
                basic.anchorPolicyCommitment(), basic.maxBatchItems(), basic.maxBatchBytes(),
                fixture.collections(), basic.validators(), basic.initialEntries(),
                fixture.genesis());
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);

        assertThat(machine.profile().components().getFirst().topics())
                .containsExactly(DomainActorRegistryComponent.TOPIC);
        assertThat(machine.profile().components().getFirst().queryPaths()).isNotEmpty();
        assertThat(machine.profile().components().get(1).queryPaths()).isNotEmpty();
        assertThat(machine.profile().workflows())
                .extracting(workflow -> workflow.workflowId())
                .containsExactly(GovernedRoleApprovalWorkflow.WORKFLOW_ID,
                        AuthenticatedMapAuthorizationWorkflow.WORKFLOW_ID);

        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        assertThat(state.get(physical(DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.organizationRevision("operator-a", 1))))
                .contains(fixture.organization().encode());
        assertThat(state.get(physical(DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.actorRevision("admin-a", 1))))
                .contains(fixture.actor().encode());
        assertThat(state.get(physical(DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.authorityRevision("registry-admins", 1))))
                .contains(fixture.authority().encode());
        assertThat(state.get(physical(RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.directPolicyRevision("issuer-write", 1))))
                .contains(fixture.directPolicy().encode());
        assertThat(state.get(physical(RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.policyRevision("release-policy", 1))))
                .contains(fixture.approvalPolicy().encode());
        assertThat(machine.query("components/domain-actors/actor",
                bytes("admin-a"), state)).isEqualTo(fixture.actor().encode());

        AppMessage roleCommand = message(GovernedRoleApprovalWorkflow.TOPIC, 2,
                new byte[]{(byte) 0x80});
        assertThat(machine.validate(roleCommand).isAccepted()).isFalse();
        assertThat(machine.validate(roleCommand).reason())
                .isEqualTo("INVALID_PAYLOAD");

        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "governed-products", bytes("sku-2"), bytes("metadata-v2")));
        MapActionV1 action = new MapActionV1(false, mutation.mutations(),
                List.of(new AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        "issuer-write", 1)));
        MapActorAuthorizationV1 authorization = MapActorAuthorizationV1.sign(
                repeated(0x51), CHAIN_ID, AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action),
                List.of(0), "issuer-write", 1, "admin-a", 1,
                "admin-a-key", fixture.actor().keys().getFirst().publicKey(),
                1, 20, ACTOR_SEED);
        AppMessage mapCommand = message(AuthenticatedMapContract.DEFAULT_TOPIC, 3,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(action, List.of(authorization))));
        assertThat(machine.validateForBlock(mapCommand, 2, state).isAccepted()).isTrue();
        execute(machine, block(2, mapCommand), state);
        state.committedHeight = 2;

        byte[] receipt = state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.receiptKey(mapCommand.getMessageId())))
                .orElseThrow();
        assertThat(AuthenticatedMapContract.decodeReceipt(receipt).status())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "governed-products", bytes("sku-2"))))).isPresent();
        byte[] consumptionQuery = new DirectConsumptionQueryV1(
                "admin-a", repeated(0x51)).encode();
        DirectConsumptionV1 consumption = DirectConsumptionV1.decode(machine.query(
                AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                consumptionQuery, state));
        assertThat(consumption.actorId()).isEqualTo("admin-a");
        assertThat(consumption.messageId()).isEqualTo(mapCommand.getMessageId());

        AppMessage replay = message(AuthenticatedMapContract.DEFAULT_TOPIC, 4,
                mapCommand.getBody());
        execute(machine, block(3, replay), state);
        state.committedHeight = 3;
        assertReceiptError(state, replay,
                AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY);
        AuthenticatedMapContract.Entry retained = state.get(physical(
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.canonicalKey(
                                "governed-products", bytes("sku-2"))))
                .map(AuthenticatedMapContract::decodeEntry).orElseThrow();
        assertThat(retained.revision()).isEqualTo(1);

        byte[] invalidSignature = authorization.signature();
        invalidSignature[0] ^= 1;
        MapActorAuthorizationV1 forged = new MapActorAuthorizationV1(
                authorization.authorizationId(), authorization.chainId(),
                authorization.genesisId(), authorization.actionCommitment(),
                authorization.coveredMutationIndexes(), authorization.policyId(),
                authorization.policyRevision(), authorization.actorId(),
                authorization.actorRevision(), authorization.keyId(),
                authorization.publicKey(), authorization.issuedHeight(),
                authorization.deadlineHeight(), authorization.signatureAlgorithm(),
                invalidSignature);
        AppMessage invalidSignatureCommand = message(
                AuthenticatedMapContract.DEFAULT_TOPIC, 5,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(action, List.of(forged))));
        assertThat(machine.validate(invalidSignatureCommand).isAccepted()).isFalse();
        assertThat(machine.validate(invalidSignatureCommand).reason())
                .isEqualTo("INVALID_SIGNATURE");
        execute(machine, block(4, invalidSignatureCommand), state);
        state.committedHeight = 4;
        assertReceiptError(state, invalidSignatureCommand,
                AuthenticatedMapContract.ERROR_ACTOR_SIGNATURE);

        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        byte[] authorityRevision = physical(DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.authorityRevision("registry-admins", 1));
        state.put(authorityRevision, new byte[]{1});
        assertThatThrownBy(() -> machine.init(
                state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("genesis state");
        state.put(authorityRevision, fixture.authority().encode());
        state.put(physical(DomainActorRegistryComponent.COMPONENT_ID,
                        RoleWorkflowKeys.authorityCurrent("registry-admins")),
                ByteBuffer.allocate(Long.BYTES).putLong(2).array());
        assertThatThrownBy(() -> machine.init(
                state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pointer is dangling");
    }

    @Test
    void finalWorkflowRetainsCanonicalCborAndSchemaValidation() {
        AppChainConfig config = config(CHAIN_ID);
        AuthenticatedMapSchema.Schema schema = AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.MapNode(List.of(
                        new AuthenticatedMapSchema.MapField("qty", true,
                                new AuthenticatedMapSchema.IntegerNode(
                                        AuthenticatedMapSchema.INTEGER_UINT,
                                        BigInteger.ZERO, BigInteger.TEN)))));
        AuthenticatedMapContract.ValidatorDescriptor validator =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "quantity-v1", schema.definition());
        List<AuthenticatedMapContract.CollectionDescriptor> collections = List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        "canonical", AuthenticatedMapContract.AUTH_OPEN, "",
                        false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, ""),
                new AuthenticatedMapContract.CollectionDescriptor(
                        "schema", AuthenticatedMapContract.AUTH_OPEN, "",
                        false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                        validator.id()));
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x14), 16, 32_768,
                collections, List.of(validator), List.of());
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));

        AppMessage nonCanonical = message(AuthenticatedMapContract.DEFAULT_TOPIC, 7,
                finalCommand(AuthenticatedMapContract.Command.single(
                                AuthenticatedMapContract.Mutation.put(
                                        "canonical", bytes("bad"), hexBytes("1817"))),
                        AuthenticatedMapContract.AUTH_OPEN));
        assertThat(machine.validate(nonCanonical).isAccepted()).isFalse();
        execute(machine, block(1, nonCanonical), state);
        state.committedHeight = 1;
        assertReceiptError(state, nonCanonical,
                AuthenticatedMapContract.ERROR_VALUE_ENCODING);

        AppMessage schemaRejected = message(AuthenticatedMapContract.DEFAULT_TOPIC, 8,
                finalCommand(AuthenticatedMapContract.Command.single(
                                AuthenticatedMapContract.Mutation.put(
                                        "schema", bytes("bad"),
                                        hexBytes("a1637174790b"))),
                        AuthenticatedMapContract.AUTH_OPEN));
        assertThat(machine.validate(schemaRejected).isAccepted()).isFalse();
        execute(machine, block(2, schemaRejected), state);
        state.committedHeight = 2;
        assertReceiptError(state, schemaRejected,
                AuthenticatedMapContract.ERROR_VALUE_SCHEMA);

        AuthenticatedMapContract.Command valid = AuthenticatedMapContract.Command.batch(
                List.of(
                        AuthenticatedMapContract.Mutation.put(
                                "canonical", bytes("good"), hexBytes("a1616101")),
                        AuthenticatedMapContract.Mutation.put(
                                "schema", bytes("good"), hexBytes("a1637174790a"))));
        AppMessage accepted = message(AuthenticatedMapContract.DEFAULT_TOPIC, 9,
                finalCommand(valid, AuthenticatedMapContract.AUTH_OPEN));
        assertThat(machine.validate(accepted).isAccepted()).isTrue();
        execute(machine, block(3, accepted), state);
        state.committedHeight = 3;
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "canonical", bytes("good"))))).isPresent();
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "schema", bytes("good"))))).isPresent();
    }

    @Test
    void finalWorkflowRetainsGenesisPinnedPluginValidationAndAtomicBatches() {
        AppChainConfig config = config(CHAIN_ID);
        AuthenticatedMapContract.ValidatorDescriptor validator =
                AuthenticatedMapContract.ValidatorDescriptor.plugin(
                        "value-v1", "showcase-validator", repeated(0x61),
                        new byte[]{(byte) 0xa0});
        AuthenticatedMapContract.CollectionDescriptor collection =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "records", AuthenticatedMapContract.AUTH_OPEN, "",
                        false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE,
                        validator.id());
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x13), 16, 32_768,
                List.of(collection), List.of(validator), List.of());
        AuthenticatedMapValidatorResolver resolver = (digest, init) ->
                (collectionId, key, value) -> Arrays.equals(value, bytes("valid"))
                        ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT;
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config, resolver), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));

        AuthenticatedMapContract.Command invalid = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("bad"), bytes("invalid")));
        assertThat(machine.validate(message(AuthenticatedMapContract.DEFAULT_TOPIC, 5,
                finalCommand(invalid, AuthenticatedMapContract.AUTH_OPEN))).isAccepted())
                .isFalse();

        AuthenticatedMapContract.Command batch = AuthenticatedMapContract.Command.batch(List.of(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("good"), bytes("valid")),
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("bad"), bytes("invalid"))));
        AppMessage finalizedBatch = message(AuthenticatedMapContract.DEFAULT_TOPIC, 6,
                finalCommand(batch, AuthenticatedMapContract.AUTH_OPEN));
        execute(machine, block(1, finalizedBatch), state);
        state.committedHeight = 1;

        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "records", bytes("good"))))).isEmpty();
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "records", bytes("bad"))))).isEmpty();
        assertReceiptError(state, finalizedBatch,
                AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
    }

    @Test
    void governedPolicyAndActorRevisionsTakeEffectBeforeMapAuthorization() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture fixture = thresholdFixture();
        AuthenticatedMapContract.Genesis basic = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x15), 16, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "placeholder", AuthenticatedMapContract.AUTH_OPEN,
                        false, 64, 4_096)), List.of());
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                basic.chainId(), basic.commitmentProfileId(), basic.formatFingerprint(),
                basic.frameworkConsensusProfileDigest(), basic.membershipCommitment(),
                basic.anchorPolicyCommitment(), basic.maxBatchItems(),
                basic.maxBatchBytes(), fixture.collections(), basic.validators(),
                basic.initialEntries(), fixture.genesis());
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        DirectRolePolicyV1 revisionTwo = new DirectRolePolicyV1(
                "issuer-write", 2, RecordStatus.ACTIVE, "auditor", 100);
        byte[] policyMutation = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .PolicyMutationV1.PutDirectPolicy(revisionTwo).encode();
        GovernanceSubject policySubject = new GovernanceSubject(
                "issuer-policy-2", policyMutation, 4, 20);
        AppMessage proposePolicy = governanceMessage(
                GovernedRoleApprovalWorkflow.TOPIC, 20,
                ActorGovernanceCommandV1.Operation.PROPOSE, policySubject,
                List.of(signedAdministrator(fixture, genesis, policySubject,
                        AdministratorStatementV1.Decision.PROPOSE,
                        fixture.actorA(), ACTOR_SEED, 2)));
        execute(machine, block(2, proposePolicy), state);
        state.committedHeight = 2;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                proposePolicy.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        RolePendingQueriesV1.GovernancePage pendingPolicy =
                RolePendingQueriesV1.GovernancePage.decode(machine.query(
                        "components/role-approvals/pending-governance",
                        new RolePendingQueriesV1.PageQuery("", 10).encode(), state));
        assertThat(pendingPolicy.entries()).extracting(
                        RolePendingQueriesV1.GovernanceEntry::mutationId)
                .containsExactly(policySubject.id());

        AppMessage approvePolicy = governanceMessage(
                GovernedRoleApprovalWorkflow.TOPIC, 21,
                ActorGovernanceCommandV1.Operation.APPROVE, policySubject,
                List.of(signedAdministrator(fixture, genesis, policySubject,
                        AdministratorStatementV1.Decision.APPROVE,
                        fixture.actorC(), ACTOR_SEED_C, 3)));
        execute(machine, block(3, approvePolicy), state);
        state.committedHeight = 3;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                approvePolicy.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);

        AppMessage policyTwoAction = directMapMessage(genesis, fixture.actorB(),
                ACTOR_SEED_B, revisionTwo, repeated(0x72), 22,
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(
                                "governed-products", bytes("policy-two"),
                                bytes("accepted"))), 3, 15);
        AppMessage activatePolicy = governanceMessage(
                GovernedRoleApprovalWorkflow.TOPIC, 23,
                ActorGovernanceCommandV1.Operation.ACTIVATE,
                policySubject, List.of());
        execute(machine, block(4, policyTwoAction, activatePolicy), state);
        state.committedHeight = 4;
        assertThat(state.get(physical(RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.directPolicyCurrent("issuer-write"))))
                .contains(ByteBuffer.allocate(Long.BYTES).putLong(2).array());
        assertThat(machine.query("components/role-approvals/direct-policy",
                bytes("issuer-write@2"), state)).isEqualTo(revisionTwo.encode());
        assertThat(machine.query("components/role-approvals/governance-mutation",
                bytes(policySubject.id()), state)).isNotEmpty();
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                activatePolicy.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(RolePendingQueriesV1.GovernancePage.decode(machine.query(
                "components/role-approvals/pending-governance",
                new RolePendingQueriesV1.PageQuery("", 10).encode(), state))
                .entries()).isEmpty();
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "governed-products", bytes("policy-two"))))).isPresent();

        byte[] sharedAuthorizationId = repeated(0x73);
        AppMessage actorAAction = directMapMessage(genesis, fixture.actorA(),
                ACTOR_SEED, revisionTwo, sharedAuthorizationId, 24,
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(
                                "governed-products", bytes("actor-a"), bytes("a"))),
                4, 15);
        execute(machine, block(5, actorAAction), state);
        state.committedHeight = 5;
        AppMessage actorBAction = directMapMessage(genesis, fixture.actorB(),
                ACTOR_SEED_B, revisionTwo, sharedAuthorizationId, 25,
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(
                                "governed-products", bytes("actor-b"), bytes("b"))),
                5, 15);
        execute(machine, block(6, actorBAction), state);
        state.committedHeight = 6;
        assertThat(machine.query(AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                new DirectConsumptionQueryV1(
                        fixture.actorA().actorId(), sharedAuthorizationId).encode(), state))
                .isNotEmpty();
        assertThat(machine.query(AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                new DirectConsumptionQueryV1(
                        fixture.actorB().actorId(), sharedAuthorizationId).encode(), state))
                .isNotEmpty();

        AuthenticatedMapContract.Command failingBatch =
                AuthenticatedMapContract.Command.batch(List.of(
                        AuthenticatedMapContract.Mutation.put(
                                "governed-products", bytes("must-not-write"), bytes("x")),
                        AuthenticatedMapContract.Mutation.compareAndSet(
                                "governed-products", bytes("absent"), bytes("y"),
                                1, new byte[0])));
        byte[] unusedAuthorizationId = repeated(0x74);
        AppMessage atomicFailure = directMapMessage(genesis, fixture.actorB(),
                ACTOR_SEED_B, revisionTwo, unusedAuthorizationId, 26,
                failingBatch, 6, 15);
        execute(machine, block(7, atomicFailure), state);
        state.committedHeight = 7;
        assertReceiptError(state, atomicFailure, AuthenticatedMapContract.ERROR_ABSENT);
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "governed-products", bytes("must-not-write"))))).isEmpty();
        assertThat(machine.query(AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                new DirectConsumptionQueryV1(
                        fixture.actorB().actorId(), unusedAuthorizationId).encode(), state))
                .isEmpty();

        ActorRecordV1 suspendedB = new ActorRecordV1(
                fixture.actorB().actorId(), fixture.actorB().organizationId(), 2,
                RecordStatus.SUSPENDED, fixture.actorB().roles(),
                fixture.actorB().keys(), new byte[0]);
        byte[] actorMutation = new com.bloxbean.cardano.yano.appchain.roles.contracts
                .RegistryMutationV1.PutActor(suspendedB, List.of()).encode();
        GovernanceSubject actorSubject = new GovernanceSubject(
                "suspend-auditor-b", actorMutation, 10, 20);
        AppMessage proposeActor = governanceMessage(
                DomainActorRegistryComponent.TOPIC, 27,
                ActorGovernanceCommandV1.Operation.PROPOSE, actorSubject,
                List.of(signedAdministrator(fixture, genesis, actorSubject,
                        AdministratorStatementV1.Decision.PROPOSE,
                        fixture.actorA(), ACTOR_SEED, 8)));
        execute(machine, block(8, proposeActor), state);
        state.committedHeight = 8;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/domain-actors/command-result",
                proposeActor.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        RolePendingQueriesV1.GovernancePage pendingActor =
                RolePendingQueriesV1.GovernancePage.decode(machine.query(
                        "components/domain-actors/pending-governance",
                        new RolePendingQueriesV1.PageQuery("", 10).encode(), state));
        assertThat(pendingActor.entries()).extracting(
                        RolePendingQueriesV1.GovernanceEntry::mutationId)
                .containsExactly(actorSubject.id());

        AppMessage approveActor = governanceMessage(
                DomainActorRegistryComponent.TOPIC, 28,
                ActorGovernanceCommandV1.Operation.APPROVE, actorSubject,
                List.of(signedAdministrator(fixture, genesis, actorSubject,
                        AdministratorStatementV1.Decision.APPROVE,
                        fixture.actorC(), ACTOR_SEED_C, 9)));
        execute(machine, block(9, approveActor), state);
        state.committedHeight = 9;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/domain-actors/command-result",
                approveActor.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        AppMessage staleActorAction = directMapMessage(genesis, fixture.actorB(),
                ACTOR_SEED_B, revisionTwo, repeated(0x75), 29,
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put(
                                "governed-products", bytes("stale-actor"), bytes("no"))),
                9, 18);
        AppMessage activateActor = governanceMessage(
                DomainActorRegistryComponent.TOPIC, 30,
                ActorGovernanceCommandV1.Operation.ACTIVATE, actorSubject, List.of());
        execute(machine, block(10, staleActorAction, activateActor), state);
        state.committedHeight = 10;
        assertReceiptError(state, staleActorAction,
                AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE);
        assertThat(state.get(physical(DomainActorRegistryComponent.COMPONENT_ID,
                RoleWorkflowKeys.actorCurrent(fixture.actorB().actorId()))))
                .contains(ByteBuffer.allocate(Long.BYTES).putLong(2).array());
        assertThat(machine.query(
                "components/domain-actors/administrator-authority",
                bytes(fixture.authority().authorityId()), state))
                .isEqualTo(fixture.authority().encode());
        assertThat(machine.query("components/domain-actors/governance-mutation",
                bytes(actorSubject.id()), state)).isNotEmpty();
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/domain-actors/command-result",
                activateActor.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(RolePendingQueriesV1.GovernancePage.decode(machine.query(
                "components/domain-actors/pending-governance",
                new RolePendingQueriesV1.PageQuery("", 10).encode(), state))
                .entries()).isEmpty();

        ActorRecordV1 suspendedAdministrator = new ActorRecordV1(
                fixture.actorA().actorId(), fixture.actorA().organizationId(), 2,
                RecordStatus.SUSPENDED, fixture.actorA().roles(),
                fixture.actorA().keys(), new byte[0]);
        GovernanceSubject unsafeSubject = new GovernanceSubject(
                "unsafe-admin-suspension",
                new com.bloxbean.cardano.yano.appchain.roles.contracts
                        .RegistryMutationV1.PutActor(
                        suspendedAdministrator, List.of()).encode(), 12, 20);
        execute(machine, block(11, governanceMessage(
                DomainActorRegistryComponent.TOPIC, 31,
                ActorGovernanceCommandV1.Operation.PROPOSE, unsafeSubject,
                List.of(signedAdministrator(fixture, genesis, unsafeSubject,
                        AdministratorStatementV1.Decision.PROPOSE,
                        fixture.actorA(), ACTOR_SEED, 11)))), state);
        state.committedHeight = 11;
        assertThat(machine.query("components/domain-actors/governance-mutation",
                bytes(unsafeSubject.id()), state)).isEmpty();
        assertThat(machine.query("components/domain-actors/actor",
                bytes(fixture.actorA().actorId()), state))
                .isEqualTo(fixture.actorA().encode());
    }

    @Test
    void terminalApprovalExecutesOnceAndMayBeReachedEarlierInSameBlock() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture fixture = thresholdFixture();
        AuthenticatedMapContract.Genesis basic = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(0x16), 16, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "placeholder", AuthenticatedMapContract.AUTH_OPEN,
                        false, 64, 4_096)), List.of());
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                basic.chainId(), basic.commitmentProfileId(), basic.formatFingerprint(),
                basic.frameworkConsensusProfileDigest(), basic.membershipCommitment(),
                basic.anchorPolicyCommitment(), basic.maxBatchItems(),
                basic.maxBatchBytes(), fixture.collections(), basic.validators(),
                basic.initialEntries(), fixture.genesis());
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "releases", bytes("release-1"), bytes("approved")));
        MapActionV1 action = new MapActionV1(false, mutation.mutations(),
                List.of(new AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_APPROVAL,
                        fixture.approvalPolicy().policyId(), 1)));
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        byte[] proposalPayload = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                AuthenticatedMapContract.genesisId(genesis), commitment);
        byte[] otherGenesisPayload =
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                        repeated(0x7f), commitment);
        String proposalId = "release-approval-1";
        String otherGenesisProposalId = "other-genesis-approval";
        long deadline = 15;
        AppMessage propose = signedActorMessage(40,
                new ActorStatementV1(ActorStatementV1.Action.PROPOSE,
                        CHAIN_ID, proposalId, fixture.approvalPolicy().policyId(),
                        fixture.approvalPolicy().revision(),
                        AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                        proposalPayload, deadline, fixture.actorA().actorId(),
                        fixture.actorA().revision(),
                        fixture.actorA().keys().getFirst().keyId(), ""), ACTOR_SEED);
        AppMessage proposeForOtherGenesis = signedActorMessage(44,
                new ActorStatementV1(ActorStatementV1.Action.PROPOSE,
                        CHAIN_ID, otherGenesisProposalId,
                        fixture.approvalPolicy().policyId(),
                        fixture.approvalPolicy().revision(),
                        AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                        otherGenesisPayload, deadline, fixture.actorA().actorId(),
                        fixture.actorA().revision(),
                        fixture.actorA().keys().getFirst().keyId(), ""), ACTOR_SEED);
        execute(machine, block(2, propose, proposeForOtherGenesis), state);
        state.committedHeight = 2;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                propose.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);

        ApprovalPolicyV1 successorPolicy = new ApprovalPolicyV1(
                fixture.approvalPolicy().policyId(), 2, RecordStatus.SUSPENDED,
                List.of("auditor"), List.of(new ApprovalPolicyV1.RequiredClause(
                "auditor", "auditor", 1,
                ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.DISABLED, 20);
        state.put(physical(RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.policyRevision(successorPolicy.policyId(), 2)),
                successorPolicy.encode());
        state.put(physical(RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleWorkflowKeys.policyCurrent(successorPolicy.policyId())),
                ByteBuffer.allocate(Long.BYTES).putLong(2).array());

        MapApprovalReferenceV1 reference = new MapApprovalReferenceV1(
                proposalId, commitment, List.of(0),
                fixture.approvalPolicy().policyId(),
                fixture.approvalPolicy().revision());
        AppMessage mapCommand = message(AuthenticatedMapContract.DEFAULT_TOPIC, 41,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(action, List.of(reference))));
        AppMessage approval = signedActorMessage(42,
                new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                        CHAIN_ID, proposalId, fixture.approvalPolicy().policyId(),
                        fixture.approvalPolicy().revision(),
                        AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                        proposalPayload, deadline, fixture.actorA().actorId(),
                        fixture.actorA().revision(),
                        fixture.actorA().keys().getFirst().keyId(), "issuer"),
                ACTOR_SEED);
        AppMessage approvalForOtherGenesis = signedActorMessage(45,
                new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                        CHAIN_ID, otherGenesisProposalId,
                        fixture.approvalPolicy().policyId(),
                        fixture.approvalPolicy().revision(),
                        AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                        otherGenesisPayload, deadline, fixture.actorA().actorId(),
                        fixture.actorA().revision(),
                        fixture.actorA().keys().getFirst().keyId(), "issuer"),
                ACTOR_SEED);
        execute(machine, block(3, mapCommand, approval, approvalForOtherGenesis), state);
        state.committedHeight = 3;

        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "releases", bytes("release-1"))))).isPresent();
        ApprovalConsumptionV1 consumption = ApprovalConsumptionV1.decode(
                machine.query(AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                        bytes(proposalId), state));
        assertThat(consumption.proposalId()).isEqualTo(proposalId);
        assertThat(consumption.actionCommitment()).isEqualTo(commitment);
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                approval.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(com.bloxbean.cardano.yano.appchain.roles.contracts
                .ApprovalProposalV1.decode(machine.query(
                        "components/role-approvals/proposal",
                        bytes(proposalId), state)).status())
                .isEqualTo(com.bloxbean.cardano.yano.appchain.roles.contracts
                        .ApprovalProposalV1.ProposalStatus.APPROVED);

        AppMessage replay = message(AuthenticatedMapContract.DEFAULT_TOPIC, 43,
                mapCommand.getBody());
        MapApprovalReferenceV1 otherGenesisReference = new MapApprovalReferenceV1(
                otherGenesisProposalId, commitment, List.of(0),
                fixture.approvalPolicy().policyId(),
                fixture.approvalPolicy().revision());
        AppMessage otherGenesisExecution = message(
                AuthenticatedMapContract.DEFAULT_TOPIC, 46,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(
                                action, List.of(otherGenesisReference))));
        execute(machine, block(4, otherGenesisExecution, replay), state);
        state.committedHeight = 4;
        assertReceiptError(state, otherGenesisExecution,
                AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH);
        assertThat(machine.query(
                AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                bytes(otherGenesisProposalId), state)).isEmpty();
        assertReceiptError(state, replay, AuthenticatedMapContract.ERROR_APPROVAL_REPLAY);
        AuthenticatedMapContract.Entry retained = state.get(physical(
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.canonicalKey(
                                "releases", bytes("release-1"))))
                .map(AuthenticatedMapContract::decodeEntry).orElseThrow();
        assertThat(retained.revision()).isEqualTo(1);
    }

    @Test
    void hostileCryptoWorkloadIsFencedByTheDeterministicPerBlockCap() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture base = thresholdFixture();
        GovernedGenesisV1 governed = new GovernedGenesisV1(
                base.genesis().chainId(), base.authority(),
                base.genesis().organizations(), base.genesis().actors(),
                base.genesis().directPolicies(), base.genesis().approvalPolicies(),
                constrainedCryptoLimits());
        ThresholdFixture fixture = new ThresholdFixture(
                governed, base.actorA(), base.actorB(), base.actorC(),
                base.authority(), base.directPolicy(), base.approvalPolicy(),
                base.collections());
        AuthenticatedMapContract.Genesis genesis = governedMapGenesis(
                config, fixture, repeated(0x18));
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        AppMessage first = directCryptoMessage(70, fixture, genesis,
                repeated(0x61), "crypto-1");
        AppMessage second = directCryptoMessage(71, fixture, genesis,
                repeated(0x62), "crypto-2");
        execute(machine, block(2, first, second), state);
        state.committedHeight = 2;

        byte[] applied = state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.receiptKey(first.getMessageId())))
                .orElseThrow();
        assertThat(AuthenticatedMapContract.decodeReceipt(applied).status())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
        assertReceiptError(state, second,
                AuthenticatedMapContract.ERROR_CRYPTO_WORK_EXCEEDED);
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "governed-products", bytes("crypto-2"))))).isEmpty();

        // The fence is a deterministic per-block reservation, not a lockout:
        // the same evidence budget reopens at the next height.
        AppMessage reopened = directCryptoMessage(72, fixture, genesis,
                repeated(0x63), "crypto-3");
        execute(machine, block(3, reopened), state);
        state.committedHeight = 3;
        byte[] recovered = state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.receiptKey(reopened.getMessageId())))
                .orElseThrow();
        assertThat(AuthenticatedMapContract.decodeReceipt(recovered).status())
                .isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
    }

    private static AppMessage directCryptoMessage(
            int discriminator,
            ThresholdFixture fixture,
            AuthenticatedMapContract.Genesis genesis,
            byte[] authorizationId,
            String key
    ) {
        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "governed-products", bytes(key), bytes("metadata")));
        MapActionV1 action = new MapActionV1(false, mutation.mutations(),
                List.of(new AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        fixture.directPolicy().policyId(), 1)));
        MapActorAuthorizationV1 authorization = MapActorAuthorizationV1.sign(
                authorizationId, CHAIN_ID, AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action),
                List.of(0), fixture.directPolicy().policyId(), 1,
                fixture.actorA().actorId(), 1,
                fixture.actorA().keys().getFirst().keyId(),
                fixture.actorA().keys().getFirst().publicKey(),
                1, 20, ACTOR_SEED);
        return message(AuthenticatedMapContract.DEFAULT_TOPIC, discriminator,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(action, List.of(authorization))));
    }

    private static GovernedAuthorizationLimitsV1 constrainedCryptoLimits() {
        GovernedAuthorizationLimitsV1 defaults = GovernedAuthorizationLimitsV1.defaults();
        return new GovernedAuthorizationLimitsV1(
                defaults.maximumEvidenceItemsPerCommand(),
                defaults.maximumCoveredIndexesPerEvidence(),
                defaults.maximumGenesisOrganizations(),
                defaults.maximumGenesisActors(),
                defaults.maximumGenesisKeys(),
                defaults.maximumGenesisPolicies(),
                defaults.maximumGenesisRecordBytes(),
                defaults.maximumPendingGovernance(),
                defaults.maximumPendingApprovals(),
                defaults.maximumPendingPerActor(),
                defaults.maximumPendingPerPolicy(),
                defaults.maximumPendingPerAuthority(),
                defaults.maximumPendingPerDeadline(),
                defaults.maximumExpiryWorkPerBlock(),
                defaults.maximumAuthoritySupersessionWork(),
                defaults.maximumQueryPageSize(),
                1);
    }

    @Test
    void perDeadlineBucketBoundSaturatesIndependentlyOfGlobalCapacity() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture base = thresholdFixture();
        GovernedGenesisV1 governed = new GovernedGenesisV1(
                base.genesis().chainId(), base.authority(),
                base.genesis().organizations(), base.genesis().actors(),
                base.genesis().directPolicies(), base.genesis().approvalPolicies(),
                new GovernedAuthorizationLimitsV1(
                        RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS,
                        RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES,
                        RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS,
                        RoleWorkflowLimits.MAX_GENESIS_ACTORS,
                        RoleWorkflowLimits.MAX_GENESIS_KEYS,
                        RoleWorkflowLimits.MAX_GENESIS_POLICIES,
                        RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES,
                        2, 4, 2, 4, 2, 1, 2, 2, 10,
                        RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK));
        ThresholdFixture fixture = new ThresholdFixture(
                governed, base.actorA(), base.actorB(), base.actorC(),
                base.authority(), base.directPolicy(), base.approvalPolicy(),
                base.collections());
        AuthenticatedMapContract.Genesis genesis = governedMapGenesis(
                config, fixture, repeated(0x19));
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        AppMessage first = approvalMessage(80, fixture, genesis, "bucket-a",
                repeated(0x54), 10, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        AppMessage sameBucket = approvalMessage(81, fixture, genesis, "bucket-b",
                repeated(0x55), 10, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        AppMessage otherBucket = approvalMessage(82, fixture, genesis, "bucket-c",
                repeated(0x56), 11, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        execute(machine, block(2, first, sameBucket, otherBucket), state);
        state.committedHeight = 2;

        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                first.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        // The second proposal shares deadline height 10: its bucket is full
        // even though global and per-policy capacity remain open.
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                sameBucket.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.CAPACITY_EXCEEDED);
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                otherBucket.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
    }

    @Test
    void approvalCapacityReturnsAResultAndReopensAfterAutomaticExpiry() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture base = thresholdFixture();
        GovernedGenesisV1 governed = new GovernedGenesisV1(
                base.genesis().chainId(), base.authority(),
                base.genesis().organizations(), base.genesis().actors(),
                base.genesis().directPolicies(), base.genesis().approvalPolicies(),
                constrainedApprovalLimits());
        ThresholdFixture fixture = new ThresholdFixture(
                governed, base.actorA(), base.actorB(), base.actorC(),
                base.authority(), base.directPolicy(), base.approvalPolicy(),
                base.collections());
        AuthenticatedMapContract.Genesis genesis = governedMapGenesis(
                config, fixture, repeated(0x17));
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        AppMessage expiring = approvalMessage(50, fixture, genesis, "expiring",
                repeated(0x51), 3, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        execute(machine, block(2, expiring), state);
        state.committedHeight = 2;

        AppMessage saturated = approvalMessage(51, fixture, genesis, "saturated",
                repeated(0x52), 10, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        execute(machine, block(3, saturated), state);
        state.committedHeight = 3;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                saturated.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.CAPACITY_EXCEEDED);
        RolePendingQueriesV1.ApprovalPage full = RolePendingQueriesV1.ApprovalPage.decode(
                machine.query("components/role-approvals/pending-approvals",
                        new RolePendingQueriesV1.PageQuery("", 10).encode(), state));
        assertThat(full.entries()).extracting(
                        RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("expiring");
        assertThatThrownBy(() -> machine.query(
                "components/role-approvals/pending-approvals",
                new RolePendingQueriesV1.PageQuery("", 11).encode(), state))
                .isInstanceOf(AppQueryException.class)
                .extracting(failure -> ((AppQueryException) failure).code())
                .isEqualTo(AppQueryException.Code.INVALID_REQUEST);

        AppMessage reopened = approvalMessage(52, fixture, genesis, "reopened",
                repeated(0x53), 10, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        execute(machine, block(4), state);
        state.committedHeight = 4;
        assertThat(ApprovalProposalV1.decode(machine.query(
                "components/role-approvals/proposal", bytes("expiring"), state)).status())
                .isEqualTo(ApprovalProposalV1.ProposalStatus.EXPIRED);
        RolePendingQueriesV1.ApprovalPage emptyAfterExpiry =
                RolePendingQueriesV1.ApprovalPage.decode(machine.query(
                        "components/role-approvals/pending-approvals",
                        new RolePendingQueriesV1.PageQuery("", 10).encode(), state));
        assertThat(emptyAfterExpiry.entries()).isEmpty();

        execute(machine, block(5, reopened), state);
        state.committedHeight = 5;
        assertThat(RoleCommandResultV1.decode(machine.query(
                "components/role-approvals/command-result",
                reopened.getMessageId(), state)).resultCode())
                .isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        RolePendingQueriesV1.ApprovalPage afterExpiry =
                RolePendingQueriesV1.ApprovalPage.decode(machine.query(
                        "components/role-approvals/pending-approvals",
                        new RolePendingQueriesV1.PageQuery("", 10).encode(), state));
        assertThat(afterExpiry.entries()).extracting(
                        RolePendingQueriesV1.ApprovalEntry::proposalId)
                .containsExactly("reopened");
        assertThat(RoleApprovalStatsV1.decode(machine.query(
                "components/role-approvals/stats", new byte[0], state)))
                .isEqualTo(new RoleApprovalStatsV1(2, 1, 0, 0, 0, 1));

        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
    }

    @Test
    void failedBatchConsumesNoneOfMultipleApprovedReferences() {
        AppChainConfig config = config(CHAIN_ID);
        ThresholdFixture fixture = thresholdFixture();
        AuthenticatedMapContract.Genesis genesis = governedMapGenesis(
                config, fixture, repeated(0x18));
        CompositeStateMachine machine = AuthenticatedMapPreset.create(
                context(config), genesis);
        MemoryState state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
        execute(machine, block(1), state);
        state.committedHeight = 1;

        AuthenticatedMapContract.Command mutations = AuthenticatedMapContract.Command.batch(
                List.of(AuthenticatedMapContract.Mutation.put(
                                "releases", bytes("will-rollback"), bytes("first")),
                        AuthenticatedMapContract.Mutation.compareAndSet(
                                "releases", bytes("absent"), bytes("second"),
                                1, null)));
        MapActionV1 action = new MapActionV1(true, mutations.mutations(), List.of(
                new AuthorizationAssignmentV1(0, AuthenticatedMapContract.AUTH_APPROVAL,
                        fixture.approvalPolicy().policyId(), 1),
                new AuthorizationAssignmentV1(1, AuthenticatedMapContract.AUTH_APPROVAL,
                        fixture.approvalPolicy().policyId(), 2)));
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        long deadline = 20;
        AppMessage proposeA = approvalMessage(60, fixture, genesis,
                "approval-a", commitment, deadline, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        AppMessage proposeB = approvalMessage(61, fixture, genesis,
                "approval-b", commitment, deadline, ActorStatementV1.Action.PROPOSE,
                fixture.actorA(), ACTOR_SEED, "");
        execute(machine, block(2, proposeA, proposeB), state);
        state.committedHeight = 2;

        AppMessage approveA = approvalMessage(62, fixture, genesis,
                "approval-a", commitment, deadline, ActorStatementV1.Action.APPROVE,
                fixture.actorA(), ACTOR_SEED, "issuer");
        AppMessage approveB = approvalMessage(63, fixture, genesis,
                "approval-b", commitment, deadline, ActorStatementV1.Action.APPROVE,
                fixture.actorA(), ACTOR_SEED, "issuer");
        execute(machine, block(3, approveA, approveB), state);
        state.committedHeight = 3;

        MapApprovalReferenceV1 referenceA = new MapApprovalReferenceV1(
                "approval-a", commitment, List.of(0),
                fixture.approvalPolicy().policyId(), 1);
        MapApprovalReferenceV1 referenceB = new MapApprovalReferenceV1(
                "approval-b", commitment, List.of(1),
                fixture.approvalPolicy().policyId(), 1);
        AppMessage map = message(AuthenticatedMapContract.DEFAULT_TOPIC, 64,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(
                                action, List.of(referenceA, referenceB))));
        execute(machine, block(4, map), state);
        state.committedHeight = 4;

        assertReceiptError(state, map, AuthenticatedMapContract.ERROR_ABSENT);
        assertThat(state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                AuthenticatedMapContract.canonicalKey(
                        "releases", bytes("will-rollback"))))).isEmpty();
        assertThat(machine.query(AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                bytes("approval-a"), state)).isEmpty();
        assertThat(machine.query(AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                bytes("approval-b"), state)).isEmpty();
        machine.init(state, new AppChainInfo(CHAIN_ID, hex(MEMBER), 1));
    }

    private static GovernedFixture governedFixture() {
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(ACTOR_SEED);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "admin-a-key", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                CHAIN_ID, "admin-a", 1, key, ACTOR_SEED);
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "operator-a", 1, RecordStatus.ACTIVE, new byte[0]);
        ActorRecordV1 actor = new ActorRecordV1(
                "admin-a", "operator-a", 1, RecordStatus.ACTIVE,
                List.of("issuer", "registry-admin"), List.of(key), new byte[0]);
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of("admin-a"), 1, 1_000);
        DirectRolePolicyV1 direct = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        ApprovalPolicyV1 approval = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "issuer", "issuer", 1, ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 1_000);
        GovernedGenesisV1 genesis = new GovernedGenesisV1(
                CHAIN_ID, authority, List.of(organization),
                List.of(new GenesisActorV1(actor, List.of(proof))),
                List.of(direct), List.of(approval),
                GovernedAuthorizationLimitsV1.defaults());
        List<AuthenticatedMapContract.CollectionDescriptor> collections = List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        "governed-products", AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        "issuer-write", false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""),
                new AuthenticatedMapContract.CollectionDescriptor(
                        "releases", AuthenticatedMapContract.AUTH_APPROVAL,
                        "release-policy", false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""));
        return new GovernedFixture(genesis, organization, actor, authority,
                direct, approval, collections);
    }

    private static ThresholdFixture thresholdFixture() {
        OrganizationRecordV1 organizationA = new OrganizationRecordV1(
                "operator-a", 1, RecordStatus.ACTIVE, new byte[0]);
        OrganizationRecordV1 organizationB = new OrganizationRecordV1(
                "operator-b", 1, RecordStatus.ACTIVE, new byte[0]);
        OrganizationRecordV1 organizationC = new OrganizationRecordV1(
                "operator-c", 1, RecordStatus.ACTIVE, new byte[0]);
        ActorRecordV1 actorA = actor("admin-a", organizationA.organizationId(),
                "admin-a-key", ACTOR_SEED,
                List.of("auditor", "issuer", "registry-admin"));
        ActorRecordV1 actorB = actor("auditor-b", organizationB.organizationId(),
                "auditor-b-key", ACTOR_SEED_B, List.of("auditor"));
        ActorRecordV1 actorC = actor("admin-c", organizationC.organizationId(),
                "admin-c-key", ACTOR_SEED_C, List.of("registry-admin"));
        AdministratorAuthorityV1 authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of(actorA.actorId(), actorC.actorId()),
                2, 100);
        DirectRolePolicyV1 direct = new DirectRolePolicyV1(
                "issuer-write", 1, RecordStatus.ACTIVE, "issuer", 100);
        ApprovalPolicyV1 approval = new ApprovalPolicyV1(
                "release-policy", 1, RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        "issuer", "issuer", 1,
                        ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        GovernedGenesisV1 governed = new GovernedGenesisV1(
                CHAIN_ID, authority,
                List.of(organizationA, organizationB, organizationC),
                List.of(genesisActor(actorA, ACTOR_SEED),
                        genesisActor(actorB, ACTOR_SEED_B),
                        genesisActor(actorC, ACTOR_SEED_C)),
                List.of(direct), List.of(approval),
                GovernedAuthorizationLimitsV1.defaults());
        List<AuthenticatedMapContract.CollectionDescriptor> collections = List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        "governed-products",
                        AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        direct.policyId(), false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""),
                new AuthenticatedMapContract.CollectionDescriptor(
                        "releases", AuthenticatedMapContract.AUTH_APPROVAL,
                        approval.policyId(), false, 64, 4_096,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""));
        return new ThresholdFixture(governed, actorA, actorB, actorC,
                authority, direct, approval, collections);
    }

    private static AuthenticatedMapContract.Genesis governedMapGenesis(
            AppChainConfig config,
            ThresholdFixture fixture,
            byte[] formatFingerprint
    ) {
        AuthenticatedMapContract.Genesis basic = AuthenticatedMapGenesisFactory.mpf(
                config, formatFingerprint, 16, 32_768,
                List.of(new AuthenticatedMapContract.CollectionDescriptor(
                        "placeholder", AuthenticatedMapContract.AUTH_OPEN,
                        false, 64, 4_096)), List.of());
        return new AuthenticatedMapContract.Genesis(
                basic.chainId(), basic.commitmentProfileId(), basic.formatFingerprint(),
                basic.frameworkConsensusProfileDigest(), basic.membershipCommitment(),
                basic.anchorPolicyCommitment(), basic.maxBatchItems(),
                basic.maxBatchBytes(), fixture.collections(), basic.validators(),
                basic.initialEntries(), fixture.genesis());
    }

    private static GovernedAuthorizationLimitsV1 constrainedApprovalLimits() {
        return new GovernedAuthorizationLimitsV1(
                RoleWorkflowLimits.MAX_AUTHORIZATION_EVIDENCE_ITEMS,
                RoleWorkflowLimits.MAX_COVERED_MUTATION_INDEXES,
                RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS,
                RoleWorkflowLimits.MAX_GENESIS_ACTORS,
                RoleWorkflowLimits.MAX_GENESIS_KEYS,
                RoleWorkflowLimits.MAX_GENESIS_POLICIES,
                RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES,
                2, 1, 1, 1, 2, 1, 1, 2, 10,
                RoleWorkflowLimits.MAX_CRYPTO_WORK_UNITS_PER_BLOCK);
    }

    private static ActorRecordV1 actor(
            String actorId,
            String organizationId,
            String keyId,
            byte[] seed,
            List<String> roles
    ) {
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                keyId, KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                1, 0, RecordStatus.ACTIVE);
        return new ActorRecordV1(actorId, organizationId, 1,
                RecordStatus.ACTIVE, roles, List.of(key), new byte[0]);
    }

    private static GenesisActorV1 genesisActor(ActorRecordV1 actor, byte[] seed) {
        return new GenesisActorV1(actor, List.of(ActorKeyProofV1.sign(
                CHAIN_ID, actor.actorId(), actor.revision(),
                actor.keys().getFirst(), seed)));
    }

    private static SignedAdministratorStatementV1 signedAdministrator(
            ThresholdFixture fixture,
            AuthenticatedMapContract.Genesis mapGenesis,
            GovernanceSubject subject,
            AdministratorStatementV1.Decision decision,
            ActorRecordV1 actor,
            byte[] seed,
            long issuedHeight
    ) {
        AdministratorStatementV1 statement = new AdministratorStatementV1(
                decision, CHAIN_ID, AuthenticatedMapContract.genesisId(mapGenesis),
                fixture.authority().authorityId(), fixture.authority().revision(),
                subject.id(), ActorGovernanceCommandV1.mutationHash(subject.mutation()),
                subject.notBeforeHeight(), subject.expiryHeight(), actor.actorId(),
                actor.revision(), actor.keys().getFirst().keyId(),
                actor.keys().getFirst().publicKey(), issuedHeight,
                subject.expiryHeight(), AdministratorStatementV1.ED25519);
        return SignedAdministratorStatementV1.sign(statement, seed);
    }

    private static AppMessage governanceMessage(
            String topic,
            int discriminator,
            ActorGovernanceCommandV1.Operation operation,
            GovernanceSubject subject,
            List<SignedAdministratorStatementV1> authorizations
    ) {
        ActorGovernanceCommandV1 command = new ActorGovernanceCommandV1(
                operation, subject.id(),
                operation == ActorGovernanceCommandV1.Operation.PROPOSE
                        ? subject.mutation() : new byte[0], authorizations);
        return message(topic, discriminator, command.encode());
    }

    private static AppMessage signedActorMessage(
            int discriminator,
            ActorStatementV1 statement,
            byte[] seed
    ) {
        return message(GovernedRoleApprovalWorkflow.TOPIC, discriminator,
                SignedActorCommandV1.sign(statement, seed).encode());
    }

    private static AppMessage approvalMessage(
            int discriminator,
            ThresholdFixture fixture,
            AuthenticatedMapContract.Genesis genesis,
            String proposalId,
            byte[] actionCommitment,
            long deadline,
            ActorStatementV1.Action action,
            ActorRecordV1 actor,
            byte[] seed,
            String clauseId
    ) {
        return signedActorMessage(discriminator, new ActorStatementV1(
                action, CHAIN_ID, proposalId, fixture.approvalPolicy().policyId(),
                fixture.approvalPolicy().revision(),
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                        AuthenticatedMapContract.genesisId(genesis), actionCommitment),
                deadline, actor.actorId(), actor.revision(),
                actor.keys().getFirst().keyId(), clauseId), seed);
    }

    private static AppMessage directMapMessage(
            AuthenticatedMapContract.Genesis genesis,
            ActorRecordV1 actor,
            byte[] seed,
            DirectRolePolicyV1 policy,
            byte[] authorizationId,
            int discriminator,
            AuthenticatedMapContract.Command command,
            long issuedHeight,
            long deadlineHeight
    ) {
        List<AuthorizationAssignmentV1> assignments = java.util.stream.IntStream
                .range(0, command.mutations().size())
                .mapToObj(index -> new AuthorizationAssignmentV1(
                        index, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        policy.policyId(), 1))
                .toList();
        MapActionV1 action = new MapActionV1(
                command.batch(), command.mutations(), assignments);
        List<Integer> coverage = java.util.stream.IntStream
                .range(0, command.mutations().size()).boxed().toList();
        MapActorAuthorizationV1 authorization = MapActorAuthorizationV1.sign(
                authorizationId, CHAIN_ID,
                AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action),
                coverage, policy.policyId(), policy.revision(), actor.actorId(),
                actor.revision(), actor.keys().getFirst().keyId(),
                actor.keys().getFirst().publicKey(), issuedHeight,
                deadlineHeight, seed);
        return message(AuthenticatedMapContract.DEFAULT_TOPIC, discriminator,
                AuthenticatedMapAuthorizationContract.encodeCommand(
                        new AuthenticatedMapCommandV1(action,
                                List.of(authorization))));
    }

    private static AppChainConfig config(String chainId) {
        return AppChainConfig.builder(chainId)
                .signingKeyHex(hex(repeated(0x21)))
                .memberKeysHex(Set.of(hex(MEMBER)))
                .proposerKeyHex(hex(MEMBER))
                .threshold(1)
                .stateMachineId(AuthenticatedMapContract.STATE_MACHINE_ID)
                .build();
    }

    private static AppStateMachineContext context(AppChainConfig config) {
        return context(config, null);
    }

    private static AppStateMachineContext context(
            AppChainConfig config,
            AuthenticatedMapValidatorResolver validatorResolver
    ) {
        AppChainMembershipEpoch membership = new AppChainMembershipEpoch(
                0, List.of(hex(MEMBER)), 1);
        return new AppStateMachineContext() {
            @Override
            public String chainId() {
                return config.chainId();
            }

            @Override
            public Map<String, String> settings() {
                return Map.of();
            }

            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
            consensusProfile() {
                return Optional.of(AppChainEffectsConfig.from(config)
                        .consensusProfile(config));
            }

            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView>
            membershipView() {
                return Optional.of(height -> membership);
            }

            @Override
            public Optional<AuthenticatedMapValidatorResolver>
            authenticatedMapValidatorResolver() {
                return Optional.ofNullable(validatorResolver);
            }
        };
    }

    private static byte[] finalCommand(
            AuthenticatedMapContract.Command command,
            int authorizationKind
    ) {
        return AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapCommandV1(
                        MapActionV1.basic(command, java.util.Collections.nCopies(
                                command.mutations().size(), authorizationKind)),
                        List.of()));
    }

    private static AppMessage message(String topic, int discriminator, byte[] body) {
        return AppMessage.builder()
                .messageId(repeated(discriminator))
                .chainId(CHAIN_ID)
                .topic(topic)
                .sender(MEMBER)
                .senderSeq(discriminator)
                .expiresAt(4_000_000_000L)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[64])
                .build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height,
                new byte[32], 0, new byte[0], 1_700_000_000_000L + height,
                new byte[32], new byte[32], List.of(messages), MEMBER,
                FinalityCert.empty());
    }

    private static void execute(
            CompositeStateMachine machine,
            AppBlock block,
            MemoryState state
    ) {
        machine.apply(
                AppBlockExecutionContext.fromValidatedBlock(block),
                state,
                AppEffectEmitter.rejecting("effects are not expected"));
    }

    private static byte[] physical(String componentId, byte[] localKey) {
        return CompositeStateKeys.componentKey(componentId, localKey);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hexBytes(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static void assertReceiptError(
            MemoryState state,
            AppMessage message,
            int errorCode
    ) {
        byte[] receipt = state.get(physical(AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.receiptKey(message.getMessageId())))
                .orElseThrow();
        assertThat(AuthenticatedMapContract.decodeReceipt(receipt).errorCode())
                .isEqualTo(errorCode);
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private record GovernedFixture(
            GovernedGenesisV1 genesis,
            OrganizationRecordV1 organization,
            ActorRecordV1 actor,
            AdministratorAuthorityV1 authority,
            DirectRolePolicyV1 directPolicy,
            ApprovalPolicyV1 approvalPolicy,
            List<AuthenticatedMapContract.CollectionDescriptor> collections
    ) {
    }

    private record ThresholdFixture(
            GovernedGenesisV1 genesis,
            ActorRecordV1 actorA,
            ActorRecordV1 actorB,
            ActorRecordV1 actorC,
            AdministratorAuthorityV1 authority,
            DirectRolePolicyV1 directPolicy,
            ApprovalPolicyV1 approvalPolicy,
            List<AuthenticatedMapContract.CollectionDescriptor> collections
    ) {
    }

    private record GovernanceSubject(
            String id,
            byte[] mutation,
            long notBeforeHeight,
            long expiryHeight
    ) {
        private GovernanceSubject {
            mutation = mutation.clone();
        }

        @Override
        public byte[] mutation() {
            return mutation.clone();
        }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();
        private long committedHeight;

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public byte[] stateRoot() {
            return repeated((int) committedHeight);
        }

        @Override
        public long committedHeight() {
            return committedHeight;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(new Key(key));
        }
    }

    private static final class Key {
        private final byte[] value;

        private Key(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}
