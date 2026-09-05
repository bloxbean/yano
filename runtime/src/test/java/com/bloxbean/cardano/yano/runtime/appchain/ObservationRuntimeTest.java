package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCandidate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRequest;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSourceConfiguration;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationRuntimeTest {
    private static final String CHAIN_ID = "observation-runtime-test";
    private static final String MEMBER_SEED = "51".repeat(32);

    @Test
    void incompatibleGovernedMembershipActivationIsVoid() {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        MemberGroup members = new MemberGroup(Set.of(signer.publicKeyHex()), 1);
        ObservationSettings settings = ObservationSettings.from(config(signer,
                profile(definition(signer.publicKey()))), members);
        GovernedMembership governance = new GovernedMembership(members, null, 600,
                LoggerFactory.getLogger(ObservationRuntimeTest.class));
        governance.setEpochGuard(effect -> settings.admitsMembership(effect.members(), effect.threshold()));
        byte[] body = GovernedMembership.encodeCommand(GovernedMembership.OP_ADD,
                new AppMessageSigner("52".repeat(32)).publicKey(), 0, 1);
        AppMessage command = AppMessage.builder().messageId(AppMessage.computeMessageId(CHAIN_ID,
                        GovernedMembership.TOPIC, signer.publicKey(), 1, Long.MAX_VALUE, body))
                .chainId(CHAIN_ID).topic(GovernedMembership.TOPIC).sender(signer.publicKey())
                .senderSeq(1).expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[64]).build();
        List<AppMessage> messages = List.of(command);
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, 1, new byte[32], 0,
                new byte[0], 1, AppBlockCodec.messagesRoot(messages), new byte[32], messages,
                signer.publicKey(), FinalityCert.empty());
        assertThat(governance.processBlock(block).effects()).isEmpty();
        assertThat(members.membersAt(2)).containsExactly(signer.publicKeyHex());
    }

    @Test
    void retriesTransientFailureRestoresReadyCertificateAndPrunesAfterFinality(
            @TempDir Path directory) throws Exception {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        MemberGroup members = new MemberGroup(Set.of(signer.publicKeyHex()), 1);
        ObservationDefinition definition = definition(signer.publicKey());
        ObservationProfileV1 profile = profile(definition);
        AppChainConfig config = config(signer, profile);
        ObservationSettings settings = ObservationSettings.from(config, members);
        EffectsSettings effects = EffectsSettings.from(config);
        AppChainConsensusProfile consensusProfile = effects.consensusProfile(config);
        SystemInputKernel kernel = kernel(settings, effects, consensusProfile);
        AppStateMachine machine = machine();
        Path ledgerPath = directory.resolve("ledger");
        List<byte[]> diffusedReports = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean providerClosed = new AtomicBoolean();

        try (AppLedgerStore ledger = ledger(ledgerPath)) {
            apply(ledger, kernel, machine, 1, List.of());
            apply(ledger, kernel, machine, 2, List.of());
            ObservationProvider flaky = request -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient provider crash");
                }
                return new ObservationCandidate("source".getBytes(StandardCharsets.US_ASCII),
                        new byte[]{7}, new byte[0],
                        new byte[]{1}, ObservationAnchorType.APP_HEIGHT.code(),
                        request.round().dueAnchor());
            };
            ObservationProvider tracked = new ObservationProvider() {
                @Override public ObservationCandidate acquire(
                        ObservationRequest request) throws Exception {
                    return flaky.acquire(request);
                }

                @Override public void close() { providerClosed.set(true); }
            };
            try (ObservationRuntime runtime = runtime(settings, tracked, ledger, signer, members,
                    consensusProfile, diffusedReports)) {
                runtime.tick();
                await(() -> attempts.get() == 1
                        && runtime.status().get("acquisitionFailures") == 1, Duration.ofSeconds(5));
                assertThat(runtime.readyCertificates(10)).isEmpty();

                runtime.start();
                await(() -> !runtime.readyCertificates(10).isEmpty(), Duration.ofSeconds(5));
                assertThat(attempts).hasValue(2);
                assertThat(diffusedReports).isNotEmpty();
            }
            assertThat(providerClosed).isTrue();
        }

        AtomicInteger restartedAttempts = new AtomicInteger();
        try (AppLedgerStore reopened = ledger(ledgerPath);
             ObservationRuntime restarted = runtime(settings,
                     request -> {
                         restartedAttempts.incrementAndGet();
                         throw new AssertionError("durable report must suppress double signing");
                     }, reopened, signer, members, consensusProfile, new CopyOnWriteArrayList<>())) {
            assertThat(restarted.readyCertificates(10)).hasSize(1);
            restarted.tick();
            await(() -> restarted.status().get("journalEntries") > 0,
                    Duration.ofSeconds(1));
            assertThat(restartedAttempts).hasValue(0);

            ObservationCertificate certificate = restarted.readyCertificates(1).getFirst();
            apply(reopened, kernel, machine, 3,
                    List.of(message(ObservationTopics.RESULT, certificate.encode(), 1)));
            restarted.tick();
            assertThat(restarted.readyCertificates(10)).isEmpty();
            assertThat(restarted.status().get("journalEntries")).isZero();
        }
    }

    private static ObservationRuntime runtime(ObservationSettings settings,
                                              ObservationProvider provider,
                                              AppLedgerStore ledger,
                                              AppMessageSigner signer,
                                              MemberGroup members,
                                              AppChainConsensusProfile consensusProfile,
                                              List<byte[]> diffusedReports) {
        return new ObservationRuntime(settings,
                ObservationProviders.of(Map.of("delivery", provider)), ledger, signer, members,
                CHAIN_ID, TestStateCommitments.MPF.genesisId(),
                AppChainConsensusProfileCommitment.digest(consensusProfile),
                (topic, body) -> {
                    if (ObservationTopics.REPORT.equals(topic)) diffusedReports.add(body);
                }, 2, 1000, 1024 * 1024,
                LoggerFactory.getLogger(ObservationRuntimeTest.class));
    }

    private static SystemInputKernel kernel(ObservationSettings settings,
                                            EffectsSettings effects,
                                            AppChainConsensusProfile consensusProfile) {
        ObservationKernel observations = new ObservationKernel(settings.profile(),
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(consensusProfile), 0,
                height -> new AppChainMembershipEpoch(0,
                        List.of(new AppMessageSigner(MEMBER_SEED).publicKeyHex()), 1),
                settings.verifierRegistry());
        return new SystemInputKernel(effects, new ConsensusProfileGuard(consensusProfile),
                new ObservationProfileGuard(settings.profile()), observations);
    }

    private static AppStateMachine machine() {
        return new AppStateMachine() {
            @Override public String id() { return "runtime-test-app"; }

            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) {
            }

            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects,
                                        AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    observations.watch(ObservationIntent.oneShot("delivery", "order/7",
                            new byte[0], ObservationAnchorType.APP_HEIGHT, 2, 4, 4));
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

    private static AppMessage message(String topic, byte[] body, long sequence) {
        byte[] sender = new byte[32];
        long expiry = 4_000_000_000L;
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(
                        CHAIN_ID, topic, sender, sequence, expiry, body))
                .chainId(CHAIN_ID).topic(topic).sender(sender).senderSeq(sequence)
                .expiresAt(expiry).body(body).authScheme(0).authProof(new byte[64]).build();
    }

    private static AppChainConfig config(AppMessageSigner signer, ObservationProfileV1 profile) {
        return AppChainConfig.builder(CHAIN_ID).signingKeyHex(MEMBER_SEED)
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .pluginSettings(Map.of(ObservationSettings.PROFILE_HEX,
                        HexUtil.encodeHexString(profile.encode()),
                        "observations.providers.delivery.type", ObservationProviders.HTTPS_EXACT,
                        "observations.providers.delivery.url", "https://example.com/value",
                        "observations.providers.delivery.source-id", "source"))
                .stateCommitmentIdentity(TestStateCommitments.MPF).build();
    }

    private static ObservationDefinition definition(byte[] reporter) {
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2), filled(3),
                filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(List.of(reporter)), 0, 1, 1, false,
                ObservationProviders.HTTPS_EXACT, ObservationSourceConfiguration.httpsSourceDigest(
                "https://example.com/value", "GET", "source", "etag"), "identity-v1",
                ObservationSettings.RAW_EXACT_EVIDENCE, ObservationSettings.EXACT_POLICY,
                filled(6), filled(7), "one-source-v1", "source-version-v1", "digest-v1",
                1, 1024, 1024, 1024, 1, 1);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition) {
        return new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 1, 1,
                4096, 1024, 8192, 10, 16_384, 1, 20, 3);
    }

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(),
                LoggerFactory.getLogger(ObservationRuntimeTest.class), TestStateCommitments.MPF);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before timeout");
            }
            Thread.sleep(10);
        }
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
