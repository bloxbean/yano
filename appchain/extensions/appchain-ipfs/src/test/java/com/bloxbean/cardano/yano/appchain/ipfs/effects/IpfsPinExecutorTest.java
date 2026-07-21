package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.IpfsPinDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.ipfs.config.IpfsEffectConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.CID;
import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.REPLICATION_POLICY;
import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.TARGET_ALIAS;
import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.command;
import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.context;
import static com.bloxbean.cardano.yano.appchain.ipfs.effects.IpfsEffectTestSupport.effect;
import static org.assertj.core.api.Assertions.assertThat;

class IpfsPinExecutorTest {

    @Test
    void pinsOnceReprobesAndArchivesReceiptBoundDetail() throws Exception {
        IpfsEffectTestSupport.RecordingClient client = client(PinState.ABSENT);
        IpfsEffectTestSupport.MemoryDetailArchive archive =
                new IpfsEffectTestSupport.MemoryDetailArchive();
        PendingEffect pending = effect(command().encode());

        try (IpfsPinExecutor executor = executor(client, archive)) {
            EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) executor.execute(
                    context(1), pending);

            IpfsPinReceiptV1 receipt = IpfsPinReceiptV1.decode(confirmed.externalRef());
            assertThat(receipt.targetFingerprint()).isEqualTo(IpfsEffectTestSupport.config()
                    .target(TARGET_ALIAS).orElseThrow().targetFingerprint().bytes());
            assertThat(receipt.cidFingerprint())
                    .isEqualTo(IpfsCidFingerprint.compute(CID).bytes());
            assertThat(client.probes()).isEqualTo(2);
            assertThat(client.adds()).isOne();
            assertThat(client.state()).isEqualTo(PinState.RECURSIVE);
            assertThat(client.observedKeys())
                    .allSatisfy(key -> assertThat(key).isEqualTo(pending.idHash()));

            byte[] detailBytes = archive.bytes(confirmed.detailHash()).orElseThrow();
            ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.decode(detailBytes);
            assertThat(document.effectIdHash()).isEqualTo(pending.idHash());
            IpfsPinDetailV1 detail = (IpfsPinDetailV1) document.data();
            assertThat(detail.targetFingerprint()).isEqualTo(receipt.targetFingerprint());
            assertThat(detail.cid()).isEqualTo(CID);
            assertThat(detail.recursive()).isTrue();
            assertThat(detail.providerReference()).isNull();
        }
        assertThat(client.closeCalls()).isOne();
    }

    @Test
    void exactOrStrongerExplicitPinConfirmsWithoutMutation() {
        for (PinState state : List.of(PinState.DIRECT, PinState.RECURSIVE)) {
            IpfsEffectTestSupport.RecordingClient client = client(state);
            IpfsPinCommandV1 direct = command(CID, false, REPLICATION_POLICY);
            try (IpfsPinExecutor executor = executor(
                    IpfsEffectTestSupport.config(false), client, null)) {
                assertThat(executor.execute(context(1), effect(direct.encode())))
                        .isInstanceOf(EffectExecution.Confirmed.class);
                assertThat(client.probes()).isOne();
                assertThat(client.adds()).isEqualTo(0);
            }
        }
    }

    @Test
    void weakerOrIndirectStateIsUpgradedByOneExplicitPin() {
        for (PinState state : List.of(PinState.ABSENT, PinState.DIRECT, PinState.INDIRECT)) {
            IpfsEffectTestSupport.RecordingClient client = client(state);
            try (IpfsPinExecutor executor = executor(client, null)) {
                assertThat(executor.execute(context(1), effect(command().encode())))
                        .isInstanceOf(EffectExecution.Confirmed.class);
                assertThat(client.adds()).isOne();
                assertThat(client.state()).isEqualTo(PinState.RECURSIVE);
            }
        }
    }

    @Test
    void unknownAcknowledgementReconcilesOnlyObservedState() {
        IpfsEffectTestSupport.RecordingClient committed = client(PinState.ABSENT);
        committed.failNextAdd(new IpfsProviderException(ConnectorErrorCode.ACK_UNKNOWN), true);
        try (IpfsPinExecutor executor = executor(committed, null)) {
            assertThat(executor.execute(context(1), effect(command().encode())))
                    .isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(committed.adds()).isOne();
        }

        IpfsEffectTestSupport.RecordingClient absent = client(PinState.ABSENT);
        absent.failNextAdd(new IpfsProviderException(ConnectorErrorCode.ACK_UNKNOWN), false);
        try (IpfsPinExecutor executor = executor(absent, null)) {
            assertFailure(executor.execute(context(1), effect(command().encode())),
                    ConnectorErrorCode.ACK_UNKNOWN);
            assertThat(absent.adds()).isOne();
        }
    }

    @Test
    void archiveFailurePersistsReceiptAndResumeNeverPinsAgain() {
        IpfsEffectTestSupport.RecordingClient client = client(PinState.ABSENT);
        IpfsEffectTestSupport.MemoryDetailArchive archive =
                new IpfsEffectTestSupport.MemoryDetailArchive();
        archive.failWith(new IOException("archive unavailable"));
        PendingEffect pending = effect(command().encode());

        try (IpfsPinExecutor executor = executor(client, archive)) {
            EffectExecution.Submitted first = (EffectExecution.Submitted) executor.execute(
                    context(1), pending);
            IpfsPinReceiptV1.decode(first.externalRef());
            assertThat(client.adds()).isOne();

            assertFailure(executor.execute(context(2, first.externalRef()), pending),
                    ConnectorErrorCode.DETAIL_ARCHIVE_FAILED);
            assertThat(client.adds()).isOne();

            archive.clearFailure();
            EffectExecution.Confirmed recovered = (EffectExecution.Confirmed) executor.execute(
                    context(3, first.externalRef()), pending);
            assertThat(recovered.detailHash()).hasSize(32);
            assertThat(client.adds()).isOne();
        }
    }

    @Test
    void submittedReceiptIsStrictAndAuthorizesProbeOnly() {
        IpfsEffectTestSupport.RecordingClient client = client(PinState.RECURSIVE);
        IpfsEffectConfig.Target target = IpfsEffectTestSupport.config()
                .target(TARGET_ALIAS).orElseThrow();
        IpfsPinReceiptV1 receipt = new IpfsPinReceiptV1(target.targetFingerprint(),
                IpfsCidFingerprint.compute(CID));
        PendingEffect pending = effect(command().encode());

        try (IpfsPinExecutor executor = executor(client, null)) {
            assertThat(executor.execute(context(2, receipt.encode()), pending))
                    .isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(client.probes()).isOne();
            assertThat(client.adds()).isEqualTo(0);

            assertFailure(executor.execute(context(3, new byte[]{1, 2, 3}), pending),
                    ConnectorErrorCode.INTERNAL_ERROR);
            IpfsPinReceiptV1 changed = new IpfsPinReceiptV1(new byte[32],
                    IpfsCidFingerprint.compute(CID).bytes());
            assertFailure(executor.execute(context(4, changed.encode()), pending),
                    ConnectorErrorCode.TARGET_CHANGED);
            assertThat(client.probes()).isOne();
            assertThat(client.adds()).isEqualTo(0);
        }

        IpfsEffectTestSupport.RecordingClient missing = client(PinState.ABSENT);
        try (IpfsPinExecutor executor = executor(missing, null)) {
            assertFailure(executor.execute(context(2, receipt.encode()), pending),
                    ConnectorErrorCode.ACK_UNKNOWN);
            assertThat(missing.adds()).isEqualTo(0);
        }
    }

    @Test
    void malformedIdentityContextAndPoliciesFailBeforeProviderIo() {
        AtomicInteger opens = new AtomicInteger();
        try (IpfsPinExecutor executor = new IpfsPinExecutor(IpfsEffectTestSupport.config(),
                ignored -> () -> {
                    opens.incrementAndGet();
                    return client(PinState.ABSENT);
                }, null)) {
            assertFailure(executor.execute(context(1), effect(new byte[]{0})),
                    ConnectorErrorCode.INVALID_PAYLOAD);
            assertFailure(executor.execute(context(1), effect("other.type",
                            command().encode(), null)), ConnectorErrorCode.INVALID_PAYLOAD);
            assertFailure(executor.execute(context(1), effect(IpfsPinExecutor.TYPE,
                            command().encode(), new byte[32])), ConnectorErrorCode.INVALID_PAYLOAD);
            assertFailure(executor.execute(IpfsEffectTestSupport.context(
                            "other-chain", 1, new byte[0]), effect(command().encode())),
                    ConnectorErrorCode.INTERNAL_ERROR);
            assertFailure(executor.execute(IpfsEffectTestSupport.context(
                            IpfsEffectTestSupport.CHAIN_ID, 1, null), effect(command().encode())),
                    ConnectorErrorCode.INTERNAL_ERROR);

            IpfsPinCommandV1 unknown = new IpfsPinCommandV1("missing", CID,
                    true, REPLICATION_POLICY);
            assertFailure(executor.execute(context(1), effect(unknown.encode())),
                    ConnectorErrorCode.UNKNOWN_TARGET);
            IpfsPinCommandV1 recursiveMismatch = command(CID, false, REPLICATION_POLICY);
            assertFailure(executor.execute(context(1), effect(recursiveMismatch.encode())),
                    ConnectorErrorCode.POLICY_DENIED);
            IpfsPinCommandV1 replicationMismatch = command(CID, true, "other-policy");
            assertFailure(executor.execute(context(1), effect(replicationMismatch.encode())),
                    ConnectorErrorCode.POLICY_DENIED);
            CanonicalCid dagPb = dagPbCid();
            IpfsPinCommandV1 codecMismatch = command(dagPb, true, REPLICATION_POLICY);
            assertFailure(executor.execute(context(1), effect(codecMismatch.encode())),
                    ConnectorErrorCode.POLICY_DENIED);
            assertThat(opens).hasValue(0);
        }
    }

    @Test
    void disabledTargetAndProviderFailuresUseFrozenCodes() {
        Map<String, String> disabledSettings = new LinkedHashMap<>(
                IpfsEffectTestSupport.settings());
        disabledSettings.put("enabled", "false");
        IpfsEffectTestSupport.RecordingClient unused = client(PinState.ABSENT);
        try (IpfsPinExecutor executor = executor(
                IpfsEffectConfig.parse(disabledSettings), unused, null)) {
            assertFailure(executor.execute(context(1), effect(command().encode())),
                    ConnectorErrorCode.TARGET_DISABLED);
            assertThat(unused.calls()).isEqualTo(0);
        }

        for (ConnectorErrorCode code : List.of(
                ConnectorErrorCode.AUTH_UNAVAILABLE,
                ConnectorErrorCode.RATE_LIMITED,
                ConnectorErrorCode.SERVICE_UNAVAILABLE,
                ConnectorErrorCode.CONTENT_UNAVAILABLE,
                ConnectorErrorCode.PROVIDER_REJECTED)) {
            IpfsEffectTestSupport.RecordingClient failed = client(PinState.ABSENT);
            failed.failNextProbe(new IpfsProviderException(code));
            try (IpfsPinExecutor executor = executor(failed, null)) {
                assertFailure(executor.execute(context(1), effect(command().encode())), code);
                assertThat(failed.adds()).isEqualTo(0);
            }
        }
    }

    @Test
    void closeIsBoundedIdempotentAndContendingExecutionRetries() throws Exception {
        IpfsEffectTestSupport.RecordingClient client = client(PinState.ABSENT);
        client.blockProbe();
        IpfsPinExecutor executor = executor(client, null);
        CompletableFuture<EffectExecution> execution = CompletableFuture.supplyAsync(
                () -> executor.execute(context(1), effect(command().encode())));
        assertThat(client.awaitProbe(1, TimeUnit.SECONDS)).isTrue();

        EffectExecution contention = executor.execute(context(2), effect(command().encode()));
        assertThat(contention).isInstanceOf(EffectExecution.Retry.class);
        assertThat(((EffectExecution.Retry) contention).notBefore())
                .isEqualTo(Duration.ofMillis(100));

        CompletableFuture.runAsync(executor::close).get(2, TimeUnit.SECONDS);
        executor.close();
        assertThat(client.closeCalls()).isOne();
        assertFailure(execution.get(2, TimeUnit.SECONDS), ConnectorErrorCode.SHUTDOWN);
    }

    private static IpfsPinExecutor executor(IpfsEffectTestSupport.RecordingClient client,
                                            IpfsEffectTestSupport.MemoryDetailArchive archive) {
        return executor(IpfsEffectTestSupport.config(), client, archive);
    }

    private static IpfsPinExecutor executor(IpfsEffectConfig config,
                                            IpfsEffectTestSupport.RecordingClient client,
                                            IpfsEffectTestSupport.MemoryDetailArchive archive) {
        return new IpfsPinExecutor(config, ignored -> () -> client, archive);
    }

    private static IpfsEffectTestSupport.RecordingClient client(PinState state) {
        return new IpfsEffectTestSupport.RecordingClient(state);
    }

    private static CanonicalCid dagPbCid() {
        byte[] value = CID.bytes();
        value[1] = 0x70;
        return CanonicalCid.fromBytes(value);
    }

    private static void assertFailure(EffectExecution result, ConnectorErrorCode expected) {
        assertThat(result).isInstanceOf(EffectExecution.Failed.class);
        EffectExecution.Failed failed = (EffectExecution.Failed) result;
        assertThat(failed.reason()).isEqualTo(expected.wireCode());
        assertThat(failed.retryable()).isEqualTo(expected.disposition().retryable());
    }
}
