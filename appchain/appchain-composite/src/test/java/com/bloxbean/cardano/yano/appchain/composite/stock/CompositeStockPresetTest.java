package com.bloxbean.cardano.yano.appchain.composite.stock;

import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleasePrerequisiteCommandsV1;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectOperation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeStockPresetTest {
    private static final String CHAIN = "stock-chain";
    private static final byte[] SENDER = filled(0x44);
    private static final byte[] REGISTRY_KEY = "product-42".getBytes(StandardCharsets.US_ASCII);

    @Test
    void demoProfileDigestsRemainExplicitDeploymentTrustRoots() throws Exception {
        Path manifest = Path.of(System.getProperty("yano.repo.root"), "app",
                "appchain-effects-demo", "config", "composite-profile-digests.properties");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.US_ASCII)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] entry = line.split("=", -1);
            assertThat(entry).hasSize(2);
            assertThat(entry[1]).matches("[0-9a-f]{64}");
            assertThat(expected.putIfAbsent(entry[0], entry[1])).isNull();
        }
        assertThat(expected.keySet()).containsExactlyInAnyOrder(
                "app-final:explicit", "app-final:direct",
                "l1-anchored:explicit", "l1-anchored:direct");
        Map<String, String> actualDigests = new HashMap<>();
        for (String gate : List.of("app-final", "l1-anchored")) {
            for (boolean direct : List.of(false, true)) {
                Map<String, String> settings = new HashMap<>();
                settings.put("effects.max-per-block", "128");
                settings.put("machines.evidence-registry.storage-gate", gate);
                if (direct) {
                    settings.put("machines.evidence-registry.activations.direct-result-emission", "1");
                }
                String actual = HexFormat.of().formatHex(
                        CompositeStockPresets.create(context(settings)).profile().digest());
                actualDigests.put(gate + ":" + (direct ? "direct" : "explicit"), actual);
            }
        }
        assertThat(actualDigests).isEqualTo(expected);
    }

    @Test
    void presetIsConfigurationOnlyAndCommitsExpectedOrderedInventory() {
        CompositeStateMachine machine = CompositeStockPresets.create(context(Map.of()));

        assertThat(machine.id()).isEqualTo("composite");
        assertThat(machine.profile().profileId()).isEqualTo("evidence-v1-gated");
        assertThat(machine.profile().components()).extracting(component -> component.componentId())
                .containsExactly("registry", "approvals", "doc-trail", "evidence");
        assertThat(machine.profile().workflows()).extracting(workflow -> workflow.workflowId())
                .containsExactly("evidence-notify", "evidence-release");
        assertThat(machine.profile().queryAliases()).extracting(alias -> alias.aliasPath())
                .containsExactly(EvidenceContract.GET_QUERY_PATH);
    }

    @Test
    void evidenceCapacityDeterministicallyDerivesEveryReservedQuota() {
        CompositeStateMachine gated = CompositeStockPresets.create(context(Map.of(
                "machines.composite.evidence-capacity-per-block", "4")));
        CompositeStateMachine direct = CompositeStockPresets.create(context(Map.of(
                "machines.composite.preset", CompositeStockPresets.EVIDENCE_V1,
                "machines.composite.evidence-capacity-per-block", "4")));

        assertThat(gated.profile().components()).filteredOn(component ->
                        component.componentId().equals(CompositeStockPresets.EVIDENCE_ID))
                .singleElement().extracting(component -> component.maxEffectsPerBlock())
                .isEqualTo(4);
        assertThat(gated.profile().workflows()).extracting(
                        workflow -> workflow.workflowId() + ":" + workflow.maxEffectsPerBlock())
                .containsExactly("evidence-notify:4", "evidence-release:8");
        assertThat(direct.profile().components()).filteredOn(component ->
                        component.componentId().equals(CompositeStockPresets.EVIDENCE_ID))
                .singleElement().extracting(component -> component.maxEffectsPerBlock())
                .isEqualTo(8);
        assertThat(direct.profile().workflows()).singleElement()
                .extracting(workflow -> workflow.maxEffectsPerBlock()).isEqualTo(8);
        assertThat(gated.profile().digest()).isNotEqualTo(
                CompositeStockPresets.create(context(Map.of())).profile().digest());
    }

    @Test
    void defaultCapacityAllowsEightIndependentReleasesInOneBlock() {
        CompositeStateMachine machine = CompositeStockPresets.create(context(Map.of()));
        MemoryState state = new MemoryState();
        List<SubmitEvidenceCommandV1> evidence = new ArrayList<>();
        List<AppMessage> proposals = new ArrayList<>();
        List<AppMessage> approvals = new ArrayList<>();
        List<AppMessage> releases = new ArrayList<>();

        machine.apply(block(1, message(1, CompositeStockPresets.REGISTRY_TOPIC,
                KvRegistryStateMachine.put(REGISTRY_KEY, bytes("passport")))),
                state, new CapturingEmitter(1));
        for (int index = 0; index < 8; index++) {
            SubmitEvidenceCommandV1 command = evidence("sample-" + index);
            evidence.add(command);
            String approvalId = "approval-" + index;
            proposals.add(message(2 + index, CompositeStockPresets.APPROVALS_TOPIC,
                    ApprovalsStateMachine.propose(approvalId, command.encode(), 1, 0)));
            approvals.add(message(10 + index, CompositeStockPresets.APPROVALS_TOPIC,
                    ApprovalsStateMachine.approve(approvalId)));
            EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                    "release-" + index, REGISTRY_KEY, approvalId, "product-" + index,
                    filled(0x61 + index), "object:" + index, command.encode());
            releases.add(message(18 + index, EvidenceReleaseWorkflow.TOPIC, release.encode()));
        }
        machine.apply(block(2, proposals.toArray(AppMessage[]::new)),
                state, new CapturingEmitter(2));
        machine.apply(block(3, approvals.toArray(AppMessage[]::new)),
                state, new CapturingEmitter(3));
        CapturingEmitter emitted = new CapturingEmitter(4);

        machine.apply(block(4, releases.toArray(AppMessage[]::new)), state, emitted);

        assertThat(emitted.intents).hasSize(16);
        assertThat(emitted.intents).extracting(EffectIntent::type)
                .containsOnly("object.put", "ipfs.pin");
        for (int index = 0; index < evidence.size(); index++) {
            assertThat(record(state, "sample-" + index, 1).businessVersion()).isEqualTo(1);
        }
    }

    @Test
    void stockPresetCanStartAsGovernedEpochZeroWithoutChangingProfileDigest() {
        CompositeStateMachine fixed = CompositeStockPresets.create(context(Map.of()));
        CompositeStateMachine governed = CompositeStockPresets.create(context(Map.of(
                "membership.mode", "governed",
                "machines.composite.profile-mode", "governed")));
        MemoryState state = new MemoryState();

        governed.apply(block(1), state, new CapturingEmitter(1));

        assertThat(governed.profile().digest()).containsExactly(fixed.profile().digest());
        assertThat(governed.operationalStatus()).containsEntry("mode", "governed")
                .containsEntry("currentEpoch", 0L);
        assertThat(governed.query("composite/profile-epoch-v1", new byte[0], state.atHeight(1)))
                .isNotEmpty();
    }

    @Test
    void gatedPresetAllowsOnlyNotifyOnDirectEvidenceTopic() {
        SubmitEvidenceCommandV1 command = evidence("direct-bypass");
        AppMessage direct = message(1, EvidenceContract.COMMAND_TOPIC, command.encode());
        AppMessage republish = message(2, EvidenceContract.COMMAND_TOPIC,
                new RepublishEvidenceCommandV1(
                        command.evidenceId(), 2, command.objectPutCommand(),
                        command.expectedObjectDestinationFingerprint(), command.ipfsPinCommand(),
                        command.expectedIpfsTargetFingerprint(), command.kafkaTarget(),
                        command.kafkaTopic(),
                        command.expectedKafkaDestinationFingerprint()).encode());
        AppMessage notify = message(3, EvidenceContract.COMMAND_TOPIC,
                new NotifyEvidenceCommandV1(command.evidenceId(), 1).encode());
        CompositeStateMachine gated = CompositeStockPresets.create(context(Map.of()));
        CompositeStateMachine explicit = CompositeStockPresets.create(context(Map.of(
                "machines.composite.preset", CompositeStockPresets.EVIDENCE_V1)));

        assertThat(gated.validate(direct).isAccepted()).isFalse();
        assertThat(gated.validate(republish).isAccepted()).isFalse();
        assertThat(gated.validate(notify).isAccepted()).isTrue();
        assertThat(explicit.validate(direct).isAccepted()).isTrue();
        assertThat(gated.profile().digest())
                .isNotEqualTo(explicit.profile().digest());

        MemoryState state = new MemoryState();
        CapturingEmitter emitter = new CapturingEmitter(1);
        gated.apply(block(1, direct), state, emitter);
        assertThat(emitter.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("direct-bypass", 1)))).isEmpty();
    }

    @Test
    void settingsDiscoveryOrderCannotChangeProfileBytesOrDigest() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("machines.composite.preset", "evidence-v1");
        forward.put("machines.kv-registry.value-format", "raw");
        forward.put(AppChainApprovalsConfig.ENABLED, "false");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put(AppChainApprovalsConfig.ENABLED, "false");
        reverse.put("machines.kv-registry.value-format", "raw");
        reverse.put("machines.composite.preset", "evidence-v1");

        CompositeStateMachine first = CompositeStockPresets.create(context(forward));
        CompositeStateMachine second = CompositeStockPresets.create(context(reverse));

        assertThat(first.profile().canonicalBytes())
                .containsExactly(second.profile().canonicalBytes());
        assertThat(first.profile().digest()).containsExactly(second.profile().digest());
    }

    @Test
    void releaseRequiresMatchingApprovalThenWritesTrailAndEvidenceExactlyOnce() {
        CompositeStateMachine machine = CompositeStockPresets.create(context(Map.of()));
        MemoryState state = new MemoryState();
        SubmitEvidenceCommandV1 evidence = evidence("sample-42");
        EvidenceReleaseCommandV1 release = release("release-42", evidence, "doc://certificate");

        machine.apply(block(1,
                message(1, CompositeStockPresets.REGISTRY_TOPIC,
                        KvRegistryStateMachine.put(REGISTRY_KEY, bytes("passport"))),
                message(2, CompositeStockPresets.APPROVALS_TOPIC,
                        ApprovalsStateMachine.propose(
                                "approval-42", evidence.encode(), 1, 0))),
                state, new CapturingEmitter(1));
        machine.apply(block(2, message(3, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.approve("approval-42"))), state, new CapturingEmitter(2));

        CapturingEmitter emitted = new CapturingEmitter(3);
        machine.apply(block(3, message(4, EvidenceReleaseWorkflow.TOPIC, release.encode())),
                state, emitted);

        assertThat(emitted.intents).extracting(EffectIntent::type)
                .containsExactly("object.put", "ipfs.pin");
        byte[] evidenceRecordKey = CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("sample-42", 1));
        assertThat(state.get(evidenceRecordKey)).isPresent();
        byte[] trailKey = CompositeStateKeys.componentKey("doc-trail",
                DocTrailStateMachine.entityKey("product-42"));
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);

        CapturingEmitter replay = new CapturingEmitter(4);
        machine.apply(block(4, message(5, EvidenceReleaseWorkflow.TOPIC, release.encode())),
                state, replay);
        assertThat(replay.intents).isEmpty();
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);

        byte[] query = machine.query(EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1("sample-42", 1).encode(), state.atHeight(4));
        assertThat(EvidenceGetResponseV1.decode(query).found()).isTrue();
    }

    @Test
    void gatedReleaseRepublishesExactNextVersionOnlyAfterPriorVersionIsTerminal() {
        CompositeStateMachine machine = CompositeStockPresets.create(context(Map.of()));
        MemoryState state = new MemoryState();
        SubmitEvidenceCommandV1 submit = evidence("sample-42");
        EvidenceReleaseCommandV1 initial = release(
                "release-42", submit, "doc://certificate/v1");

        machine.apply(block(1,
                message(1, CompositeStockPresets.REGISTRY_TOPIC,
                        KvRegistryStateMachine.put(REGISTRY_KEY, bytes("passport"))),
                message(2, CompositeStockPresets.APPROVALS_TOPIC,
                        ApprovalsStateMachine.propose(
                                "approval-42", submit.encode(), 1, 0))),
                state, new CapturingEmitter(1));
        machine.apply(block(2, message(3, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.approve("approval-42"))), state, new CapturingEmitter(2));
        machine.apply(block(3, message(4, EvidenceReleaseWorkflow.TOPIC, initial.encode())),
                state, new CapturingEmitter(3));

        RepublishEvidenceCommandV1 republish = republish(submit, 2);
        EvidenceReleaseCommandV1 next = new EvidenceReleaseCommandV1(
                "release-43", REGISTRY_KEY, "approval-43", "product-42",
                filled(0x67), "doc://certificate/v2", republish.encode());
        machine.apply(block(4, message(5, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.propose("approval-43", republish.encode(), 1, 0))),
                state, new CapturingEmitter(4));
        machine.apply(block(5, message(6, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.approve("approval-43"))), state, new CapturingEmitter(5));

        byte[] trailKey = CompositeStateKeys.componentKey("doc-trail",
                DocTrailStateMachine.entityKey("product-42"));
        CapturingEmitter premature = new CapturingEmitter(6);
        machine.apply(block(6, message(7, EvidenceReleaseWorkflow.TOPIC, next.encode())),
                state, premature);
        assertThat(premature.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("sample-42", 2)))).isEmpty();
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);

        machine.onEffectResult(block(7), failedResult(3, 0,
                ConnectorTypes.OBJECT_PUT, EvidenceEffectOperation.OBJECT, 7),
                state, new CapturingEmitter(7));
        machine.onEffectResult(block(8), failedResult(3, 1,
                ConnectorTypes.IPFS_PIN, EvidenceEffectOperation.IPFS, 8),
                state, new CapturingEmitter(8));
        assertThat(EvidenceStatus.derive(record(state, "sample-42", 1)))
                .isEqualTo(EvidenceStatus.STORAGE_FAILED);

        CapturingEmitter foreignOwner = new CapturingEmitter(9);
        machine.apply(block(9, message(8, EvidenceReleaseWorkflow.TOPIC, next.encode(),
                filled(0x45))), state, foreignOwner);
        assertThat(foreignOwner.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("sample-42", 2)))).isEmpty();
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);

        RepublishEvidenceCommandV1 skipped = republish(submit, 3);
        EvidenceReleaseCommandV1 skippedRelease = new EvidenceReleaseCommandV1(
                "release-44", REGISTRY_KEY, "approval-44", "product-42",
                filled(0x68), "doc://certificate/v3", skipped.encode());
        machine.apply(block(10, message(9, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.propose("approval-44", skipped.encode(), 1, 0))),
                state, new CapturingEmitter(10));
        machine.apply(block(11, message(10, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.approve("approval-44"))), state, new CapturingEmitter(11));
        CapturingEmitter versionSkip = new CapturingEmitter(12);
        machine.apply(block(12, message(11, EvidenceReleaseWorkflow.TOPIC,
                skippedRelease.encode())), state, versionSkip);
        assertThat(versionSkip.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("sample-42", 3)))).isEmpty();
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);

        CapturingEmitter emitted = new CapturingEmitter(13);
        machine.apply(block(13, message(12, EvidenceReleaseWorkflow.TOPIC, next.encode())),
                state, emitted);

        assertThat(emitted.intents).extracting(EffectIntent::type)
                .containsExactly(ConnectorTypes.OBJECT_PUT, ConnectorTypes.IPFS_PIN);
        assertThat(record(state, "sample-42", 2).businessVersion()).isEqualTo(2);
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(2);
        EvidenceGetResponseV1 latest = EvidenceGetResponseV1.decode(machine.query(
                EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1("sample-42", 0).encode(), state.atHeight(13)));
        assertThat(latest.head().latestVersion()).isEqualTo(2);
        assertThat(latest.record().businessVersion()).isEqualTo(2);
        EvidenceGetResponseV1 historical = EvidenceGetResponseV1.decode(machine.query(
                EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1("sample-42", 1).encode(), state.atHeight(13)));
        assertThat(historical.record().businessVersion()).isEqualTo(1);
    }

    @Test
    void businessPreconditionFailuresAndConflictingReplayAreFinalizableNoOps() {
        CompositeStateMachine machine = CompositeStockPresets.create(context(Map.of()));
        MemoryState state = new MemoryState();
        SubmitEvidenceCommandV1 evidence = evidence("sample-42");
        EvidenceReleaseCommandV1 release = release("release-42", evidence, "doc://certificate");
        machine.apply(block(1, message(1, CompositeStockPresets.REGISTRY_TOPIC,
                KvRegistryStateMachine.put(REGISTRY_KEY, bytes("passport")))),
                state, new CapturingEmitter(1));

        CapturingEmitter missingApproval = new CapturingEmitter(2);
        machine.apply(block(2, message(2, EvidenceReleaseWorkflow.TOPIC, release.encode())),
                state, missingApproval);
        assertThat(missingApproval.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("sample-42", 1)))).isEmpty();

        machine.apply(block(2, message(3, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.propose("approval-42", evidence.encode(), 1, 0))),
                state, new CapturingEmitter(2));
        machine.apply(block(3, message(4, CompositeStockPresets.APPROVALS_TOPIC,
                ApprovalsStateMachine.approve("approval-42"))), state, new CapturingEmitter(3));
        machine.apply(block(4, message(5, EvidenceReleaseWorkflow.TOPIC, release.encode())),
                state, new CapturingEmitter(4));

        EvidenceReleaseCommandV1 conflict = release(
                "release-42", evidence, "doc://different");
        byte[] trailKey = CompositeStateKeys.componentKey("doc-trail",
                DocTrailStateMachine.entityKey("product-42"));
        CapturingEmitter conflictingReplay = new CapturingEmitter(5);
        machine.apply(block(5, message(6, EvidenceReleaseWorkflow.TOPIC, conflict.encode())),
                state, conflictingReplay);
        assertThat(conflictingReplay.intents).isEmpty();
        assertThat(DocTrailStateMachine.decodeEntry(state.get(trailKey).orElseThrow()).count())
                .isEqualTo(1);
    }

    @Test
    void providerRejectsUnsafeOrUnknownPresetConfiguration() {
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                "machines.composite.preset", "unknown"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported composite preset");
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                "effects.max-per-block", "3"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 32");
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                "machines.composite.evidence-capacity-per-block", "0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1");
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                "machines.composite.evidence-capacity-per-block", "not-a-number"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal integer");
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                "effects.enabled", "true",
                AppChainApprovalsConfig.ENABLED, "true",
                AppChainApprovalsConfig.TYPE, "demo.webhook",
                AppChainApprovalsConfig.ACTIVATION, "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("on-approved effect disabled");
        assertThatThrownBy(() -> CompositeStockPresets.create(context(Map.of(
                AppChainApprovalsConfig.ENABLED, "yes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be true or false");
    }

    @Test
    void commandCodecRejectsTrailingBytesAndDefensivelyCopies() {
        EvidenceReleaseCommandV1 command = release("release-42", evidence("sample-42"), "ref");
        byte[] encoded = command.encode();
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        byte[] returned = command.registryKey();
        returned[0] ^= 1;

        assertThat(EvidenceReleaseCommandV1.decode(encoded)).isEqualTo(command);
        assertThat(command.registryKey()).containsExactly(REGISTRY_KEY);
        assertThatThrownBy(() -> EvidenceReleaseCommandV1.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-canonical");
    }

    private static AppStateMachineContext context(Map<String, String> overrides) {
        Map<String, String> settings = new HashMap<>();
        settings.put("effects.enabled", "true");
        settings.put("effects.max-per-block", "32");
        settings.putAll(overrides);
        int effectCap = Integer.parseInt(settings.get("effects.max-per-block"));
        return new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return Map.copyOf(settings); }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
            consensusProfile() {
                return Optional.of(AppChainTestProfiles.enabledEffects(effectCap, 8));
            }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView>
            membershipView() {
                var epoch = new com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch(
                        0, List.of(HexFormat.of().formatHex(SENDER)), 1);
                return Optional.of(ignored -> epoch);
            }
        };
    }

    private static SubmitEvidenceCommandV1 evidence(String id) {
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/certificate.bin", "evidence/certificate.bin",
                DigestAlgorithm.SHA_256, filled(0x11), 32,
                "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CanonicalCid.fromText(
                "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi"),
                true, "demo-single");
        return new SubmitEvidenceCommandV1(id, 1, object.encode(), filled(0x22),
                ipfs.encode(), filled(0x33), "primary", "evidence-ready", filled(0x55));
    }

    private static RepublishEvidenceCommandV1 republish(
            SubmitEvidenceCommandV1 submit,
            long version
    ) {
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/certificate-v" + version + ".bin",
                "evidence/certificate-v" + version + ".bin",
                DigestAlgorithm.SHA_256, filled(0x12), 33,
                "application/octet-stream", null);
        return new RepublishEvidenceCommandV1(submit.evidenceId(), version,
                object.encode(), submit.expectedObjectDestinationFingerprint(),
                submit.ipfsPinCommand(), submit.expectedIpfsTargetFingerprint(),
                submit.kafkaTarget(), submit.kafkaTopic(),
                submit.expectedKafkaDestinationFingerprint());
    }

    private static EffectResult failedResult(
            long effectHeight,
            int ordinal,
            String type,
            EvidenceEffectOperation operation,
            long resultHeight
    ) {
        return new EffectResult(new EffectId(CHAIN, effectHeight, ordinal), type,
                EvidenceKeys.effectScope("sample-42", 1, operation),
                EffectOutcome.FAILED, bytes("demo-failure"), filled(0x77), resultHeight);
    }

    private static EvidenceRecordV1 record(MemoryState state, String id, long version) {
        return EvidenceRecordV1.decode(state.get(CompositeStateKeys.componentKey(
                "evidence", EvidenceKeys.recordKey(id, version))).orElseThrow());
    }

    private static EvidenceReleaseCommandV1 release(
            String releaseId,
            SubmitEvidenceCommandV1 evidence,
            String ref
    ) {
        return new EvidenceReleaseCommandV1(releaseId, REGISTRY_KEY, "approval-42",
                "product-42", filled(0x66), ref, evidence.encode());
    }

    private static AppMessage message(long sequence, String topic, byte[] body) {
        return message(sequence, topic, body, SENDER);
    }

    private static AppMessage message(
            long sequence,
            String topic,
            byte[] body,
            byte[] sender
    ) {
        byte[] id = new byte[32];
        id[31] = (byte) sequence;
        return AppMessage.builder().messageId(id).chainId(CHAIN).topic(topic).sender(sender)
                .senderSeq(sequence).expiresAt(Long.MAX_VALUE).body(body)
                .authScheme(0).authProof(new byte[64]).build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(1, CHAIN, height, new byte[32], 0, new byte[0],
                1_700_000_000_000L + height, new byte[32], new byte[32],
                List.of(messages), new byte[32], FinalityCert.empty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EffectIntent> intents = new ArrayList<>();

        private CapturingEmitter(long height) {
            this.height = height;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            intents.add(intent);
            return new EffectId(CHAIN, height, intents.size() - 1);
        }

        @Override
        public long pendingCount() {
            return 0;
        }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new HashMap<>();
        private long height;

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public byte[] stateRoot() {
            byte[] root = new byte[32];
            root[31] = (byte) height;
            return root;
        }

        @Override
        public long committedHeight() {
            return height;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }

        private MemoryState atHeight(long value) {
            height = value;
            return this;
        }
    }

    @Test
    void lightweightPrerequisiteEncodersStayWireCompatibleWithStdlib() {
        byte[] payload = evidence("sample-42").encode();
        assertThat(EvidenceReleasePrerequisiteCommandsV1.registryPut(REGISTRY_KEY, new byte[]{1}))
                .isEqualTo(KvRegistryStateMachine.put(REGISTRY_KEY, new byte[]{1}));
        assertThat(EvidenceReleasePrerequisiteCommandsV1.approvalPropose(
                "approval-42", payload, 1, 0))
                .isEqualTo(ApprovalsStateMachine.propose("approval-42", payload, 1, 0));
        assertThat(EvidenceReleasePrerequisiteCommandsV1.approvalApprove("approval-42"))
                .isEqualTo(ApprovalsStateMachine.approve("approval-42"));
    }
}
