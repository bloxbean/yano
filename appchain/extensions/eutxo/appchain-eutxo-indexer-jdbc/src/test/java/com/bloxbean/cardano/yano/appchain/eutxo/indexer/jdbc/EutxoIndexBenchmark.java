package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexCoordinator;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexReader;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexWrite;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoProjector;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCheckpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCoverage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory.InMemoryEutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexFixtures;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reproducible single-process support-bound benchmark, not a peak claim. */
public final class EutxoIndexBenchmark {
    private static final int EVENTS_PER_BLOCK = 100;
    private static final int QUERY_SAMPLES = 1_000;

    private EutxoIndexBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "expected <report.json> <operation-count>");
        }
        Path report = Path.of(arguments[0])
                .toAbsolutePath().normalize();
        int operations = Integer.parseInt(arguments[1]);
        if (operations < 1_000 || operations > 1_000_000
                || operations % EVENTS_PER_BLOCK != 0) {
            throw new IllegalArgumentException(
                    "operation count must be 1,000-1,000,000"
                            + " and divisible by "
                            + EVENTS_PER_BLOCK);
        }
        Path workspace = Files.createTempDirectory(
                "yano-eutxo-index-benchmark-");
        IndexIdentity identity = EutxoIndexFixtures.identity();
        EutxoIndexStoreContext context =
                new EutxoIndexStoreContext(
                        identity, workspace, Map.of());
        int blocks = operations / EVENTS_PER_BLOCK;
        long ingestionStart = System.nanoTime();
        String digest;
        try (EutxoIndexStore store =
                     SqliteEutxoIndexStore.open(context)) {
            replayRange(store, 1, blocks);
            digest = store.reader().normalizedDigest();
        }
        long ingestionNanos = System.nanoTime() - ingestionStart;
        long databaseBytes = Files.size(workspace.resolve(
                SqliteEutxoIndexStore.DEFAULT_FILE));

        long restartStart = System.nanoTime();
        long[] lookupNanos = new long[QUERY_SAMPLES];
        long[] pageNanos = new long[QUERY_SAMPLES];
        long[] accountNanos = new long[QUERY_SAMPLES];
        long restartNanos;
        long rollbackReplayNanos;
        try (EutxoIndexStore store =
                     SqliteEutxoIndexStore.open(context)) {
            restartNanos = System.nanoTime() - restartStart;
            if (!digest.equals(store.reader().normalizedDigest())) {
                throw new IllegalStateException(
                        "restart changed normalized projection digest");
            }
            for (int sample = 0; sample < QUERY_SAMPLES; sample++) {
                long sequence = 1L + (sample * 7_919L) % operations;
                long started = System.nanoTime();
                store.reader().transaction(hex(sequence));
                lookupNanos[sample] = System.nanoTime() - started;

                started = System.nanoTime();
                store.reader().transactions(sequence, 25);
                pageNanos[sample] = System.nanoTime() - started;

                started = System.nanoTime();
                store.reader().account(
                        address(sequence), 25);
                accountNanos[sample] = System.nanoTime() - started;
            }

            long rollbackHeight = Math.max(1, blocks - 10L);
            long rollbackStart = System.nanoTime();
            store.rollbackTo(point(rollbackHeight));
            replayRange(
                    store, (int) rollbackHeight + 1, blocks);
            rollbackReplayNanos =
                    System.nanoTime() - rollbackStart;
            if (!digest.equals(store.reader().normalizedDigest())) {
                throw new IllegalStateException(
                        "rollback and replay changed projection digest");
            }
        }
        long rebuildStart = System.nanoTime();
        SqliteIndexRebuilder.rebuild(
                context,
                store -> replayRange(store, 1, blocks));
        long rebuildNanos = System.nanoTime() - rebuildStart;
        try (EutxoIndexStore rebuilt =
                     SqliteEutxoIndexStore.open(context)) {
            if (!digest.equals(rebuilt.reader().normalizedDigest())) {
                throw new IllegalStateException(
                        "shadow rebuild changed projection digest");
            }
        }
        CallbackResult callbacks = callbackBenchmark();

        Files.createDirectories(report.getParent());
        Files.writeString(report, json(
                operations,
                blocks,
                ingestionNanos,
                databaseBytes,
                restartNanos,
                rollbackReplayNanos,
                rebuildNanos,
                lookupNanos,
                pageNanos,
                accountNanos,
                callbacks,
                digest));
        System.out.println(report);
    }

    private static void replayRange(
            EutxoIndexStore store,
            int firstBlock,
            int lastBlock
    ) {
        EutxoProjector projector = new EutxoProjector(store);
        for (int block = firstBlock; block <= lastBlock; block++) {
            long first = (long) (block - 1)
                    * EVENTS_PER_BLOCK + 1;
            List<EutxoIndexEvent> events =
                    new ArrayList<>(EVENTS_PER_BLOCK);
            for (int offset = 0;
                 offset < EVENTS_PER_BLOCK;
                 offset++) {
                long sequence = first + offset;
                events.add(new EutxoIndexEvent.Transaction(
                        sequence, summary(sequence, block)));
            }
            projector.apply(
                    point(block), events, IndexCoverage.FULL);
        }
    }

    private static EutxoTransactionSummary summary(
            long sequence,
            long height
    ) {
        List<EutxoTransactionSummary.Entry> inputs =
                sequence == 1 ? List.of() : List.of(
                        entry(sequence - 1, address(sequence - 1)));
        return new EutxoTransactionSummary(
                hex(sequence),
                hex(sequence + 1_000_000),
                sequence,
                height,
                (int) ((sequence - 1) % EVENTS_PER_BLOCK),
                10_000 + height,
                EutxoTransactionSummary.Status.ACCEPTED,
                "cardano-vkey",
                inputs,
                List.of(entry(sequence, address(sequence))),
                "");
    }

    private static EutxoTransactionSummary.Entry entry(
            long transaction,
            String address
    ) {
        return new EutxoTransactionSummary.Entry(
                new EutxoOutpoint(hex(transaction), 0),
                address,
                BigInteger.valueOf(10_000_000));
    }

    private static String address(long sequence) {
        return "addr_test1_benchmark_" + sequence % 64;
    }

    private static SourcePoint point(long height) {
        return new SourcePoint(
                height, hex(height),
                10_000 + height, hex(10_000 + height));
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%064x", value);
    }

    private static String json(
            int operations,
            int blocks,
            long ingestionNanos,
            long databaseBytes,
            long restartNanos,
            long rollbackReplayNanos,
            long rebuildNanos,
            long[] lookupNanos,
            long[] pageNanos,
            long[] accountNanos,
            CallbackResult callbacks,
            String digest
    ) {
        double seconds = ingestionNanos / 1_000_000_000.0;
        return """
                {
                  "schema": "yano-eutxo-index-benchmark-v1",
                  "java": "%s",
                  "os": "%s %s",
                  "processors": %d,
                  "operations": %d,
                  "eventsPerBlock": %d,
                  "blocks": %d,
                  "ingestionSeconds": %.6f,
                  "ingestionOperationsPerSecond": %.2f,
                  "databaseBytes": %d,
                  "bytesPerOperation": %.2f,
                  "restartMillis": %.3f,
                  "rollbackReplayTenBlocksMillis": %.3f,
                  "shadowRebuildSeconds": %.6f,
                  "shadowRebuildOperationsPerSecond": %.2f,
                  "lookupP50Micros": %.3f,
                  "lookupP95Micros": %.3f,
                  "page25P50Micros": %.3f,
                  "page25P95Micros": %.3f,
                  "accountP50Micros": %.3f,
                  "accountP95Micros": %.3f,
                  "finalizedCallbackSamples": %d,
                  "finalizedCallbackP50Micros": %.3f,
                  "finalizedCallbackP95Micros": %.3f,
                  "finalizedCallbackP99Micros": %.3f,
                  "consensusOverheadMethod": "finalized-listener callback while the index writer is stalled",
                  "normalizedDigest": "%s"
                }
                """.formatted(
                escape(System.getProperty("java.version")),
                escape(System.getProperty("os.name")),
                escape(System.getProperty("os.arch")),
                Runtime.getRuntime().availableProcessors(),
                operations,
                EVENTS_PER_BLOCK,
                blocks,
                seconds,
                operations / seconds,
                databaseBytes,
                (double) databaseBytes / operations,
                restartNanos / 1_000_000.0,
                rollbackReplayNanos / 1_000_000.0,
                rebuildNanos / 1_000_000_000.0,
                operations / (rebuildNanos / 1_000_000_000.0),
                percentileMicros(lookupNanos, 0.50),
                percentileMicros(lookupNanos, 0.95),
                percentileMicros(pageNanos, 0.50),
                percentileMicros(pageNanos, 0.95),
                percentileMicros(accountNanos, 0.50),
                percentileMicros(accountNanos, 0.95),
                callbacks.nanos().length,
                percentileMicros(callbacks.nanos(), 0.50),
                percentileMicros(callbacks.nanos(), 0.95),
                percentileMicros(callbacks.nanos(), 0.99),
                digest);
    }

    private static CallbackResult callbackBenchmark() throws Exception {
        ConcurrentHashMap<Long, AppBlock> blocks =
                new ConcurrentHashMap<>();
        AtomicReference<AppChainGateway.FinalizedBlockListener> listener =
                new AtomicReference<>();
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                EutxoIndexBenchmark.class.getClassLoader(),
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
                        listener.set(
                                (AppChainGateway.FinalizedBlockListener)
                                        arguments[0]);
                        yield (AutoCloseable) () -> listener.set(null);
                    }
                    case "query" -> callbackQuery(
                            (String) arguments[0]);
                    case "toString" ->
                            "EutxoIndexCallbackBenchmarkGateway";
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EutxoIndexStore slow = new SlowStore(
                new InMemoryEutxoIndexStore(
                        EutxoIndexFixtures.identity()),
                entered,
                release);
        try (EutxoIndexCoordinator coordinator =
                     new EutxoIndexCoordinator(gateway, slow)) {
            coordinator.start();
            AppBlock block = emptyBlock();
            blocks.put(1L, block);
            listener.get().onFinalized(block, new byte[32]);
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "callback benchmark worker did not stall");
            }
            long[] nanos = new long[10_000];
            for (int sample = 0; sample < nanos.length; sample++) {
                long started = System.nanoTime();
                listener.get().onFinalized(block, new byte[32]);
                nanos[sample] = System.nanoTime() - started;
            }
            release.countDown();
            return new CallbackResult(nanos);
        } finally {
            release.countDown();
        }
    }

    private static AppQueryResult callbackQuery(String path) {
        byte[] payload = switch (path) {
            case EutxoQueryCodec.DEPOSIT_COUNT_PATH,
                    EutxoQueryCodec.WITHDRAWAL_COUNT_PATH ->
                    EutxoQueryCodec.count(0);
            case EutxoQueryCodec.BRIDGE_HALT_PATH ->
                    EutxoQueryCodec.bridgeHalt("");
            default -> throw new UnsupportedOperationException(path);
        };
        return new AppQueryResult(
                "payments", "eutxo-ledger", 1,
                new byte[32], payload);
    }

    private static AppBlock emptyBlock() {
        return new AppBlock(
                1, "payments", 1,
                new byte[32],
                10_001,
                java.util.HexFormat.of().parseHex(hex(10_001)),
                1_001,
                AppBlockCodec.messagesRoot(List.of()),
                new byte[32],
                List.of(),
                new byte[32],
                FinalityCert.empty());
    }

    private static double percentileMicros(
            long[] source,
            double percentile
    ) {
        List<Long> values = java.util.Arrays.stream(source)
                .boxed()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.min(
                values.size() - 1,
                (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(index) / 1_000.0;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private record CallbackResult(long[] nanos) {
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
                            "callback benchmark timed out");
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
