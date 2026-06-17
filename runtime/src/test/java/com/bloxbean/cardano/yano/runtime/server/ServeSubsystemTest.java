package com.bloxbean.cardano.yano.runtime.server;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServeSubsystemTest {

    @Test
    void stopAndAwaitRetainsHandlesWhenServerThreadDoesNotStop() throws Exception {
        ChainState chainState = new InMemoryChainState();
        ServeSubsystem subsystem = new ServeSubsystem(
                0,
                42L,
                chainState,
                noopTransactionAdmission(),
                false,
                LoggerFactory.getLogger(ServeSubsystemTest.class));
        TestNodeServer server = new TestNodeServer(chainState);
        CountDownLatch release = new CountDownLatch(1);
        Thread serverThread = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ServeSubsystemTest-blocked-server");
        serverThread.setDaemon(true);
        serverThread.start();

        setField(subsystem, "nodeServer", server);
        setField(subsystem, "serverThread", serverThread);
        running(subsystem).set(true);

        try {
            assertFalse(subsystem.stopAndAwait(Duration.ofMillis(10)));
            assertSame(server, getField(subsystem, "nodeServer"));
            assertSame(serverThread, getField(subsystem, "serverThread"));
            assertFalse(subsystem.isRunning());
            assertTrue(server.shutdownCalled);
        } finally {
            release.countDown();
            serverThread.join(1000);
        }
    }

    private static AtomicBoolean running(ServeSubsystem subsystem) throws Exception {
        return (AtomicBoolean) getField(subsystem, "running");
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TransactionAdmission noopTransactionAdmission() {
        return new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                return "tx";
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        };
    }

    private static final class TestNodeServer extends NodeServer {
        private boolean shutdownCalled;

        private TestNodeServer(ChainState chainState) {
            super(0, N2NVersionTableConstant.v11AndAbove(42L, false, 0, false), chainState);
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }
    }
}
