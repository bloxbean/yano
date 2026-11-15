package org.yanoproject.runtime.appchain;

import org.yanoproject.x.framework.cardano.AppChainSubsystem;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.yanoproject.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.config.AppChainConfigParser;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.observation.AppObservationEmitter;
import org.yanoproject.x.framework.api.appchain.observation.CompleteSourceMedianPolicy;
import org.yanoproject.x.framework.api.appchain.observation.ObservationFixedPoint;
import org.yanoproject.x.framework.api.appchain.observation.ObservationReport;
import org.yanoproject.x.framework.api.appchain.observation.ObservationRound;
import org.yanoproject.x.framework.api.appchain.observation.ObservationAnchorType;
import org.yanoproject.x.framework.api.appchain.observation.ObservationAttestation;
import org.yanoproject.x.framework.api.appchain.observation.ObservationCandidate;
import org.yanoproject.x.framework.api.appchain.observation.ObservationCertificate;
import org.yanoproject.x.framework.api.appchain.observation.ObservationDefinition;
import org.yanoproject.x.framework.api.appchain.observation.ObservationHashes;
import org.yanoproject.x.framework.api.appchain.observation.ObservationIntent;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProvider;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProviderFactory;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.observation.ObservationReporterMode;
import org.yanoproject.x.framework.api.appchain.observation.ObservationRequest;
import org.yanoproject.x.framework.api.appchain.observation.ObservationResult;
import org.yanoproject.x.framework.api.appchain.observation.ObservationSourceConfiguration;
import org.yanoproject.x.framework.api.appchain.observation.ObservationTopics;
import org.yanoproject.x.framework.api.appchain.observation.ObservationSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.math.BigInteger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
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
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationRuntimeClusterTest {
    private static final String CHAIN_ID = "observation-cluster-test";
    private static final String LIVE_SOURCE = "https://raw.githubusercontent.com/bloxbean/yano/"
            + "ed5de419da68b524bdad2a3848c3156c419d0476/gradle/wrapper/gradle-wrapper.properties";
    private static final List<String> SEEDS = List.of(
            "61".repeat(32), "62".repeat(32), "63".repeat(32));

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
        networkCluster(directory, attested, false);
    }

    @Test
    void externalMedianFinalizesThroughFiveNetworkedValidators(@TempDir Path directory) throws Exception {
        networkCluster(directory, false, true);
    }

    private static void networkCluster(Path directory, boolean attested, boolean external) throws Exception {
        networkCluster(directory, attested, external, false);
    }

    @Test
    void honestProposerIncludesRetainedCertificateAfterFaultyNetworkedProposerOmitsIt(
            @TempDir Path directory) throws Exception {
        networkCluster(directory, false, true, true);
    }

    private static void networkCluster(Path directory, boolean attested, boolean external,
                                       boolean omitCertificate) throws Exception {
        List<String> seeds = external ? List.of("61".repeat(32), "62".repeat(32), "63".repeat(32),
                "64".repeat(32), "65".repeat(32)) : SEEDS;
        List<SignerProvider> signers = seeds.stream().map(ObservationRuntimeClusterTest::signer).toList();
        List<SignerProvider> reporters = IntStream.range(71, 76)
                .mapToObj(seed -> signer(Integer.toHexString(seed).repeat(32))).toList();
        CompleteSourceMedianPolicy.Parameters parameters = aggregateParameters();
        SignerProvider attestor = signer("64".repeat(32));
        Set<String> members = Set.copyOf(signers.stream()
                .map(SignerProvider::publicKeyHex).toList());
        ObservationDefinition definition = external ? externalDefinition(reporters, parameters)
                : definition(signers, attested, attestor, true);
        ObservationProfileV1 profile = external
                ? new ObservationProfileV1(1, true, 1, 2, 1, 1, 2, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 15, 3,
                4096, 1024, 16_384, 10, 32_768, 1, 20, 3) : profile(definition);
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
        for (int index = 0; index < seeds.size(); index++) {
            try (ServerSocket socket = new ServerSocket(0)) { ports.add(socket.getLocalPort()); }
        }
        List<AppChainSubsystem> subsystems = new ArrayList<>();
        List<NodeServer> servers = new ArrayList<>();
        try {
            for (int index = 0; index < seeds.size(); index++) {
                int port = ports.get(index);
                Map<String, String> providerSettings = new LinkedHashMap<>();
                providerSettings.put("observations.profile-cbor-hex",
                        HexUtil.encodeHexString(profile.encode()));
                providerSettings.put("observations.providers.delivery.type",
                        attested ? factory.type() : "https-exact-v1");
                if (external) {
                    providerSettings.put("observations.reporters.delivery", reporters.stream()
                            .map(SignerProvider::publicKeyHex).collect(Collectors.joining(",")));
                    providerSettings.put("observations.policy.delivery", HexUtil.encodeHexString(parameters.encode()));
                    providerSettings.put("consensus.max-byzantine-members", "1");
                } else if (attested) {
                    providerSettings.put("observations.attestors.delivery", attestor.publicKeyHex());
                    providerSettings.put("observations.providers.delivery.source-id", "source");
                } else {
                    providerSettings.put("observations.providers.delivery.url", LIVE_SOURCE);
                    providerSettings.put("observations.providers.delivery.source-id", "source");
                }
                // Exercise the ordinary configuration boundary, not direct pluginSettings injection.
                Map<String, Object> settings = new LinkedHashMap<>(providerSettings);
                settings.put("chain-id", CHAIN_ID);
                settings.put("signing-key", seeds.get(index));
                settings.put("members", String.join(",", members));
                settings.put("threshold", external ? 4 : 2);
                settings.put("sequencer.proposer", signers.getFirst().publicKeyHex());
                settings.put("block.interval-ms", 300);
                // Keep the production round timeout: network setup and report collection
                // must not rotate away from the designated faulty proposer before omission.
                settings.put("peers", ports.stream().filter(candidate -> candidate != port)
                        .map(candidate -> "localhost:" + candidate).collect(Collectors.joining(",")));
                settings.putAll(TestStateCommitments.MPF.settings());
                AppChainConfig config = AppChainConfigParser.parse(settings);
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
                return connected instanceof Map<?, ?> peers && peers.size() == seeds.size() - 1
                        && peers.values().stream().allMatch(Boolean.TRUE::equals);
            }), Duration.ofSeconds(30));
            proposer.submit("open", new byte[]{1});
            await(() -> subsystems.stream().allMatch(node -> node.tipHeight() >= 1),
                    Duration.ofSeconds(30));
            proposer.submit("due", new byte[]{2});
            if (external) {
                await(() -> subsystems.stream().allMatch(node -> node.tipHeight() >= 2), Duration.ofSeconds(30));
                byte[] genesisId = proposer.stateCommitmentIdentity().orElseThrow().genesisId();
                byte[] subscriptionId = ObservationHashes.subscriptionId(genesisId,
                        1, 0, definition.digest(), new byte[0]);
                ObservationRound round = ObservationRound.decode(proposer.query("yano/observations/round",
                        ByteBuffer.allocate(40).put(subscriptionId).putLong(0).array()).payload());
                byte[] consensusDigest = HexUtil.decodeHexString((String)
                        ((Map<?, ?>) proposer.status().get("consensusProfile")).get("digest"));
                if (omitCertificate) {
                    // Fault injection is test-only. The runtime still acquires, validates and
                    // retains certificates; only this proposer's selection deliberately omits them.
                    omitObservationResults(proposer);
                }
                // Three independent source quorums enter through non-proposer gateways.
                for (int source = 0; source < 3; source++) {
                    for (int reporter = 0; reporter < 4; reporter++) {
                        SignerProvider key = reporters.get(reporter);
                        ObservationReport unsigned = externalReport(round, profile, consensusDigest, genesisId, key, source,
                                new byte[64]);
                        subsystems.get(1 + reporter).submitObservationReport(externalReport(round, profile,
                                consensusDigest, genesisId, key, source, key.sign(unsigned.signingDigest())).encode());
                    }
                }
            }
            long resultHeight = 3;
            if (omitCertificate) {
                await(() -> subsystems.stream().allMatch(node ->
                        node.status().get("genericObservations") instanceof Map<?, ?> resources
                                && resources.get("certificatesReady") instanceof Number count
                                && count.intValue() > 0), Duration.ofSeconds(30));
                assertThat(subsystems).allMatch(node -> node.tipHeight() == 2);
                proposer.submit("omit-ready-certificate", new byte[]{3});
                await(() -> subsystems.stream().allMatch(node -> node.tipHeight() == 3),
                        Duration.ofSeconds(30));
                for (AppChainSubsystem node : subsystems) {
                    AppBlock omitted = node.block(3).orElseThrow();
                    assertThat(omitted.cert().signatures()).hasSize(4);
                    assertThat(omitted.proposer()).isEqualTo(signers.getFirst().publicKey());
                    assertThat(omitted.messages()).noneMatch(message ->
                            ObservationTopics.RESULT.equals(message.getTopic()));
                    assertThat(node.query("result", new byte[0]).payload()).isEmpty();
                }
                // A single faulty validator may now fail-stop. Four honest validators must
                // recover through certified view change without re-signing external reports.
                proposer.stop();
                await(() -> subsystems.subList(1, 5).stream().allMatch(node -> node.tipHeight() >= 4),
                        Duration.ofSeconds(60));
                AppBlock recovered = subsystems.get(1).block(4).orElseThrow();
                assertThat(recovered.view()).isPositive();
                assertThat(recovered.proposer()).isNotEqualTo(signers.getFirst().publicKey());
                proposer.start();
                resultHeight = 4;
            }
            long expectedHeight = resultHeight;
            await(() -> subsystems.stream().allMatch(node -> node.tipHeight() >= expectedHeight),
                    Duration.ofSeconds(30));
            AppBlock finalized = proposer.block(resultHeight).orElseThrow();
            assertThat(finalized.messages()).anyMatch(message ->
                    ObservationTopics.RESULT.equals(message.getTopic()));
            ObservationCertificate finalizedObservation = ObservationCertificate.decode(finalized.messages().stream()
                    .filter(message -> ObservationTopics.RESULT.equals(message.getTopic()))
                    .findFirst().orElseThrow().getBody());
            byte[] output = finalizedObservation.output();
            if (external) {
                assertThat(ObservationFixedPoint.decode(output).units()).isEqualTo(BigInteger.valueOf(501000));
                assertThat(finalizedObservation.reports()).hasSize(12);
            } else {
                assertThat(new String(output, StandardCharsets.US_ASCII))
                        .contains(attested ? "delivered" : "distributionUrl=");
            }
            for (AppChainSubsystem node : subsystems) {
                AppBlock block = node.block(resultHeight).orElseThrow();
                assertThat(block.cert().signatures()).hasSize(external ? 4 : 2);
                assertThat(block.stateRoot()).isEqualTo(finalized.stateRoot());
                assertThat(node.query("result", new byte[0]).payload())
                        .isEqualTo(output);
                var audit = node.query("yano/observations/result", finalizedObservation.resultId());
                assertThat(audit.stateRoot()).isEqualTo(finalized.stateRoot());
                assertThat(ObservationResult.decode(audit.payload()).value()).isEqualTo(output);
                if (external) {
                    assertThat(ObservationResult.decode(audit.payload()).reporterCount()).isEqualTo(4);
                    assertThat(ObservationResult.decode(audit.payload()).sourceCount()).isEqualTo(3);
                }
                assertThat(ObservationSubscription.decode(node.query("yano/observations/subscription",
                        finalizedObservation.subscriptionId()).payload()).status().name()).isEqualTo("COMPLETED");
                assertThat(node.status()).containsKey("genericObservations");
            }
        } finally {
            for (AppChainSubsystem node : subsystems) node.close();
            for (NodeServer server : servers) server.shutdown();
        }
    }

    private static CompleteSourceMedianPolicy.Parameters aggregateParameters() {
        return new CompleteSourceMedianPolicy.Parameters(6, 2, 100_000, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.valueOf(10_000_000), IntStream.range(0, 3)
                .mapToObj(i -> new CompleteSourceMedianPolicy.Source(filled(i), filled(i))).toList());
    }

    private static ObservationDefinition externalDefinition(List<SignerProvider> reporters,
                                                            CompleteSourceMedianPolicy.Parameters parameters) {
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2), filled(3), filled(4),
                ObservationReporterMode.EXTERNAL_REPORTERS,
                ObservationHashes.reporterSetDigest(reporters.stream().map(SignerProvider::publicKey).toList()),
                1, 4, 3, true, "external-reporters-v1", parameters.sourceSetDigest(),
                "fixed-point-i128-v1", "external-reporter-claim-v1", CompleteSourceMedianPolicy.ID,
                parameters.digest(), filled(7), "pinned-groups-v1", "round-window-v1", "digest-v1",
                1, 1024, 18, 0, 15, 3);
    }

    private static ObservationReport externalReport(ObservationRound round, ObservationProfileV1 profile,
                                                     byte[] consensusDigest, byte[] genesisId, SignerProvider reporter,
                                                     int source, byte[] signature) {
        return new ObservationReport(1, genesisId, CHAIN_ID, consensusDigest,
                profile.digest(), round.definitionDigest(), round.subscriptionId(), round.roundNumber(),
                round.membershipDigest(), round.reporterSetDigest(), reporter.publicKey(), filled(source),
                new ObservationFixedPoint(BigInteger.valueOf(500000 + source * 1000L), 6).encode(),
                new byte[0], new byte[]{1}, round.anchorType().code(), round.dueAnchor(), signature);
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

    private static ObservationDefinition definition(List<SignerProvider> signers,
                                                    boolean attested,
                                                    SignerProvider attestor,
                                                    boolean networkFixture) {
        List<byte[]> reporters = signers.stream().map(SignerProvider::publicKey)
                .sorted(Arrays::compareUnsigned).toList();
        String adapter = attested
                ? "https-attested-v1" : "https-exact-v1";
        byte[] sourceConfiguration = attested
                ? ObservationSourceConfiguration.attestedHttpsSourceDigest(
                "https://example.com/attestation", "GET", "source", List.of(attestor.publicKey()))
                : ObservationSourceConfiguration.httpsSourceDigest(
                "https://example.com/value", "GET", "source", "etag");
        if (networkFixture && attested) {
            adapter = "fixture-attested-v1";
            sourceConfiguration = ObservationSourceConfiguration.attestedSourceDigest(
                    "source", List.of(attestor.publicKey()));
        } else if (networkFixture) {
            sourceConfiguration = ObservationSourceConfiguration.httpsSourceDigest(
                    LIVE_SOURCE, "GET", "source", "etag");
        }
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2), filled(3),
                filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(reporters), 0, 2, 1, false,
                adapter, sourceConfiguration, "identity-v1",
                attested ? "ed25519-attestation-v1"
                        : "raw-exact-v1",
                "exact-value-quorum-v1",
                filled(6), filled(7), "one-source-v1", "source-version-v1", "digest-v1",
                1, 1024, 1024, 4096, 3, 1);
    }

    private static ObservationCandidate candidate(ObservationRequest request,
                                                  ObservationDefinition definition,
                                                  boolean attested,
                                                  SignerProvider attestor) {
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

    // This fixture implements the public signing contract, without exposing the
    // framework's seed-backed implementation constructor solely to host tests.
    private static SignerProvider signer(String seedHex) {
        byte[] seed = HexUtil.decodeHexString(seedHex);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        return new SignerProvider() {
            @Override public byte[] publicKey() { return publicKey.clone(); }
            @Override public byte[] sign(byte[] message) {
                return CryptoConfiguration.INSTANCE.getSigningProvider().sign(message, seed);
            }
        };
    }

    private static void omitObservationResults(AppChainSubsystem proposer) throws Exception {
        // Deliberately retain the existing test-only fault injection. The callback
        // remains an internal core type; ordinary host integration uses public APIs.
        Field engineField = AppLedgerRuntime.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        Object engine = engineField.get(proposer);
        Method setter = Arrays.stream(engine.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals("setObservationResultInputProvider"))
                .findFirst().orElseThrow();
        Class<?> callback = setter.getParameterTypes()[0];
        Object omission = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("inputs")) {
                        throw new AssertionError("Unexpected observation callback: " + method);
                    }
                    return List.of();
                });
        setter.setAccessible(true);
        setter.invoke(engine, omission);
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

}
