package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAttestation;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCandidate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProviderFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRequest;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSourceConfiguration;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationRuntimeClusterTest {
    private static final String CHAIN_ID = "observation-cluster-test";
    private static final String LIVE_SOURCE = "https://raw.githubusercontent.com/bloxbean/yano/"
            + "ed5de419da68b524bdad2a3848c3156c419d0476/gradle/wrapper/gradle-wrapper.properties";
    private static final List<String> SEEDS = List.of(
            "61".repeat(32), "62".repeat(32), "63".repeat(32));

    @Test
    void partitionHealAndProposerRotationConvergeOnOneResult(@TempDir Path directory)
            throws Exception {
        runCluster(directory, false);
    }

    @Test
    void signedAttestationConvergesAcrossThreeNodes(@TempDir Path directory) throws Exception {
        runCluster(directory, true);
    }

    @Test
    void signedAttestationFinalizesThroughThreeNetworkedSubsystems(@TempDir Path directory)
            throws Exception {
        networkCluster(directory, true);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "YANO_OBSERVATION_HTTPS_LIVE", matches = "1")
    void rawHttpsFinalizesThroughThreeNetworkedSubsystems(@TempDir Path directory)
            throws Exception {
        networkCluster(directory, false);
    }

    private static void networkCluster(Path directory, boolean attested) throws Exception {
        List<AppMessageSigner> signers = SEEDS.stream().map(AppMessageSigner::new).toList();
        AppMessageSigner attestor = new AppMessageSigner("64".repeat(32));
        Set<String> members = Set.copyOf(signers.stream()
                .map(AppMessageSigner::publicKeyHex).toList());
        ObservationDefinition definition = definition(signers, attested, attestor, true);
        ObservationProfileV1 profile = profile(definition);
        ObservationProviderFactory factory = new ObservationProviderFactory() {
            @Override public String type() { return "fixture-attested-v1"; }
            @Override public ObservationProvider create(String id, Map<String, String> settings) {
                return request -> candidate(request, definition, true, attestor);
            }
        };
        PluginProviderRegistry registry = new PluginProviderRegistry() {
            @Override public <P> Optional<P> find(Class<P> type, String selector) {
                return type == ObservationProviderFactory.class && selector.equals(factory.type())
                        ? Optional.of(type.cast(factory)) : Optional.empty();
            }
            @Override public <P> List<String> names(Class<P> type) {
                return type == ObservationProviderFactory.class ? List.of(factory.type()) : List.of();
            }
        };
        List<Integer> ports = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            try (ServerSocket socket = new ServerSocket(0)) { ports.add(socket.getLocalPort()); }
        }
        List<AppChainSubsystem> subsystems = new ArrayList<>();
        List<NodeServer> servers = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                int port = ports.get(index);
                Map<String, String> providerSettings = new LinkedHashMap<>();
                providerSettings.put(ObservationSettings.PROFILE_HEX,
                        HexUtil.encodeHexString(profile.encode()));
                providerSettings.put("observations.providers.delivery.type",
                        attested ? factory.type() : ObservationProviders.HTTPS_EXACT);
                if (attested) {
                    providerSettings.put("observations.attestors.delivery", attestor.publicKeyHex());
                } else {
                    providerSettings.put("observations.providers.delivery.url", LIVE_SOURCE);
                    providerSettings.put("observations.providers.delivery.source-id", "source");
                }
                AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                        .signingKeyHex(SEEDS.get(index)).memberKeysHex(members).threshold(2)
                        .proposerKeyHex(signers.getFirst().publicKeyHex()).blockIntervalMs(300)
                        .peers(ports.stream().filter(candidate -> candidate != port)
                                .map(candidate -> new AppChainConfig.AppPeer("localhost", candidate))
                                .toList())
                        .stateCommitmentIdentity(TestStateCommitments.MPF)
                        .pluginSettings(providerSettings)
                        .build();
                AppChainSubsystem node = new AppChainSubsystem(config, 42, null, machine(),
                        directory.resolve("network-" + index).toString(), null, registry,
                        LoggerFactory.getLogger(ObservationRuntimeClusterTest.class));
                subsystems.add(node);
                NodeServer server = new NodeServer(port,
                        N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                        new InMemoryChainState(), null, null, node.serverAgentFactories());
                servers.add(server);
                Thread thread = new Thread(server::start, "observation-test-server-" + index);
                thread.setDaemon(true);
                thread.start();
                node.start();
            }
            AppChainSubsystem proposer = subsystems.getFirst();
            await(() -> subsystems.stream().allMatch(node -> {
                Object connected = node.status().get("peers");
                return connected instanceof Map<?, ?> peers && peers.size() == 2
                        && peers.values().stream().allMatch(Boolean.TRUE::equals);
            }), Duration.ofSeconds(30));
            proposer.submit("open", new byte[]{1});
            await(() -> subsystems.stream().allMatch(node -> node.tipHeight() >= 1),
                    Duration.ofSeconds(30));
            proposer.submit("due", new byte[]{2});
            await(() -> subsystems.stream().allMatch(node -> node.tipHeight() >= 3),
                    Duration.ofSeconds(30));
            AppBlock finalized = proposer.block(3).orElseThrow();
            assertThat(finalized.messages()).anyMatch(message ->
                    ObservationTopics.RESULT.equals(message.getTopic()));
            byte[] output = ObservationCertificate.decode(finalized.messages().stream()
                    .filter(message -> ObservationTopics.RESULT.equals(message.getTopic()))
                    .findFirst().orElseThrow().getBody()).output();
            assertThat(new String(output, StandardCharsets.US_ASCII))
                    .contains(attested ? "delivered" : "distributionUrl=");
            for (AppChainSubsystem node : subsystems) {
                AppBlock block = node.block(3).orElseThrow();
                assertThat(block.cert().signatures()).hasSize(2);
                assertThat(block.stateRoot()).isEqualTo(finalized.stateRoot());
                assertThat(node.query("result", new byte[0]).payload())
                        .isEqualTo(output);
            }
        } finally {
            for (AppChainSubsystem node : subsystems) node.stop();
            for (NodeServer server : servers) server.shutdown();
        }
    }

    private static void runCluster(Path directory, boolean attested) throws Exception {
        List<AppMessageSigner> signers = SEEDS.stream().map(AppMessageSigner::new).toList();
        AppMessageSigner attestor = new AppMessageSigner("64".repeat(32));
        Set<String> memberKeys = Set.copyOf(signers.stream()
                .map(AppMessageSigner::publicKeyHex).toList());
        MemberGroup members = new MemberGroup(memberKeys, 2);
        ObservationDefinition definition = definition(signers, attested, attestor);
        ObservationProfileV1 profile = profile(definition);
        List<Node> nodes = new ArrayList<>();

        try {
            for (int index = 0; index < signers.size(); index++) {
                AppMessageSigner signer = signers.get(index);
                AppChainConfig config = config(memberKeys, profile, attested, attestor);
                EffectsSettings effects = EffectsSettings.from(config);
                AppChainConsensusProfile consensusProfile = effects.consensusProfile(config);
                ObservationSettings settings = ObservationSettings.from(config, members);
                AppLedgerStore ledger = new AppLedgerStore(
                        directory.resolve("node-" + index).toString(),
                        LoggerFactory.getLogger(ObservationRuntimeClusterTest.class),
                        TestStateCommitments.MPF);
                SystemInputKernel kernel = kernel(settings, effects, consensusProfile, memberKeys);
                apply(ledger, kernel, machine(), 1, List.of());
                apply(ledger, kernel, machine(), 2, List.of());
                List<Diffusion> outbound = new CopyOnWriteArrayList<>();
                ObservationRuntime runtime = new ObservationRuntime(settings,
                        ObservationProviders.of(Map.of("delivery", request ->
                                candidate(request, definition, attested, attestor))),
                        ledger, signer, members, CHAIN_ID, TestStateCommitments.MPF.genesisId(),
                        AppChainConsensusProfileCommitment.digest(consensusProfile),
                        (topic, body) -> outbound.add(new Diffusion(topic, body)), 1,
                        10_000, 4 * 1024 * 1024,
                        LoggerFactory.getLogger(ObservationRuntimeClusterTest.class));
                nodes.add(new Node(ledger, kernel, runtime, outbound));
            }

            // While partitioned, every node has only its own report and none
            // can meet the two-of-three observation threshold.
            nodes.forEach(node -> node.runtime.tick());
            for (Node node : nodes) {
                await(() -> node.outbound.stream().anyMatch(diffusion ->
                        ObservationTopics.REPORT.equals(diffusion.topic)), Duration.ofSeconds(5));
                assertThat(node.runtime.readyCertificates(10)).isEmpty();
            }

            // Heal report diffusion in different orders. Any node, including
            // a newly selected proposer, can assemble and carry the result.
            for (int receiver = 0; receiver < nodes.size(); receiver++) {
                Node recipient = nodes.get(receiver);
                for (int offset = 0; offset < nodes.size(); offset++) {
                    Node sender = nodes.get((receiver + offset) % nodes.size());
                    sender.outbound.stream()
                            .filter(diffusion -> ObservationTopics.REPORT.equals(diffusion.topic))
                            .forEach(diffusion -> recipient.runtime.onReport(diffusion.body));
                }
            }
            for (Node node : nodes) {
                await(() -> !node.runtime.readyCertificates(10).isEmpty(), Duration.ofSeconds(5));
            }
            List<ObservationCertificate> certificates = nodes.stream()
                    .map(node -> node.runtime.readyCertificates(1).getFirst()).toList();
            assertThat(certificates).extracting(certificate ->
                    HexUtil.encodeHexString(certificate.resultId())).containsOnly(
                    HexUtil.encodeHexString(certificates.getFirst().resultId()));

            ObservationCertificate rotatedProposerCertificate = certificates.get(2);
            AppMessage result = message(rotatedProposerCertificate.encode());
            for (Node node : nodes) {
                apply(node.ledger, node.kernel, machine(), 3, List.of(result));
            }
            assertThat(nodes).extracting(node -> HexUtil.encodeHexString(node.ledger.stateRoot()))
                    .containsOnly(HexUtil.encodeHexString(nodes.getFirst().ledger.stateRoot()));
        } finally {
            for (Node node : nodes) node.close();
        }
    }

    private static SystemInputKernel kernel(ObservationSettings settings,
                                            EffectsSettings effects,
                                            AppChainConsensusProfile consensusProfile,
                                            Set<String> memberKeys) {
        List<String> ordered = memberKeys.stream().sorted().toList();
        ObservationKernel observations = new ObservationKernel(settings.profile(),
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(consensusProfile), 0,
                height -> new AppChainMembershipEpoch(0, ordered, 2),
                settings.verifierRegistry());
        return new SystemInputKernel(effects, new ConsensusProfileGuard(consensusProfile),
                new ObservationProfileGuard(settings.profile()), observations);
    }

    private static AppStateMachine machine() {
        return new AppStateMachine() {
            @Override public String id() { return "cluster-test-app"; }
            @Override public void onObservationResult(AppBlockExecutionContext context,
                                                       ObservationResult result,
                                                       AppStateWriter writer,
                                                       AppEffectEmitter effects,
                                                       AppObservationEmitter observations) {
                writer.put(new byte[]{42}, result.value());
            }
            @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
                return state.get(new byte[]{42}).orElse(new byte[0]);
            }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) {
            }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects,
                                        AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    observations.watch(ObservationIntent.oneShot("delivery", "order/42",
                            new byte[0], ObservationAnchorType.APP_HEIGHT, 2, 5, 5));
                }
            }
        };
    }

    private static void apply(AppLedgerStore ledger, SystemInputKernel kernel,
                              AppStateMachine machine, long height, List<AppMessage> messages) {
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height,
                ledger.tipHash(), 0, new byte[0], height, AppBlockCodec.messagesRoot(messages),
                new byte[32], messages, new byte[32], FinalityCert.empty());
        FxBlockApplier.applyAndCommit(ledger, kernel, machine, block);
    }

    private static AppMessage message(byte[] body) {
        byte[] sender = new byte[32];
        long expiry = 4_000_000_000L;
        return AppMessage.builder().messageId(AppMessage.computeMessageId(
                        CHAIN_ID, ObservationTopics.RESULT, sender, 1, expiry, body))
                .chainId(CHAIN_ID).topic(ObservationTopics.RESULT).sender(sender).senderSeq(1)
                .expiresAt(expiry).body(body).authScheme(0).authProof(new byte[64]).build();
    }

    private static AppChainConfig config(Set<String> members, ObservationProfileV1 profile,
                                         boolean attested, AppMessageSigner attestor) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(ObservationSettings.PROFILE_HEX, HexUtil.encodeHexString(profile.encode()));
        settings.put("observations.providers.delivery.type", attested
                ? ObservationProviders.HTTPS_ATTESTED : ObservationProviders.HTTPS_EXACT);
        settings.put("observations.providers.delivery.url", attested
                ? "https://example.com/attestation" : "https://example.com/value");
        if (attested) {
            settings.put("observations.attestors.delivery", attestor.publicKeyHex());
        } else {
            settings.put("observations.providers.delivery.source-id", "source");
        }
        return AppChainConfig.builder(CHAIN_ID).signingKeyHex(SEEDS.getFirst())
                .memberKeysHex(members)
                .proposerKeyHex(new AppMessageSigner(SEEDS.getFirst()).publicKeyHex()).threshold(2)
                .pluginSettings(settings)
                .stateCommitmentIdentity(TestStateCommitments.MPF).build();
    }

    private static ObservationDefinition definition(List<AppMessageSigner> signers,
                                                    boolean attested,
                                                    AppMessageSigner attestor) {
        return definition(signers, attested, attestor, false);
    }

    private static ObservationDefinition definition(List<AppMessageSigner> signers,
                                                    boolean attested,
                                                    AppMessageSigner attestor,
                                                    boolean networkFixture) {
        List<byte[]> reporters = signers.stream().map(AppMessageSigner::publicKey)
                .sorted(Arrays::compareUnsigned).toList();
        String adapter = attested
                ? ObservationProviders.HTTPS_ATTESTED : ObservationProviders.HTTPS_EXACT;
        byte[] sourceConfiguration = attested
                ? ObservationSourceConfiguration.attestedHttpsSourceDigest(
                "https://example.com/attestation", "GET", List.of(attestor.publicKey()))
                : ObservationSourceConfiguration.httpsSourceDigest(
                "https://example.com/value", "GET", "source", "etag");
        if (networkFixture && attested) {
            adapter = "fixture-attested-v1";
            sourceConfiguration = ObservationSourceConfiguration.attestorSetDigest(
                    List.of(attestor.publicKey()));
        } else if (networkFixture) {
            sourceConfiguration = ObservationSourceConfiguration.httpsSourceDigest(
                    LIVE_SOURCE, "GET", "source", "etag");
        }
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2), filled(3),
                filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(reporters), 0, 2, 1, false,
                adapter, sourceConfiguration, "identity-v1",
                attested ? ObservationSettings.ATTESTATION_EVIDENCE
                        : ObservationSettings.RAW_EXACT_EVIDENCE,
                ObservationSettings.EXACT_POLICY,
                filled(6), filled(7), "one-source-v1", "source-version-v1", "digest-v1",
                1, 1024, 1024, 4096, 3, 1);
    }

    private static ObservationCandidate candidate(ObservationRequest request,
                                                  ObservationDefinition definition,
                                                  boolean attested,
                                                  AppMessageSigner attestor) {
        byte[] source = "source".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "delivered".getBytes(StandardCharsets.US_ASCII);
        byte[] version = new byte[]{1};
        int anchorType = ObservationAnchorType.APP_HEIGHT.code();
        long anchor = request.round().dueAnchor();
        if (!attested) {
            return new ObservationCandidate(source, value, new byte[0], version,
                    anchorType, anchor);
        }
        ObservationAttestation unsigned = new ObservationAttestation(1, definition.digest(),
                request.round().subscriptionId(), request.round().roundNumber(),
                attestor.publicKey(), source, value, version, anchorType, anchor, new byte[64]);
        ObservationAttestation signed = new ObservationAttestation(1, definition.digest(),
                request.round().subscriptionId(), request.round().roundNumber(),
                attestor.publicKey(), source, value, version, anchorType, anchor,
                attestor.sign(unsigned.signingDigest()));
        return new ObservationCandidate(source, value, signed.encode(), version,
                anchorType, anchor);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition) {
        return new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 3, 1,
                8192, 4096, 16_384, 10, 32_768, 1, 20, 3);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition timed out");
            Thread.sleep(10);
        }
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Diffusion(String topic, byte[] body) {
        private Diffusion { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }

    private record Node(AppLedgerStore ledger, SystemInputKernel kernel,
                        ObservationRuntime runtime, List<Diffusion> outbound) implements AutoCloseable {
        @Override public void close() {
            runtime.close();
            ledger.close();
        }
    }
}
