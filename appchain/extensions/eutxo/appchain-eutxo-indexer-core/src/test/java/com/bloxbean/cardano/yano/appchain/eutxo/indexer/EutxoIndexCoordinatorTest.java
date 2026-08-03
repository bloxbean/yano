package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory.InMemoryEutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoIndexCoordinatorTest {
    @Test
    void retainedCatchUpProjectsFinalizedBlocksWithoutBlockingCallbacks()
            throws Exception {
        List<List<EutxoIndexEvent>> fixture =
                EutxoIndexFixtures.splitMergeEvents();
        EutxoIndexEvent.Transaction firstTransaction =
                (EutxoIndexEvent.Transaction) fixture.getFirst().get(0);
        EutxoIndexEvent.Transaction secondTransaction =
                (EutxoIndexEvent.Transaction) fixture.getFirst().get(1);
        EutxoIndexEvent.Transaction thirdTransaction =
                (EutxoIndexEvent.Transaction) fixture.getLast().getFirst();
        Map<String, EutxoTransactionSummary> byMessage = Map.of(
                firstTransaction.summary().messageId(),
                firstTransaction.summary(),
                secondTransaction.summary().messageId(),
                secondTransaction.summary(),
                thirdTransaction.summary().messageId(),
                thirdTransaction.summary());
        AppBlock first = block(1, fixture.getFirst().stream()
                .map(EutxoIndexEvent.Transaction.class::cast)
                .map(EutxoIndexCoordinatorTest::message).toList());
        AppBlock second = block(2, fixture.getLast().stream()
                .map(EutxoIndexEvent.Transaction.class::cast)
                .map(EutxoIndexCoordinatorTest::message).toList());
        AtomicReference<AppChainGateway.FinalizedBlockListener>
                listener = new AtomicReference<>();
        AppChainGateway gateway = gateway(
                Map.of(1L, first, 2L, second), byMessage, listener);
        InMemoryEutxoIndexStore store =
                new InMemoryEutxoIndexStore(EutxoIndexFixtures.identity());
        try (EutxoIndexCoordinator coordinator =
                     new EutxoIndexCoordinator(gateway, store)) {
            coordinator.start();
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (store.checkpoint().source().appHeight() < 2
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(store.checkpoint().source().appHeight()).isEqualTo(2);
            assertThat(store.reader().transactions(0, 10).items()).hasSize(3);
            assertThat(coordinator.health().status())
                    .isEqualTo(IndexHealth.Status.READY);
            assertThat(listener.get()).isNotNull();
        }
    }

    @Test
    void committedBridgeHaltIsExposedWithoutDeletingAcceptedHistory()
            throws Exception {
        List<EutxoIndexEvent> fixture =
                EutxoIndexFixtures.splitMergeEvents().getFirst();
        Map<String, EutxoTransactionSummary> byMessage =
                fixture.stream()
                        .map(EutxoIndexEvent.Transaction.class::cast)
                        .collect(java.util.stream.Collectors.toMap(
                                event -> event.summary().messageId(),
                                EutxoIndexEvent.Transaction::summary));
        AppBlock block = block(
                1,
                fixture.stream()
                        .map(EutxoIndexEvent.Transaction.class::cast)
                        .map(EutxoIndexCoordinatorTest::message)
                        .toList());
        AtomicReference<AppChainGateway.FinalizedBlockListener>
                listener = new AtomicReference<>();
        AppChainGateway gateway = gateway(
                Map.of(1L, block), byMessage, listener,
                "DEEP_ROLLBACK_BELOW_CREDITED_DEPOSIT");
        InMemoryEutxoIndexStore store =
                new InMemoryEutxoIndexStore(
                        EutxoIndexFixtures.identity());
        try (EutxoIndexCoordinator coordinator =
                     new EutxoIndexCoordinator(gateway, store)) {
            coordinator.start();
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (store.checkpoint().source().appHeight() < 1
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(coordinator.health().diagnostic())
                    .isEqualTo(
                            "DEEP_ROLLBACK_BELOW_CREDITED_DEPOSIT");
            assertThat(store.reader().transactions(0, 10).items())
                    .hasSize(2);
        }
    }

    @Test
    void slowStoreCoalescesNotificationBurstsWithoutBlockingFinality()
            throws Exception {
        AppBlock first = block(1, List.of());
        AtomicReference<AppChainGateway.FinalizedBlockListener>
                listener = new AtomicReference<>();
        AppChainGateway gateway = gateway(
                Map.of(1L, first), Map.of(), listener);
        InMemoryEutxoIndexStore delegate =
                new InMemoryEutxoIndexStore(
                        EutxoIndexFixtures.identity());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SlowStore slow = new SlowStore(
                delegate, entered, release);
        try (EutxoIndexCoordinator coordinator =
                     new EutxoIndexCoordinator(gateway, slow)) {
            coordinator.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            for (int notification = 0;
                 notification < 10_000;
                 notification++) {
                listener.get().onFinalized(first, new byte[32]);
            }
            long elapsed = System.nanoTime() - started;
            assertThat(elapsed)
                    .isLessThan(TimeUnit.SECONDS.toNanos(1));
            assertThat(coordinator.queueDepth()).isLessThanOrEqualTo(1);
            release.countDown();
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5);
            while (delegate.checkpoint().source().appHeight() < 1
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(delegate.checkpoint().source().appHeight())
                    .isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void missingRetainedHistoryFailsTheIndexWithoutConsensusImpact()
            throws Exception {
        AtomicReference<AppChainGateway.FinalizedBlockListener>
                listener = new AtomicReference<>();
        AppChainGateway gateway = gateway(
                Map.of(2L, block(2, List.of())),
                Map.of(),
                listener);
        InMemoryEutxoIndexStore store =
                new InMemoryEutxoIndexStore(
                        EutxoIndexFixtures.identity());
        try (EutxoIndexCoordinator coordinator =
                     new EutxoIndexCoordinator(gateway, store)) {
            coordinator.start();
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.health().status()
                    != IndexHealth.Status.FAILED
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(coordinator.health().status())
                    .isEqualTo(IndexHealth.Status.FAILED);
            assertThat(store.checkpoint().source().appHeight())
                    .isZero();
        }
    }

    private static AppChainGateway gateway(
        Map<Long, AppBlock> blocks,
            Map<String, EutxoTransactionSummary> summaries,
            AtomicReference<AppChainGateway.FinalizedBlockListener>
                    listener
    ) {
        return gateway(blocks, summaries, listener, "");
    }

    private static AppChainGateway gateway(
            Map<Long, AppBlock> blocks,
            Map<String, EutxoTransactionSummary> summaries,
            AtomicReference<AppChainGateway.FinalizedBlockListener>
                    listener,
            String bridgeHalt
    ) {
        return (AppChainGateway) Proxy.newProxyInstance(
                EutxoIndexCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "payments";
                    case "tipHeight" -> blocks.keySet().stream()
                            .mapToLong(Long::longValue)
                            .max()
                            .orElse(0L);
                    case "block" -> Optional.ofNullable(
                            blocks.get((Long) arguments[0]));
                    case "subscribeFinalized" -> {
                        listener.set((AppChainGateway.FinalizedBlockListener)
                                arguments[0]);
                        yield (AutoCloseable) () -> listener.set(null);
                    }
                    case "query" -> query(
                            (String) arguments[0],
                            (byte[]) arguments[1],
                            summaries,
                            bridgeHalt);
                    case "toString" -> "EutxoIndexCoordinatorTestGateway";
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
    }

    private static AppQueryResult query(
            String path,
            byte[] request,
            Map<String, EutxoTransactionSummary> summaries,
            String bridgeHalt
    ) {
        byte[] payload = switch (path) {
            case EutxoQueryCodec.DEPOSIT_COUNT_PATH,
                    EutxoQueryCodec.WITHDRAWAL_COUNT_PATH ->
                    EutxoQueryCodec.count(0);
            case EutxoQueryCodec.BRIDGE_HALT_PATH ->
                    EutxoQueryCodec.bridgeHalt(bridgeHalt);
            case EutxoQueryCodec.MESSAGE_SUMMARY_PATH -> {
                String id = HexFormat.of().formatHex(request);
                EutxoTransactionSummary summary = summaries.get(id);
                yield summary == null ? new byte[0] : summary.encode();
            }
            default -> throw new UnsupportedOperationException(path);
        };
        return new AppQueryResult(
                "payments", "eutxo-ledger", 2,
                new byte[32], payload);
    }

    private static AppMessage message(EutxoIndexEvent.Transaction event) {
        return AppMessage.builder()
                .version(1)
                .messageId(HexFormat.of().parseHex(
                        event.summary().messageId()))
                .chainId("payments")
                .topic("eutxo.tx")
                .body(new byte[]{1})
                .sender(new byte[32])
                .senderSeq(event.sequence())
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppBlock block(long height, List<AppMessage> messages) {
        return new AppBlock(
                1, "payments", height,
                height == 1 ? new byte[32]
                        : HexFormat.of().parseHex(
                        EutxoIndexFixtures.hex(height - 1)),
                100 + height,
                HexFormat.of().parseHex(
                        EutxoIndexFixtures.hex(100 + height)),
                1_000 + height,
                com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec
                        .messagesRoot(messages),
                new byte[32],
                messages,
                new byte[32],
                FinalityCert.empty());
    }

    private record SlowStore(
            EutxoIndexStore delegate,
            CountDownLatch entered,
            CountDownLatch release
    ) implements EutxoIndexStore {
        @Override
        public IndexIdentity identity() {
            return delegate.identity();
        }

        @Override
        public EutxoIndexWrite begin(SourcePoint source) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "slow store test timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return delegate.begin(source);
        }

        @Override
        public IndexCheckpoint checkpoint() {
            return delegate.checkpoint();
        }

        @Override
        public void rollbackTo(SourcePoint source) {
            delegate.rollbackTo(source);
        }

        @Override
        public EutxoIndexReader reader() {
            return delegate.reader();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
