package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
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
import com.bloxbean.cardano.yano.api.appchain.observation.ExactValueQuorumPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificateVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscription;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationKernelTest {
    private static final String CHAIN_ID = "observation-kernel-test";
    private static final String MEMBER_SEED = "21".repeat(32);

    @Test
    void createsOpensCertifiesAndDeduplicatesOneShotObservation(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine machine = machine(emitted, callbacks);

        apply(fixture, machine, 1, List.of());
        ObservationSubscription subscription = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(subscription.applicationId()).isEqualTo("test-app");
        assertThat(fixture.ledger.observationReader().activeCount()).isEqualTo(1);

        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(subscription.subscriptionId(), 0).orElseThrow();
        assertThat(round.openingHeight()).isEqualTo(2);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isEqualTo(1);

        ObservationCertificate certificate = certificate(fixture, signer, round,
                "delivered".getBytes(StandardCharsets.UTF_8));
        assertCertificateValid(fixture, signer, round, certificate);
        apply(fixture, machine, 3, List.of(resultMessage(certificate, 1)));

        ObservationSubscription completed = fixture.ledger.observationReader()
                .subscription(subscription.subscriptionId()).orElseThrow();
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(callbacks).hasValue(1);
        assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        assertThat(fixture.ledger.observationReader().openRoundCount()).isZero();

        // A canonical old certificate is an audit no-op, even with a fresh
        // member envelope and sender sequence.
        apply(fixture, machine, 4, List.of(resultMessage(certificate, 2)));
        assertThat(callbacks).hasValue(1);
        fixture.close();
    }

    @Test
    void conflictingCertificatesForActiveRoundRejectWholeBlock(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = machine(emitted, new AtomicInteger());
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(emitted.get().bytes(), 0).orElseThrow();
        ObservationCertificate left = certificate(fixture, signer, round, new byte[]{1});
        ObservationCertificate right = certificate(fixture, signer, round, new byte[]{2});
        List<ObservationCertificate> ordered = new ArrayList<>(List.of(left, right));
        ordered.sort((first, second) -> Arrays.compareUnsigned(first.resultId(), second.resultId()));

        assertThatThrownBy(() -> apply(fixture, machine, 3, List.of(
                resultMessage(ordered.get(0), 1), resultMessage(ordered.get(1), 2))))
                .hasRootCauseMessage(
                        "conflicting valid results for one active observation round");
        assertThat(fixture.ledger.tipHeight()).isEqualTo(2);
        fixture.close();
    }

    @Test
    void resultMustOccupyCanonicalRegionAndMalformedOldResultStillRejects(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = machine(emitted, new AtomicInteger());
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(emitted.get().bytes(), 0).orElseThrow();
        ObservationCertificate certificate = certificate(fixture, signer, round, new byte[]{7});

        assertThatThrownBy(() -> apply(fixture, machine, 3, List.of(
                message("ordinary", new byte[0], 1), resultMessage(certificate, 2))))
                .hasRootCauseMessage(
                        "observation results must immediately follow the canonical L1 prefix");

        apply(fixture, machine, 3, List.of(resultMessage(certificate, 3)));
        assertThatThrownBy(() -> apply(fixture, machine, 4, List.of(
                message("~obs/result/v1", new byte[]{(byte) 0xff}, 4))))
                .hasStackTraceContaining("malformed ~obs/result/v1 input");
        fixture.close();
    }

    @Test
    void timeoutExpiresOnlyAfterInclusiveInclusionGrace(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine machine = machine(emitted, callbacks);
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        for (long height = 3; height <= 7; height++) {
            apply(fixture, machine, height, List.of());
        }
        assertThat(callbacks).hasValue(0);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isEqualTo(1);

        apply(fixture, machine, 8, List.of());
        ObservationSubscription expired = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(expired.status().name()).isEqualTo("EXPIRED");
        assertThat(callbacks).hasValue(1);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isZero();
        fixture.close();
    }

    @Test
    void cancellationIsTerminalAuditOnlyAndIdempotent(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine base = machine(emitted, callbacks);
        apply(fixture, base, 1, List.of());
        AppStateMachine cancelling = new AppStateMachine() {
            @Override public String id() { return base.id(); }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) {
            }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects,
                                        AppObservationEmitter observations) {
                observations.cancel(emitted.get());
                observations.cancel(emitted.get());
            }
        };
        apply(fixture, cancelling, 2, List.of());
        ObservationSubscription cancelled = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(callbacks).hasValue(0);
        assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        fixture.close();
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer) {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(MEMBER_SEED)
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .maxBlockMessages(100)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        EffectsSettings effects = EffectsSettings.from(config);
        var consensusProfile = effects.consensusProfile(config);
        ObservationDefinition definition = definition(signer.publicKey());
        ObservationProfileV1 profile = profile(definition);
        ObservationKernel observations = new ObservationKernel(profile,
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(consensusProfile), 0,
                height -> new AppChainMembershipEpoch(0,
                        List.of(signer.publicKeyHex()), 1),
                ObservationKernel.exactValueRegistry(
                        (ignoredDefinition, ignoredRound, report) ->
                                report.evidence().length == 0));
        SystemInputKernel kernel = new SystemInputKernel(effects,
                new ConsensusProfileGuard(consensusProfile),
                new ObservationProfileGuard(profile), observations);
        AppLedgerStore ledger = new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(ObservationKernelTest.class), TestStateCommitments.MPF);
        return new Fixture(ledger, kernel, definition, profile, consensusProfile);
    }

    private static AppStateMachine machine(AtomicReference<ObservationSubscriptionId> emitted,
                                           AtomicInteger callbacks) {
        return new AppStateMachine() {
            @Override public String id() { return "test-app"; }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects) {
            }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects, AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    emitted.set(observations.watch(ObservationIntent.oneShot(
                            "delivery", "order/42", new byte[]{4, 2},
                            ObservationAnchorType.APP_HEIGHT, 2, 4, 4)));
                }
            }

            @Override
            public void onObservationResult(AppBlockExecutionContext context,
                                            ObservationResult result,
                                            AppStateWriter writer,
                                            AppEffectEmitter effects,
                                            AppObservationEmitter observations) {
                callbacks.incrementAndGet();
                writer.put("app/result".getBytes(StandardCharsets.US_ASCII), result.value());
            }
        };
    }

    private static void apply(Fixture fixture, AppStateMachine machine,
                              long height, List<AppMessage> messages) {
        byte[] previous = fixture.ledger.tipHash();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height,
                previous, 0, new byte[0], height,
                AppBlockCodec.messagesRoot(messages), new byte[32], messages,
                new byte[32], FinalityCert.empty());
        FxBlockApplier.applyAndCommit(fixture.ledger, fixture.kernel, machine, block);
    }

    private static ObservationCertificate certificate(Fixture fixture,
                                                      AppMessageSigner signer,
                                                      ObservationRound round,
                                                      byte[] value) {
        ObservationReport unsigned = new ObservationReport(1,
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(fixture.consensusProfile),
                fixture.profile.digest(), fixture.definition.digest(),
                round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                round.reporterSetDigest(), signer.publicKey(), new byte[]{9}, value,
                new byte[0], new byte[]{1}, 0, 1, new byte[64]);
        ObservationReport report = new ObservationReport(1,
                unsigned.chainGenesisId(), unsigned.chainId(), unsigned.consensusProfileDigest(),
                unsigned.observationProfileDigest(), unsigned.definitionDigest(),
                unsigned.subscriptionId(), unsigned.roundNumber(), unsigned.membershipDigest(),
                unsigned.reporterSetDigest(), unsigned.reporterPublicKey(), unsigned.sourceId(),
                unsigned.value(), unsigned.evidence(), unsigned.sourceVersion(),
                unsigned.freshnessAnchorType(), unsigned.freshnessAnchor(),
                signer.sign(unsigned.signingDigest()));
        byte[] resultId = ObservationHashes.resultId(round.subscriptionId(), round.roundNumber(),
                round.definitionDigest(),
                ObservationResultStatus.VALUE,
                ObservationHashes.digest(value));
        return new ObservationCertificate(1, round.subscriptionId(), round.roundNumber(),
                round.membershipDigest(), round.definitionDigest(), round.policyDigest(),
                round.sourceSetDigest(), List.of(report), value, new byte[0], resultId);
    }

    private static void assertCertificateValid(Fixture fixture, AppMessageSigner signer,
                                               ObservationRound round,
                                               ObservationCertificate certificate) {
        ObservationReport report = certificate.reports().getFirst();
        assertThat(AppMessageSigner.verify(report.signature(), report.signingDigest(),
                report.reporterPublicKey())).as("report signature").isTrue();
        var validation = ObservationCertificateVerifier.validate(
                fixture.definition, round, certificate,
                fixture.profile, TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(fixture.consensusProfile),
                List.of(signer.publicKey()), (publicKey, digest, signature) ->
                        AppMessageSigner.verify(signature, digest, publicKey),
                (definition, candidateRound, candidateReport) -> true,
                new ExactValueQuorumPolicy());
        assertThat(validation.valid()).as(validation.reason()).isTrue();
    }

    private static ObservationDefinition definition(byte[] reporter) {
        byte[] reporterSet = ObservationHashes.reporterSetDigest(List.of(reporter));
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2),
                filled(3), filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                reporterSet, 0, 1, 1, false, "test", filled(5), "identity-v1",
                ObservationSettings.RAW_EXACT_EVIDENCE, ObservationSettings.EXACT_POLICY,
                filled(6), filled(7), "one-source-v1", "source-version-v1",
                "digest-v1", 1, 1024, 1024, 1024, 1, 1);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition) {
        return new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 10, 100, 10, 100, 1, 1,
                4096, 1024, 8192, 10, 16_384, 1, 20, 3);
    }

    private static AppMessage resultMessage(ObservationCertificate certificate, long sequence) {
        return message("~obs/result/v1", certificate.encode(), sequence);
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

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Fixture(AppLedgerStore ledger, SystemInputKernel kernel,
                           ObservationDefinition definition, ObservationProfileV1 profile,
                           AppChainConsensusProfile consensusProfile) implements AutoCloseable {
        @Override public void close() { ledger.close(); }
    }
}
