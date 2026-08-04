package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapStateMachineTest {
    private static final String CHAIN_ID = "authenticated-map-unit";
    private static final byte[] OWNER = repeated(0x11);
    private static final byte[] OTHER = repeated(0x22);

    @Test
    void ownerLifecycleUsesRevisionsTombstonesReceiptsAndQueries() {
        AuthenticatedMapStateMachine machine = machine(List.of(
                collection("products", AuthenticatedMapContract.AUTH_OWNER, true)));
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 2));

        AppMessage create = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "products", bytes("sku-1"), bytes("v1"))));
        apply(machine, state, 1, create);
        AuthenticatedMapContract.Entry first = entry(state, "products", "sku-1");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.controller()).isEqualTo(OWNER);
        assertThat(first.value()).isEqualTo(bytes("v1"));
        assertApplied(state, create, 1);

        AppMessage unauthorized = message(OTHER, 2, single(
                AuthenticatedMapContract.Mutation.put(
                        "products", bytes("sku-1"), bytes("bad"))));
        apply(machine, state, 2, unauthorized);
        assertThat(entry(state, "products", "sku-1").value()).isEqualTo(bytes("v1"));
        assertRejected(state, unauthorized, AuthenticatedMapContract.ERROR_UNAUTHORIZED);

        AppMessage wrongCas = message(OWNER, 3, single(
                AuthenticatedMapContract.Mutation.compareAndSet(
                        "products", bytes("sku-1"), bytes("v2"), 99, null)));
        apply(machine, state, 3, wrongCas);
        assertThat(entry(state, "products", "sku-1").revision()).isEqualTo(1);
        assertRejected(state, wrongCas, AuthenticatedMapContract.ERROR_PRECONDITION);

        AppMessage cas = message(OWNER, 4, single(
                AuthenticatedMapContract.Mutation.compareAndSet(
                        "products", bytes("sku-1"), bytes("v2"), 1,
                        first.logicalValueHash())));
        apply(machine, state, 4, cas);
        assertThat(entry(state, "products", "sku-1")).satisfies(updated -> {
            assertThat(updated.revision()).isEqualTo(2);
            assertThat(updated.value()).isEqualTo(bytes("v2"));
            assertThat(updated.lastMutationHeight()).isEqualTo(4);
        });

        AppMessage revoke = message(OWNER, 5, single(
                AuthenticatedMapContract.Mutation.revoke(
                        "products", bytes("sku-1"), 2, null)));
        apply(machine, state, 5, revoke);
        assertThat(entry(state, "products", "sku-1")).satisfies(revoked -> {
            assertThat(revoked.status()).isEqualTo(AuthenticatedMapContract.STATUS_REVOKED);
            assertThat(revoked.revision()).isEqualTo(3);
            assertThat(revoked.value()).isEmpty();
        });

        AppMessage recreate = message(OWNER, 6, single(
                AuthenticatedMapContract.Mutation.putIfAbsent(
                        "products", bytes("sku-1"), bytes("recreated"))));
        apply(machine, state, 6, recreate);
        assertRejected(state, recreate, AuthenticatedMapContract.ERROR_REVOKED);

        AppMessage restore = message(OWNER, 7, single(
                AuthenticatedMapContract.Mutation.restore(
                        "products", bytes("sku-1"), bytes("v3"))));
        apply(machine, state, 7, restore);
        assertThat(entry(state, "products", "sku-1")).satisfies(restored -> {
            assertThat(restored.status()).isEqualTo(AuthenticatedMapContract.STATUS_ACTIVE);
            assertThat(restored.revision()).isEqualTo(4);
            assertThat(restored.value()).isEqualTo(bytes("v3"));
        });

        AppMessage transfer = message(OWNER, 8, single(
                AuthenticatedMapContract.Mutation.transferController(
                        "products", bytes("sku-1"), OTHER, 4)));
        apply(machine, state, 8, transfer);
        assertThat(entry(state, "products", "sku-1")).satisfies(transferred -> {
            assertThat(transferred.revision()).isEqualTo(5);
            assertThat(transferred.controller()).isEqualTo(OTHER);
        });

        AppMessage formerOwner = message(OWNER, 9, single(
                AuthenticatedMapContract.Mutation.put(
                        "products", bytes("sku-1"), bytes("former-owner"))));
        apply(machine, state, 9, formerOwner);
        assertRejected(state, formerOwner, AuthenticatedMapContract.ERROR_UNAUTHORIZED);

        AppMessage newOwner = message(OTHER, 10, single(
                AuthenticatedMapContract.Mutation.put(
                        "products", bytes("sku-1"), bytes("v4"))));
        apply(machine, state, 10, newOwner);
        assertThat(entry(state, "products", "sku-1")).satisfies(updated -> {
            assertThat(updated.revision()).isEqualTo(6);
            assertThat(updated.value()).isEqualTo(bytes("v4"));
        });

        state.committedHeight = 10;
        AuthenticatedMapContract.PointResult point =
                AuthenticatedMapContract.decodePointResult(machine.query(
                        AuthenticatedMapContract.POINT_QUERY_PATH,
                        AuthenticatedMapContract.encodePointQuery(
                                AuthenticatedMapContract.PointQuery.current(
                                        "products", bytes("sku-1"))),
                        state));
        assertThat(point.presence()).isEqualTo(AuthenticatedMapContract.PRESENCE_ACTIVE);
        assertThat(point.entry().revision()).isEqualTo(6);

        AuthenticatedMapContract.ReceiptResult receipt =
                AuthenticatedMapContract.decodeReceiptResult(machine.query(
                        AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                        AuthenticatedMapContract.encodeReceiptQuery(
                                new AuthenticatedMapContract.ReceiptQuery(
                                        restore.getMessageId())),
                        state));
        assertThat(receipt.presence()).isEqualTo(AuthenticatedMapContract.RECEIPT_PRESENT);
        assertThat(receipt.receipt().status()).isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
    }

    @Test
    void boundedBatchIsAllOrNothingButPersistsRejectedReceipt() {
        AuthenticatedMapStateMachine machine = machine(List.of(
                collection("open", AuthenticatedMapContract.AUTH_OPEN, false)));
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 1));

        AuthenticatedMapContract.Command batch = AuthenticatedMapContract.Command.batch(List.of(
                AuthenticatedMapContract.Mutation.put(
                        "open", bytes("would-write"), bytes("value")),
                AuthenticatedMapContract.Mutation.compareAndSet(
                        "open", bytes("absent"), bytes("value"), 1, null)));
        AppMessage rejected = message(OWNER, 1, batch);
        apply(machine, state, 1, rejected);

        assertThat(state.get(AuthenticatedMapContract.canonicalKey(
                "open", bytes("would-write")))).isEmpty();
        assertThat(state.get(AuthenticatedMapContract.canonicalKey(
                "open", bytes("absent")))).isEmpty();
        assertRejected(state, rejected, AuthenticatedMapContract.ERROR_ABSENT);
    }

    @Test
    void canonicalValueEncodingRejectsDeterministicallyWhileOpaqueRemainsCompatible() {
        AuthenticatedMapStateMachine canonicalMachine = machine(List.of(
                canonicalCollection("records")));
        TestState canonicalState = new TestState();
        canonicalMachine.init(canonicalState, new AppChainInfo(CHAIN_ID, "", 1));

        AppMessage nonMinimal = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("bad"), hexBytes("1817"))));
        assertThat(canonicalMachine.validate(nonMinimal).isAccepted()).isFalse();
        apply(canonicalMachine, canonicalState, 1, nonMinimal);
        assertThat(canonicalState.get(AuthenticatedMapContract.canonicalKey(
                "records", bytes("bad")))).isEmpty();
        assertRejected(canonicalState, nonMinimal,
                AuthenticatedMapContract.ERROR_VALUE_ENCODING);

        AppMessage canonical = message(OWNER, 2, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("good"), hexBytes("a1616101"))));
        assertThat(canonicalMachine.validate(canonical).isAccepted()).isTrue();
        apply(canonicalMachine, canonicalState, 2, canonical);
        assertApplied(canonicalState, canonical, 2);

        AppMessage invalidBatch = message(OWNER, 3,
                AuthenticatedMapContract.Command.batch(List.of(
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("would-write"), hexBytes("01")),
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("unsorted"),
                                hexBytes("a2616202616101")))));
        apply(canonicalMachine, canonicalState, 3, invalidBatch);
        assertThat(canonicalState.get(AuthenticatedMapContract.canonicalKey(
                "records", bytes("would-write")))).isEmpty();
        assertRejected(canonicalState, invalidBatch,
                AuthenticatedMapContract.ERROR_VALUE_ENCODING);

        AuthenticatedMapStateMachine opaqueMachine = machine(List.of(
                collection("records", AuthenticatedMapContract.AUTH_OPEN, false)));
        TestState opaqueState = new TestState();
        opaqueMachine.init(opaqueState, new AppChainInfo(CHAIN_ID, "", 1));
        AppMessage opaque = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("opaque"), hexBytes("1817"))));
        assertThat(opaqueMachine.validate(opaque).isAccepted()).isTrue();
        apply(opaqueMachine, opaqueState, 1, opaque);
        assertApplied(opaqueState, opaque, 1);
    }

    @Test
    void declarativeSchemaRejectsWithTypedReceiptAndKeepsBatchesAtomic() {
        AuthenticatedMapSchema.Schema schema = quantitySchema(10);
        AuthenticatedMapContract.ValidatorDescriptor validator =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "product-schema", schema.definition());
        AuthenticatedMapStateMachine machine = machine(
                List.of(schemaCollection("records", validator.id())),
                List.of(validator));
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 1));

        AppMessage invalid = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("bad"), hexBytes("a1637174790b"))));
        assertThat(machine.validate(invalid).isAccepted()).isFalse();
        apply(machine, state, 1, invalid);
        assertRejected(state, invalid, AuthenticatedMapContract.ERROR_VALUE_SCHEMA);

        AppMessage valid = message(OWNER, 2, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("good"), hexBytes("a1637174790a"))));
        assertThat(machine.validate(valid).isAccepted()).isTrue();
        apply(machine, state, 2, valid);
        assertApplied(state, valid, 2);

        AppMessage invalidBatch = message(OWNER, 3,
                AuthenticatedMapContract.Command.batch(List.of(
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("would-write"),
                                hexBytes("a16371747901")),
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("bad-again"),
                                hexBytes("a1637174790b")))));
        apply(machine, state, 3, invalidBatch);
        assertThat(state.get(AuthenticatedMapContract.canonicalKey(
                "records", bytes("would-write")))).isEmpty();
        assertRejected(state, invalidBatch, AuthenticatedMapContract.ERROR_VALUE_SCHEMA);
    }

    @Test
    void pluginValidatorRejectsWithTypedReceiptAndKeepsBatchesAtomic() {
        AuthenticatedMapContract.ValidatorDescriptor validator = pluginDescriptor();
        AuthenticatedMapStateMachine machine = pluginMachine(validator,
                (collection, key, value) -> Arrays.equals(value, bytes("valid"))
                        ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT,
                List.of());
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 1));

        AppMessage invalid = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("bad"), bytes("invalid"))));
        assertThat(machine.validate(invalid).isAccepted()).isFalse();
        apply(machine, state, 1, invalid);
        assertRejected(state, invalid, AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);

        AppMessage valid = message(OWNER, 2, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("good"), bytes("valid"))));
        assertThat(machine.validate(valid).isAccepted()).isTrue();
        apply(machine, state, 2, valid);
        assertApplied(state, valid, 2);

        AppMessage invalidBatch = message(OWNER, 3,
                AuthenticatedMapContract.Command.batch(List.of(
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("would-write"), bytes("valid")),
                        AuthenticatedMapContract.Mutation.put(
                                "records", bytes("bad-again"), bytes("invalid")))));
        apply(machine, state, 3, invalidBatch);
        assertThat(state.get(AuthenticatedMapContract.canonicalKey(
                "records", bytes("would-write")))).isEmpty();
        assertRejected(state, invalidBatch,
                AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
    }

    @Test
    void unexpectedPluginFailureStopsValidationAndApplyWithoutAReceipt() {
        AuthenticatedMapContract.ValidatorDescriptor validator = pluginDescriptor();
        AuthenticatedMapStateMachine machine = pluginMachine(validator,
                (collection, key, value) -> {
                    throw new IllegalArgumentException("plugin-controlled detail");
                }, List.of());
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 1));
        AppMessage message = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "records", bytes("key"), bytes("value"))));

        assertThatThrownBy(() -> machine.validate(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authenticated-map validator execution failed")
                .hasMessageNotContaining("plugin-controlled detail");
        assertThatThrownBy(() -> apply(machine, state, 1, message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authenticated-map validator execution failed");
        assertThat(state.get(AuthenticatedMapContract.receiptKey(
                message.getMessageId()))).isEmpty();
    }

    @Test
    void pluginInitialEntriesAndResolverAvailabilityFailClosedAtStartup() {
        AuthenticatedMapContract.ValidatorDescriptor validator = pluginDescriptor();
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                CHAIN_ID, StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(),
                repeated(1), repeated(2), repeated(3), 16, 32_768,
                List.of(pluginCollection("records", validator.id())),
                List.of(validator),
                List.of(new AuthenticatedMapContract.GenesisEntry(
                        "records", bytes("initial"), new byte[0], bytes("invalid"))));

        assertThatThrownBy(() -> new AuthenticatedMapStateMachine(genesis))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a catalog resolver");
        assertThatThrownBy(() -> new AuthenticatedMapStateMachine(
                genesis, null, resolver(validator,
                (collection, key, value) -> ValidatorVerdict.REJECT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial entry violates");
    }

    @Test
    void memberPolicyUsesHeightVersionedMembership() {
        AppChainMembershipEpoch first = new AppChainMembershipEpoch(
                0, List.of(hex(OWNER)), 1);
        AppChainMembershipEpoch second = new AppChainMembershipEpoch(
                3, List.of(hex(OTHER)), 1);
        AuthenticatedMapContract.Genesis genesis = genesis(List.of(
                collection("members", AuthenticatedMapContract.AUTH_MEMBER, false)));
        AuthenticatedMapStateMachine machine = new AuthenticatedMapStateMachine(
                genesis, height -> height < 3 ? first : second);
        TestState state = new TestState();
        machine.init(state, new AppChainInfo(CHAIN_ID, "", 2));

        AppMessage firstWrite = message(OWNER, 1, single(
                AuthenticatedMapContract.Mutation.put(
                        "members", bytes("one"), bytes("v"))));
        apply(machine, state, 1, firstWrite);
        assertApplied(state, firstWrite, 1);

        AppMessage retired = message(OWNER, 2, single(
                AuthenticatedMapContract.Mutation.put(
                        "members", bytes("two"), bytes("v"))));
        assertThat(machine.validateForBlock(retired, 3, state).isAccepted()).isFalse();
        apply(machine, state, 3, retired);
        assertRejected(state, retired, AuthenticatedMapContract.ERROR_UNAUTHORIZED);

        AppMessage newMember = message(OTHER, 3, single(
                AuthenticatedMapContract.Mutation.put(
                        "members", bytes("three"), bytes("v"))));
        apply(machine, state, 4, newMember);
        assertApplied(state, newMember, 4);
    }

    @Test
    void realMpfSchemaReplayIsIdenticalAcrossMembersRestartSnapshotAndCatchUp() {
        String chainId = "conformance-chain";
        String member = "11".repeat(32);
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .maxBlockMessages(2)
                .stateMachineId(AuthenticatedMapStateMachine.ID)
                .build();
        AuthenticatedMapSchema.Schema replaySchema = AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.ArrayNode(List.of(
                        AuthenticatedMapSchema.Occurrence.required(
                                AuthenticatedMapSchema.IntegerNode.uint()),
                        AuthenticatedMapSchema.Occurrence.required(
                                AuthenticatedMapSchema.IntegerNode.uint()))));
        AuthenticatedMapContract.ValidatorDescriptor replayValidator =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "replay-schema", replaySchema.definition());
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(7), 16, 32_768,
                List.of(schemaCollection("records", replayValidator.id())),
                List.of(replayValidator),
                List.of());

        StateMachineConformance.builder(
                        new StdlibStateMachineProviders.AuthenticatedMapProvider())
                .settings(AuthenticatedMapGenesisFactory.settings(genesis))
                .chainId(chainId)
                .blocks(12)
                .messagesPerBlock(2)
                .runs(3)
                .restartAtHeight(5)
                .snapshotAtHeight(7)
                .messageGenerator((height, index, random) ->
                        new StateMachineConformance.CorpusMessage(
                                AuthenticatedMapContract.DEFAULT_TOPIC,
                                finalCommand(single(
                                        AuthenticatedMapContract.Mutation.put(
                                                "records",
                                                bytes("key-" + height + "-" + index),
                                                new byte[]{(byte) 0x82, (byte) height,
                                                        (byte) index})),
                                        AuthenticatedMapContract.AUTH_OPEN)))
                .stateProbe("first-record", CompositeStateKeys.componentKey(
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.canonicalKey(
                                "records", bytes("key-1-0"))))
                .assertDeterministic();
    }

    @Test
    void realClassicJmtReplayIsIdenticalAcrossMembersRestartSnapshotAndCatchUp() {
        String chainId = "classic-jmt-conformance-chain";
        String member = "11".repeat(32);
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .maxBlockMessages(2)
                .stateMachineId(AuthenticatedMapStateMachine.ID)
                .build();
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.classicJmt(
                config, repeated(7), 16, 32_768,
                List.of(collection("records", AuthenticatedMapContract.AUTH_OPEN, true)),
                List.of());

        StateMachineConformance.builder(
                        new StdlibStateMachineProviders.AuthenticatedMapProvider())
                .settings(AuthenticatedMapGenesisFactory.settings(genesis))
                .chainId(chainId)
                .blocks(16)
                .messagesPerBlock(2)
                .runs(3)
                .restartAtHeight(6)
                .snapshotAtHeight(9)
                .messageGenerator((height, index, random) ->
                        new StateMachineConformance.CorpusMessage(
                                AuthenticatedMapContract.DEFAULT_TOPIC,
                                finalCommand(single(
                                        AuthenticatedMapContract.Mutation.put(
                                                "records",
                                                bytes("key-" + height + "-" + index),
                                                bytes("value-" + random.nextInt(10_000)))),
                                        AuthenticatedMapContract.AUTH_OPEN)))
                .stateProbe("first-record", CompositeStateKeys.componentKey(
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.canonicalKey(
                                "records", bytes("key-1-0"))))
                .assertDeterministic();
    }

    @Test
    void providerFailsClosedOnMissingOrMismatchedCanonicalGenesis() {
        String chainId = "provider-chain";
        String member = "11".repeat(32);
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .stateMachineId(AuthenticatedMapStateMachine.ID)
                .build();
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config, repeated(7), 16, 32_768,
                List.of(collection("records", AuthenticatedMapContract.AUTH_OPEN, true)),
                List.of());
        AppChainMembershipEpoch epoch = new AppChainMembershipEpoch(
                0, List.of(member), 1);

        StdlibStateMachineProviders.AuthenticatedMapProvider provider =
                new StdlibStateMachineProviders.AuthenticatedMapProvider();
        assertThat(provider.create(context(config, epoch,
                AuthenticatedMapGenesisFactory.settings(genesis))))
                .isInstanceOfSatisfying(CompositeStateMachine.class, machine -> {
                    assertThat(machine.id()).isEqualTo(AuthenticatedMapStateMachine.ID);
                    assertThat(machine.profile().components())
                            .extracting(component -> component.componentId())
                            .containsExactly("domain-actors", "role-approvals",
                                    "authenticated-map");
                    assertThat(machine.profile().workflows())
                            .extracting(workflow -> workflow.workflowId())
                            .containsExactly("authenticated-map-authorization-v1");
                });
        assertThatThrownBy(() -> provider.create(context(config, epoch, Map.of())))
                .hasMessageContaining(
                        StdlibStateMachineProviders.AUTHENTICATED_MAP_GENESIS_SETTING);

        AuthenticatedMapContract.Genesis wrongFingerprint =
                new AuthenticatedMapContract.Genesis(
                        genesis.chainId(), genesis.commitmentProfileId(), repeated(9),
                        genesis.frameworkConsensusProfileDigest(),
                        genesis.membershipCommitment(), genesis.anchorPolicyCommitment(),
                        genesis.maxBatchItems(), genesis.maxBatchBytes(),
                        genesis.collections(), genesis.initialEntries());
        assertThatThrownBy(() -> provider.create(context(
                config, epoch, AuthenticatedMapGenesisFactory.settings(wrongFingerprint))))
                .hasMessageContaining("fingerprint");

        AppChainMembershipEpoch differentMembership = new AppChainMembershipEpoch(
                0, List.of("33".repeat(32)), 1);
        assertThatThrownBy(() -> provider.create(context(
                config, differentMembership, AuthenticatedMapGenesisFactory.settings(genesis))))
                .hasMessageContaining("membership");

        AuthenticatedMapContract.ValidatorDescriptor schema10 =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "records-schema", quantitySchema(10).definition());
        AuthenticatedMapContract.ValidatorDescriptor schema11 =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "records-schema", quantitySchema(11).definition());
        AuthenticatedMapContract.Genesis firstSchemaGenesis =
                AuthenticatedMapGenesisFactory.mpf(
                        config, repeated(7), 16, 32_768,
                        List.of(schemaCollection("records", schema10.id())),
                        List.of(schema10), List.of());
        AuthenticatedMapContract.Genesis differentSchemaGenesis =
                AuthenticatedMapGenesisFactory.mpf(
                        config, repeated(7), 16, 32_768,
                        List.of(schemaCollection("records", schema11.id())),
                        List.of(schema11), List.of());
        Map<String, String> mismatchedSchemaSettings = new LinkedHashMap<>(
                AuthenticatedMapGenesisFactory.settings(firstSchemaGenesis));
        mismatchedSchemaSettings.put(
                StdlibStateMachineProviders.AUTHENTICATED_MAP_GENESIS_SETTING,
                hex(AuthenticatedMapContract.encodeGenesis(differentSchemaGenesis)));
        assertThatThrownBy(() -> provider.create(context(
                config, epoch, Map.copyOf(mismatchedSchemaSettings))))
                .hasMessageContaining("state commitment identity");
    }

    private static AuthenticatedMapStateMachine machine(
            List<AuthenticatedMapContract.CollectionDescriptor> collections) {
        return new AuthenticatedMapStateMachine(genesis(collections),
                height -> new AppChainMembershipEpoch(0, List.of(hex(OWNER)), 1));
    }

    private static AuthenticatedMapStateMachine pluginMachine(
            AuthenticatedMapContract.ValidatorDescriptor descriptor,
            com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator validator,
            List<AuthenticatedMapContract.GenesisEntry> initialEntries
    ) {
        AuthenticatedMapContract.Genesis genesis = new AuthenticatedMapContract.Genesis(
                CHAIN_ID, StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(),
                repeated(1), repeated(2), repeated(3), 16, 32_768,
                List.of(pluginCollection("records", descriptor.id())),
                List.of(descriptor), initialEntries);
        return new AuthenticatedMapStateMachine(genesis,
                height -> new AppChainMembershipEpoch(0, List.of(hex(OWNER)), 1),
                resolver(descriptor, validator));
    }

    private static AuthenticatedMapValidatorResolver resolver(
            AuthenticatedMapContract.ValidatorDescriptor descriptor,
            com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator validator
    ) {
        return (digest, context) -> {
            assertThat(digest).isEqualTo(descriptor.definition());
            assertThat(context.descriptorId()).isEqualTo(descriptor.id());
            assertThat(context.providerId()).isEqualTo(descriptor.providerId());
            assertThat(context.collectionIds()).containsExactly("records");
            return validator;
        };
    }

    private static AuthenticatedMapStateMachine machine(
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.ValidatorDescriptor> validators) {
        return new AuthenticatedMapStateMachine(genesis(collections, validators),
                height -> new AppChainMembershipEpoch(0, List.of(hex(OWNER)), 1));
    }

    private static AppStateMachineContext context(
            AppChainConfig config,
            AppChainMembershipEpoch membership,
            Map<String, String> settings
    ) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return config.chainId(); }
            @Override public Map<String, String> settings() { return settings; }
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
        };
    }

    private static AuthenticatedMapContract.Genesis genesis(
            List<AuthenticatedMapContract.CollectionDescriptor> collections) {
        return genesis(collections, List.of());
    }

    private static AuthenticatedMapContract.Genesis genesis(
            List<AuthenticatedMapContract.CollectionDescriptor> collections,
            List<AuthenticatedMapContract.ValidatorDescriptor> validators) {
        return new AuthenticatedMapContract.Genesis(
                CHAIN_ID,
                StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(),
                repeated(1), repeated(2), repeated(3),
                16, 32_768, collections, validators, List.of());
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(
            String id, int authorization, boolean restoreAllowed) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, authorization, restoreAllowed, 64, 1024);
    }

    private static AuthenticatedMapContract.CollectionDescriptor canonicalCollection(String id) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, AuthenticatedMapContract.AUTH_OPEN, false, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR);
    }

    private static AuthenticatedMapContract.CollectionDescriptor schemaCollection(
            String id,
            String validatorId
    ) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, AuthenticatedMapContract.AUTH_OPEN, false, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, validatorId);
    }

    private static AuthenticatedMapContract.CollectionDescriptor pluginCollection(
            String id,
            String validatorId
    ) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, AuthenticatedMapContract.AUTH_OPEN, false, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, validatorId);
    }

    private static AuthenticatedMapContract.ValidatorDescriptor pluginDescriptor() {
        return AuthenticatedMapContract.ValidatorDescriptor.plugin(
                "product-validator", "example-validator-v1", repeated(9),
                new byte[]{(byte) 0xa0});
    }

    private static AuthenticatedMapSchema.Schema quantitySchema(int maximum) {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("qty", true,
                        new AuthenticatedMapSchema.IntegerNode(
                                AuthenticatedMapSchema.INTEGER_UINT,
                                BigInteger.ZERO, BigInteger.valueOf(maximum))))));
    }

    private static AuthenticatedMapContract.Command single(
            AuthenticatedMapContract.Mutation mutation) {
        return AuthenticatedMapContract.Command.single(mutation);
    }

    private static byte[] finalCommand(
            AuthenticatedMapContract.Command command,
            int authorizationKind
    ) {
        return AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        AuthenticatedMapAuthorizationContract.MapActionV1.basic(
                                command, java.util.Collections.nCopies(
                                        command.mutations().size(), authorizationKind)),
                        List.of()));
    }

    private static AppMessage message(byte[] sender, long sequence,
                                      AuthenticatedMapContract.Command command) {
        byte[] body = AuthenticatedMapContract.encodeCommand(command);
        byte[] messageId = AppMessage.computeMessageId(
                CHAIN_ID, AuthenticatedMapContract.DEFAULT_TOPIC,
                sender, sequence, 4_000_000_000L, body);
        return AppMessage.builder()
                .messageId(messageId)
                .chainId(CHAIN_ID)
                .topic(AuthenticatedMapContract.DEFAULT_TOPIC)
                .sender(sender)
                .senderSeq(sequence)
                .expiresAt(4_000_000_000L)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[64])
                .build();
    }

    private static void apply(AuthenticatedMapStateMachine machine, TestState state,
                              long height, AppMessage... messages) {
        AppBlock block = new AppBlock(
                AppBlock.BLOCK_VERSION, CHAIN_ID, height, new byte[32], 0,
                new byte[0], 1_700_000_000_000L + height,
                new byte[32], new byte[32], List.of(messages), OWNER,
                FinalityCert.empty());
        machine.apply(block, state);
        state.committedHeight = height;
    }

    private static AuthenticatedMapContract.Entry entry(
            TestState state, String collection, String key) {
        return state.get(AuthenticatedMapContract.canonicalKey(collection, bytes(key)))
                .map(AuthenticatedMapContract::decodeEntry)
                .orElseThrow();
    }

    private static void assertApplied(TestState state, AppMessage message, long height) {
        AuthenticatedMapContract.Receipt receipt = state.get(
                        AuthenticatedMapContract.receiptKey(message.getMessageId()))
                .map(AuthenticatedMapContract::decodeReceipt)
                .orElseThrow();
        assertThat(receipt.status()).isEqualTo(AuthenticatedMapContract.RECEIPT_APPLIED);
        assertThat(receipt.height()).isEqualTo(height);
    }

    private static void assertRejected(TestState state, AppMessage message, int error) {
        AuthenticatedMapContract.Receipt receipt = state.get(
                        AuthenticatedMapContract.receiptKey(message.getMessageId()))
                .map(AuthenticatedMapContract::decodeReceipt)
                .orElseThrow();
        assertThat(receipt.status()).isEqualTo(AuthenticatedMapContract.RECEIPT_REJECTED);
        assertThat(receipt.errorCode()).isEqualTo(error);
        assertThat(receipt.results()).isEmpty();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hexBytes(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static byte[] repeated(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static final class TestState implements AppStateWriter, AppQueryContext {
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
