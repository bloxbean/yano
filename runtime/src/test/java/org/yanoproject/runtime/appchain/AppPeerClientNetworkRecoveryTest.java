package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AppPeerClientNetworkRecoveryTest {
    @Test
    void resetDuringHandshakeDoesNotStrandAppPeerAfterHealing(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("network-recovery.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dio.netty.eventLoopThreads=2", "-cp", System.getProperty("yano.test.runtime-classpath"),
                Probe.class.getName()).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(65, TimeUnit.SECONDS)).as("bounded network recovery probe").isTrue();
            String log = Files.readString(output);
            assertThat(child.exitValue()).as(log.substring(Math.max(0, log.length() - 16_000))).isZero();
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** A separate JVM contains the pre-fix networking-thread deadlock without leaking test workers. */
    public static final class Probe {
        public static void main(String[] args) {
            try {
                run();
                System.out.println("PASS: app peer reconnects after handshake-reset partition");
                Runtime.getRuntime().halt(0);
            } catch (Throwable failure) {
                failure.printStackTrace();
                Runtime.getRuntime().halt(17);
            }
        }

        private static void run() throws Exception {
            int serverPort;
            try (ServerSocket reserved = new ServerSocket(0)) { serverPort = reserved.getLocalPort(); }
            var config = AppMsgSubmissionConfig.builder().chainIds(Set.of("recovery")).build();
            NodeServer server = new NodeServer(serverPort,
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                    new InMemoryChainState(), null, null,
                    List.of(() -> new AppMsgSubmissionServerAgent(config)));
            Thread serverThread = new Thread(server::start, "recovery-test-server");
            serverThread.setDaemon(true);
            serverThread.start();
            awaitServer(serverPort);
            try (Gate gate = new Gate(serverPort)) {
                AppPeerClient peer = new AppPeerClient(new AppChainConfig.AppPeer("127.0.0.1", gate.port()),
                        42, config, LoggerFactory.getLogger(Probe.class));
                awaitConnected(peer, Duration.ofSeconds(10));
                gate.partition();
                // Accepted-and-reset TCP differs from a refused connection: it exercises
                // the delegate's close callback while its replacement handshake is pending.
                long blockedUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < blockedUntil) {
                    peer.ensureConnectedAsync();
                    Thread.sleep(50);
                }
                assertThat(peer.isConnected()).isFalse();
                gate.heal();
                awaitConnected(peer, Duration.ofSeconds(45));
                peer.shutdown();
            }
            server.shutdown();
        }

        private static void awaitConnected(AppPeerClient peer, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                peer.ensureConnectedAsync();
                if (peer.isConnected()) return;
                Thread.sleep(50);
            }
            throw new AssertionError("App peer did not reconnect within " + timeout);
        }

        private static void awaitServer(int port) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) { return; }
                catch (IOException notYetBound) { Thread.sleep(20); }
            }
            throw new AssertionError("Test server did not bind");
        }
    }

    private static final class Gate implements AutoCloseable {
        private final ServerSocket listener;
        private final int target;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private volatile boolean blocked;
        private volatile boolean closed;

        private Gate(int target) throws IOException {
            this.target = target;
            listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            workers.submit(this::accept);
        }

        int port() { return listener.getLocalPort(); }

        void partition() {
            blocked = true;
            sockets.forEach(Gate::closeSocket);
        }

        void heal() { blocked = false; }

        private void accept() {
            while (!closed) {
                try {
                    Socket incoming = listener.accept();
                    if (blocked) {
                        incoming.setSoLinger(true, 0);
                        incoming.close();
                    } else {
                        sockets.add(incoming);
                        workers.submit(() -> connect(incoming));
                    }
                } catch (IOException failure) {
                    if (!closed) throw new IllegalStateException(failure);
                }
            }
        }

        private void connect(Socket incoming) {
            try {
                Socket outgoing = new Socket(InetAddress.getLoopbackAddress(), target);
                sockets.add(outgoing);
                if (blocked || closed) {
                    closeSocket(incoming);
                    closeSocket(outgoing);
                    return;
                }
                workers.submit(() -> copy(incoming, outgoing));
                copy(outgoing, incoming);
            } catch (IOException failure) {
                closeSocket(incoming);
            }
        }

        private void copy(Socket source, Socket destination) {
            try {
                source.getInputStream().transferTo(destination.getOutputStream());
            } catch (IOException expectedReset) {
                // Fault injection deliberately closes both directions.
            } finally {
                closeSocket(source);
                closeSocket(destination);
                sockets.remove(source);
                sockets.remove(destination);
            }
        }

        private static void closeSocket(Socket socket) {
            try { socket.close(); } catch (IOException ignored) { }
        }

        @Override public void close() throws IOException {
            closed = true;
            listener.close();
            sockets.forEach(Gate::closeSocket);
            workers.shutdownNow();
        }
    }
}
