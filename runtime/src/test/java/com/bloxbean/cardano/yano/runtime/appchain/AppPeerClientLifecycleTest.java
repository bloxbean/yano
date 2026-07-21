package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgInitAck;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class AppPeerClientLifecycleTest {
    private static final Logger LOG = LoggerFactory.getLogger(AppPeerClientLifecycleTest.class);

    @Test
    void shutdownInFinalPublicationToStartGapMakesLateStartANoOp() throws Exception {
        CapturingTransportFactory factory = new CapturingTransportFactory();
        CountDownLatch beforeStart = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AppPeerClient client = client(factory, () -> {
            beforeStart.countDown();
            awaitUninterruptibly(releaseStart);
        });
        AtomicReference<Throwable> connectorFailure = new AtomicReference<>();

        Thread connector = new Thread(() -> {
            try {
                client.ensureConnected();
            } catch (Throwable failure) {
                connectorFailure.set(failure);
            }
        }, "app-peer-publication-race-test");
        connector.start();

        assertThat(beforeStart.await(5, TimeUnit.SECONDS)).isTrue();
        client.shutdown();
        releaseStart.countDown();
        connector.join(5_000);

        assertThat(connector.isAlive()).isFalse();
        assertThat(connectorFailure.get()).isNull();
        assertThat(factory.transport.startCalls).isZero();
        assertThat(factory.transport.shutdownCalls).isEqualTo(1);
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void shutdownAfterStartAdmissionInterruptsStarterAndStarterOwnsCleanup() throws Exception {
        CapturingTransportFactory factory = new CapturingTransportFactory();
        CountDownLatch startAdmitted = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        CountDownLatch releaseDelegateStart = new CountDownLatch(1);
        AppPeerClient client = client(factory, () -> { }, () -> {
            startAdmitted.countDown();
            awaitUninterruptibly(releaseDelegateStart, interruptionObserved);
        });
        AtomicReference<Throwable> connectorFailure = new AtomicReference<>();
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

        Thread connector = new Thread(() -> {
            try {
                client.ensureConnected();
            } catch (Throwable failure) {
                connectorFailure.set(failure);
            }
        }, "app-peer-admitted-start-test");
        connector.start();
        assertThat(startAdmitted.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopper = new Thread(() -> {
            try {
                client.shutdown();
            } catch (Throwable failure) {
                shutdownFailure.set(failure);
            }
        }, "app-peer-admitted-start-shutdown-test");
        stopper.start();
        try {
            assertThat(interruptionObserved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopper.isAlive()).isTrue();
            assertThat(factory.transport.startCalls).isZero();
            assertThat(factory.transport.shutdownCalls).isZero();
        } finally {
            releaseDelegateStart.countDown();
        }

        connector.join(5_000);
        stopper.join(5_000);
        assertThat(connector.isAlive()).isFalse();
        assertThat(stopper.isAlive()).isFalse();
        assertThat(connectorFailure.get()).isNull();
        assertThat(shutdownFailure.get()).isNull();
        assertThat(factory.transport.startCalls).isEqualTo(1);
        assertThat(factory.transport.startObservedInterrupted).isTrue();
        assertThat(factory.transport.shutdownCalls).isEqualTo(1);
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void reconnectReoffersMessageEnqueuedAfterNegotiationAndDeduplicatesIt() {
        CapturingTransportFactory factory = new CapturingTransportFactory();
        AppPeerClient client = client(factory, () -> { });
        try {
            client.ensureConnected();
            negotiate(factory);
            assertThat(client.isConnected()).isTrue();

            AppMessage message = message(1);
            client.enqueue(message);
            client.enqueue(message);
            assertThat(factory.appMsgAgent.getQueueSize()).isEqualTo(1);

            reconnect(factory);

            assertThat(client.isConnected()).isTrue();
            assertThat(factory.appMsgAgent.getQueueSize()).isEqualTo(1);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void reconnectCacheRemainsBounded() {
        CapturingTransportFactory factory = new CapturingTransportFactory();
        AppPeerClient client = client(factory, () -> { });
        try {
            client.ensureConnected();
            negotiate(factory);
            for (int i = 0; i < 205; i++) {
                client.enqueue(message(i));
            }
            assertThat(factory.appMsgAgent.getQueueSize()).isEqualTo(205);

            reconnect(factory);

            assertThat(factory.appMsgAgent.getQueueSize()).isEqualTo(200);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void shutdownPropagatesTransportCleanupFailureAfterClearingOwnership() {
        CapturingTransportFactory factory = new CapturingTransportFactory();
        AppPeerClient client = client(factory, () -> { });
        client.ensureConnected();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        factory.transport.shutdownFailure = cleanupFailure;

        assertThatThrownBy(client::shutdown).isSameAs(cleanupFailure);
        assertThat(factory.transport.shutdownCalls).isEqualTo(1);
        assertThat(client.isConnected()).isFalse();
    }

    private static AppPeerClient client(CapturingTransportFactory factory, Runnable beforeStart) {
        return client(factory, beforeStart, () -> { });
    }

    private static AppPeerClient client(CapturingTransportFactory factory,
                                        Runnable beforeStart,
                                        Runnable beforeDelegateStart) {
        return new AppPeerClient(
                new AppChainConfig.AppPeer("127.0.0.1", 3001),
                42,
                AppMsgSubmissionConfig.builder().chainIds(Set.of("c1")).build(),
                null,
                LOG,
                factory,
                beforeStart,
                beforeDelegateStart);
    }

    private static void reconnect(CapturingTransportFactory factory) {
        factory.appMsgAgent.disconnected();
        factory.appMsgAgent.reset();
        assertThat(factory.appMsgAgent.getQueueSize()).isZero();
        negotiate(factory);
    }

    private static void negotiate(CapturingTransportFactory factory) {
        factory.handshakeAgent.processResponse(new AcceptVersion(
                11, new N2NVersionData(42, false, 0, false)));
        factory.appMsgAgent.processResponse(new MsgInitAck(List.of("c1")));
    }

    private static AppMessage message(int sequence) {
        byte[] id = new byte[32];
        ByteBuffer.wrap(id).putInt(28, sequence);
        byte[] body = ("message-" + sequence).getBytes(StandardCharsets.UTF_8);
        return AppMessage.builder()
                .messageId(id)
                .chainId("c1")
                .topic("test")
                .sender(new byte[32])
                .senderSeq(sequence)
                .expiresAt(System.currentTimeMillis() / 1000 + 600)
                .body(body)
                .build();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        awaitUninterruptibly(latch, null);
    }

    private static void awaitUninterruptibly(CountDownLatch latch, CountDownLatch interruptionObserved) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
                if (interruptionObserved != null) {
                    interruptionObserved.countDown();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CapturingTransportFactory implements AppPeerClient.PeerTransportFactory {
        private final FakeTransport transport = new FakeTransport();
        private HandshakeAgent handshakeAgent;
        private AppMsgSubmissionAgent appMsgAgent;

        @Override
        public AppPeerClient.PeerTransport create(AppChainConfig.AppPeer peer,
                                                  HandshakeAgent handshakeAgent,
                                                  AppMsgSubmissionAgent appMsgAgent,
                                                  KeepAliveAgent keepAliveAgent,
                                                  AppChainSyncClientAgent syncAgent) {
            this.handshakeAgent = handshakeAgent;
            this.appMsgAgent = appMsgAgent;
            return transport;
        }
    }

    private static final class FakeTransport implements AppPeerClient.PeerTransport {
        private int startCalls;
        private int shutdownCalls;
        private boolean running;
        private boolean startObservedInterrupted;
        private RuntimeException shutdownFailure;

        @Override
        public void start() {
            startCalls++;
            startObservedInterrupted = Thread.currentThread().isInterrupted();
            if (startObservedInterrupted) {
                return;
            }
            running = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
            running = false;
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
        }
    }
}
