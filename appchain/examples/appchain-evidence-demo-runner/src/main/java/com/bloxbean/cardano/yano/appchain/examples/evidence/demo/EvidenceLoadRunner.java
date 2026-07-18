package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Runs bounded lifecycle or staged-pipeline evidence publication loads. */
final class EvidenceLoadRunner {
    private final PublisherFactory publishers;
    private final PipelinePublisherFactory pipelinePublishers;
    private final ReportStore reports;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final int evidenceCapacityPerBlock;
    private final String finalityGate;
    private final boolean anchorRequired;

    EvidenceLoadRunner(DemoConfig config, ReportStore reports) {
        LoadWorkflowGates workflows = new LoadWorkflowGates(
                config.evidenceCapacityPerBlock());
        LoadAnchorAdvancer anchors = new LoadAnchorAdvancer();
        this.publishers = () -> new EnvironmentPublisher(config, reports, workflows, anchors);
        this.pipelinePublishers = () -> new EnvironmentPipelinePublisher(
                config, reports, workflows, anchors);
        this.reports = reports;
        this.clock = Clock.systemUTC();
        this.nanoTime = System::nanoTime;
        this.evidenceCapacityPerBlock = config.evidenceCapacityPerBlock();
        this.finalityGate = config.requireAnchor() ? "L1_ANCHORED" : "APP_FINAL";
        this.anchorRequired = config.requireAnchor();
    }

    EvidenceLoadRunner(PublisherFactory publishers,
                       ReportStore reports,
                       Clock clock,
                       LongSupplier nanoTime) {
        this(publishers, null, reports, clock, nanoTime, 1, "APP_FINAL", false);
    }

    EvidenceLoadRunner(PublisherFactory publishers,
                       PipelinePublisherFactory pipelinePublishers,
                       ReportStore reports,
                       Clock clock,
                       LongSupplier nanoTime,
                       int evidenceCapacityPerBlock) {
        this(publishers, pipelinePublishers, reports, clock, nanoTime,
                evidenceCapacityPerBlock, "APP_FINAL", false);
    }

    EvidenceLoadRunner(PublisherFactory publishers,
                       PipelinePublisherFactory pipelinePublishers,
                       ReportStore reports,
                       Clock clock,
                       LongSupplier nanoTime,
                       int evidenceCapacityPerBlock,
                       String finalityGate,
                       boolean anchorRequired) {
        this.publishers = publishers;
        this.pipelinePublishers = pipelinePublishers;
        this.reports = reports;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.evidenceCapacityPerBlock = evidenceCapacityPerBlock;
        this.finalityGate = finalityGate;
        this.anchorRequired = anchorRequired;
    }

    LoadReport run(LoadRequest request) {
        Instant started = clock.instant();
        long startedNanos = nanoTime.getAsLong();
        ExecutionResult execution = request.mode() == LoadRequest.Mode.PIPELINE
                ? runPipeline(request) : runLifecycle(request);
        return finish(request, started, startedNanos,
                execution.items(), execution.maximumObservedInFlight());
    }

    private ExecutionResult runLifecycle(LoadRequest request) {
        List<ItemResult> results = new ArrayList<>(request.count());
        try (var executor = Executors.newFixedThreadPool(
                request.concurrency(), Thread.ofPlatform().name("evidence-load-", 0).factory())) {
            List<java.util.concurrent.Future<List<ItemResult>>> workers = new ArrayList<>();
            for (int worker = 0; worker < request.concurrency(); worker++) {
                int workerIndex = worker;
                workers.add(executor.submit(() -> runLifecycleWorker(request, workerIndex)));
            }
            for (var worker : workers) {
                results.addAll(worker.get());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        return new ExecutionResult(results, Math.min(request.count(), request.concurrency()));
    }

    private List<ItemResult> runLifecycleWorker(LoadRequest request, int workerIndex) {
        List<Integer> indices = new ArrayList<>();
        for (int index = workerIndex + 1; index <= request.count();
             index += request.concurrency()) {
            indices.add(index);
        }
        List<ItemResult> results = new ArrayList<>(indices.size());
        try (Publisher publisher = publishers.open()) {
            for (int index : indices) {
                results.add(runLifecycleItem(request, publisher, index));
            }
        } catch (RuntimeException failure) {
            DemoError error = error(failure);
            for (int index : indices) {
                if (results.stream().noneMatch(existing -> existing.index() == index)) {
                    results.add(ItemResult.failure(index, request.evidenceId(index), 0,
                            Stage.LIFECYCLE, error,
                            Map.of(Stage.LIFECYCLE, new StageOutcome(0, false)), 0));
                }
            }
        }
        return results;
    }

    private ItemResult runLifecycleItem(LoadRequest request, Publisher publisher, int index) {
        String evidenceId = request.evidenceId(index);
        long started = nanoTime.getAsLong();
        try {
            ScenarioReport report = publisher.publish(new ScenarioRequest(
                    ScenarioRequest.Operation.PUBLISH, evidenceId, 1, request.sampleFile()));
            long elapsed = elapsedMillis(started);
            if (!"PASS".equals(report.outcome()) || !evidenceId.equals(report.evidenceId())) {
                return ItemResult.failure(index, evidenceId, elapsed, Stage.LIFECYCLE,
                        DemoError.INTERNAL_ERROR,
                        Map.of(Stage.LIFECYCLE, new StageOutcome(elapsed, false)), 0);
            }
            int effects = report.chain() == null ? 0 : report.chain().effectProofsVerified();
            return ItemResult.success(index, evidenceId, elapsed,
                    Map.of(Stage.LIFECYCLE, new StageOutcome(elapsed, true)),
                    report.submittedEnvelopes(), effects);
        } catch (RuntimeException failure) {
            long elapsed = elapsedMillis(started);
            return ItemResult.failure(index, evidenceId, elapsed, Stage.LIFECYCLE,
                    error(failure), Map.of(Stage.LIFECYCLE,
                            new StageOutcome(elapsed, false)), 0);
        }
    }

    private ExecutionResult runPipeline(LoadRequest request) {
        if (pipelinePublishers == null) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        try (PipelinePublisher probe = pipelinePublishers.open()) {
            probe.probe();
        }
        Map<Stage, ArrayBlockingQueue<PipelineTask>> queues = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.pipeline()) {
            queues.put(stage, new ArrayBlockingQueue<>(request.maxInFlight(), true));
        }
        ConcurrentLinkedQueue<ItemResult> results = new ConcurrentLinkedQueue<>();
        CountDownLatch complete = new CountDownLatch(request.count());
        Semaphore inFlight = new Semaphore(request.maxInFlight(), true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        int workers = Math.multiplyExact(Stage.pipeline().size(), request.concurrency());
        ExecutorService executor = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("evidence-pipeline-", 0).factory());
        try {
            for (Stage stage : Stage.pipeline()) {
                for (int worker = 0; worker < request.concurrency(); worker++) {
                    executor.submit(() -> pipelineWorker(stage, queues, results,
                            complete, inFlight, active));
                }
            }
            for (int index = 1; index <= request.count(); index++) {
                acquire(inFlight);
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                PipelineTask task = new PipelineTask(index, request.evidenceId(index),
                        new ScenarioRequest(ScenarioRequest.Operation.PUBLISH,
                                request.evidenceId(index), 1, request.sampleFile()),
                        nanoTime.getAsLong());
                put(queues.get(Stage.PREPARE), task);
            }
            await(complete);
        } finally {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new DemoException(DemoError.INTERNAL_ERROR);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DemoException(DemoError.INTERNAL_ERROR);
            }
        }
        return new ExecutionResult(new ArrayList<>(results), maximum.get());
    }

    private void pipelineWorker(Stage stage,
                                Map<Stage, ArrayBlockingQueue<PipelineTask>> queues,
                                ConcurrentLinkedQueue<ItemResult> results,
                                CountDownLatch complete,
                                Semaphore inFlight,
                                AtomicInteger active) {
        PipelinePublisher publisher = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                PipelineTask task = queues.get(stage).take();
                long stageStarted = nanoTime.getAsLong();
                try {
                    if (publisher == null) {
                        publisher = pipelinePublishers.open();
                    }
                    ScenarioReport verified = process(stage, publisher, task);
                    long duration = elapsedMillis(stageStarted);
                    task.outcomes.put(stage, new StageOutcome(duration, true));
                    if (stage == Stage.VERIFY) {
                        int effects = verified.chain() == null
                                ? 0 : verified.chain().effectProofsVerified();
                        results.add(ItemResult.success(task.index, task.evidenceId,
                                elapsedMillis(task.startedNanos), task.outcomes,
                                task.item.submittedMessages(), effects));
                        complete.countDown();
                        active.decrementAndGet();
                        inFlight.release();
                    } else {
                        put(queues.get(stage.next()), task);
                    }
                } catch (RuntimeException failure) {
                    long duration = elapsedMillis(stageStarted);
                    task.outcomes.put(stage, new StageOutcome(duration, false));
                    int submitted = task.item == null ? 0 : task.item.submittedMessages();
                    results.add(ItemResult.failure(task.index, task.evidenceId,
                            elapsedMillis(task.startedNanos), stage, error(failure),
                            task.outcomes, submitted));
                    complete.countDown();
                    active.decrementAndGet();
                    inFlight.release();
                    close(publisher);
                    publisher = null;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            close(publisher);
        }
    }

    private ScenarioReport process(Stage stage,
                                   PipelinePublisher publisher,
                                   PipelineTask task) {
        return switch (stage) {
            case PREPARE -> {
                task.item = publisher.prepare(task.request);
                yield null;
            }
            case PREREQUISITES -> {
                task.item = publisher.prerequisites(task.item);
                yield null;
            }
            case APPROVAL -> {
                task.item = publisher.approval(task.item);
                yield null;
            }
            case RELEASE -> {
                task.item = publisher.release(task.item);
                yield null;
            }
            case EFFECTS -> {
                task.item = publisher.effects(task.item);
                yield null;
            }
            case VERIFY -> {
                ScenarioReport report = publisher.verify(task.item);
                if (!"PASS".equals(report.outcome())
                        || !task.evidenceId.equals(report.evidenceId())) {
                    throw new DemoException(DemoError.INTERNAL_ERROR);
                }
                yield report;
            }
            case LIFECYCLE -> throw new DemoException(DemoError.INTERNAL_ERROR);
        };
    }

    private LoadReport finish(LoadRequest request,
                              Instant started,
                              long startedNanos,
                              List<ItemResult> results,
                              int maximumObservedInFlight) {
        results = new ArrayList<>(results);
        results.sort(Comparator.comparingInt(ItemResult::index));
        if (results.size() != request.count()) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        Instant finished = clock.instant();
        long elapsedNanos = Math.max(0, nanoTime.getAsLong() - startedNanos);
        long durationMillis = elapsedNanos / 1_000_000;
        double seconds = elapsedNanos == 0 ? 0 : elapsedNanos / 1_000_000_000.0;
        int succeeded = (int) results.stream().filter(ItemResult::successful).count();
        int failed = results.size() - succeeded;
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        List<LoadReport.FailureSample> samples = new ArrayList<>();
        for (ItemResult result : results) {
            if (result.successful()) {
                continue;
            }
            failureCounts.merge(result.error().name(), 1, Math::addExact);
            if (samples.size() < LoadReport.MAX_FAILURE_SAMPLES) {
                samples.add(new LoadReport.FailureSample(result.index(), result.evidenceId(),
                        result.error().name(), result.failureStage().wireName()));
            }
        }
        List<Long> latencies = results.stream().map(ItemResult::latencyMillis).sorted().toList();
        int messages = results.stream().mapToInt(ItemResult::submittedMessages).sum();
        int effects = results.stream().mapToInt(ItemResult::effectsVerified).sum();
        Map<String, LoadReport.StageMetrics> stages = stageMetrics(request, results, seconds);
        LoadReport report = new LoadReport(LoadReport.SCHEMA_VERSION,
                "load-" + started.toEpochMilli() + "-"
                        + UUID.randomUUID().toString().substring(0, 8),
                request.idPrefix(), request.count(), results.size(), succeeded, failed,
                request.concurrency(), request.mode().wireName(), request.maxInFlight(),
                maximumObservedInFlight, evidenceCapacityPerBlock,
                finalityGate, anchorRequired,
                failed == 0 ? "PASS" : "FAIL",
                started.toString(), finished.toString(), durationMillis,
                rate(results.size(), seconds), rate(succeeded, seconds),
                new LoadReport.Throughput(messages, rate(messages, seconds),
                        succeeded, rate(succeeded, seconds), effects, rate(effects, seconds),
                        succeeded, rate(succeeded, seconds)),
                latency(latencies), stages, failureCounts, samples);
        reports.writeLoad(report);
        return report;
    }

    private Map<String, LoadReport.StageMetrics> stageMetrics(
            LoadRequest request,
            List<ItemResult> results,
            double seconds) {
        List<Stage> included = request.mode() == LoadRequest.Mode.PIPELINE
                ? Stage.pipeline() : List.of(Stage.LIFECYCLE);
        Map<String, LoadReport.StageMetrics> metrics = new LinkedHashMap<>();
        for (Stage stage : included) {
            List<StageOutcome> outcomes = results.stream()
                    .map(result -> result.stages().get(stage))
                    .filter(java.util.Objects::nonNull).toList();
            int successful = (int) outcomes.stream().filter(StageOutcome::successful).count();
            List<Long> durations = outcomes.stream().map(StageOutcome::durationMillis)
                    .sorted().toList();
            long total = durations.stream().mapToLong(Long::longValue).sum();
            metrics.put(stage.wireName(), new LoadReport.StageMetrics(
                    outcomes.size(), successful, outcomes.size() - successful, total,
                    rate(successful, seconds), durations.isEmpty() ? null : latency(durations)));
        }
        return Map.copyOf(metrics);
    }

    private long elapsedMillis(long started) {
        return Math.max(0, nanoTime.getAsLong() - started) / 1_000_000;
    }

    private static double rate(int count, double seconds) {
        return seconds == 0 ? 0 : count / seconds;
    }

    private static LoadReport.LatencyMillis latency(List<Long> sorted) {
        return new LoadReport.LatencyMillis(sorted.getFirst(), percentile(sorted, 50),
                percentile(sorted, 95), sorted.getLast());
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile / 100.0) - 1);
        return sorted.get(index);
    }

    private static DemoError error(RuntimeException failure) {
        return failure instanceof DemoException demo
                ? demo.error() : DemoError.INTERNAL_ERROR;
    }

    private static void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    private static void put(ArrayBlockingQueue<PipelineTask> queue, PipelineTask task) {
        try {
            queue.put(task);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // A completed or failed item already owns the stable terminal result.
        }
    }

    @FunctionalInterface
    interface PublisherFactory {
        Publisher open();
    }

    interface Publisher extends AutoCloseable {
        ScenarioReport publish(ScenarioRequest request);

        @Override
        void close();
    }

    @FunctionalInterface
    interface PipelinePublisherFactory {
        PipelinePublisher open();
    }

    interface PipelinePublisher extends AutoCloseable {
        default void probe() {
        }

        EvidenceScenario.PipelineItem prepare(ScenarioRequest request);

        EvidenceScenario.PipelineItem prerequisites(EvidenceScenario.PipelineItem item);

        EvidenceScenario.PipelineItem approval(EvidenceScenario.PipelineItem item);

        EvidenceScenario.PipelineItem release(EvidenceScenario.PipelineItem item);

        EvidenceScenario.PipelineItem effects(EvidenceScenario.PipelineItem item);

        ScenarioReport verify(EvidenceScenario.PipelineItem item);

        @Override
        void close();
    }

    private static final class EnvironmentPublisher implements Publisher {
        private final DemoEnvironment environment;
        private final EvidenceScenario scenario;

        private EnvironmentPublisher(DemoConfig config,
                                     ReportStore reports,
                                     LoadWorkflowGates workflows,
                                     LoadAnchorAdvancer anchors) {
            environment = new DemoEnvironment(config);
            scenario = new EvidenceScenario(
                    environment, reports, Clock.systemUTC(), workflows, anchors);
        }

        @Override
        public ScenarioReport publish(ScenarioRequest request) {
            return scenario.run(request);
        }

        @Override
        public void close() {
            environment.close();
        }
    }

    private static final class EnvironmentPipelinePublisher implements PipelinePublisher {
        private final DemoEnvironment environment;
        private final EvidenceScenario scenario;

        private EnvironmentPipelinePublisher(DemoConfig config,
                                             ReportStore reports,
                                             LoadWorkflowGates workflows,
                                             LoadAnchorAdvancer anchors) {
            environment = new DemoEnvironment(config);
            scenario = new EvidenceScenario(
                    environment, reports, Clock.systemUTC(), workflows, anchors);
        }

        @Override
        public void probe() {
            scenario.probePipeline();
        }

        @Override
        public EvidenceScenario.PipelineItem prepare(ScenarioRequest request) {
            return scenario.preparePipeline(request);
        }

        @Override
        public EvidenceScenario.PipelineItem prerequisites(
                EvidenceScenario.PipelineItem item) {
            return scenario.submitPipelinePrerequisites(item);
        }

        @Override
        public EvidenceScenario.PipelineItem approval(EvidenceScenario.PipelineItem item) {
            return scenario.submitPipelineApproval(item);
        }

        @Override
        public EvidenceScenario.PipelineItem release(EvidenceScenario.PipelineItem item) {
            return scenario.submitPipelineRelease(item);
        }

        @Override
        public EvidenceScenario.PipelineItem effects(EvidenceScenario.PipelineItem item) {
            return scenario.awaitPipelineEffects(item);
        }

        @Override
        public ScenarioReport verify(EvidenceScenario.PipelineItem item) {
            return scenario.verifyPipeline(item);
        }

        @Override
        public void close() {
            environment.close();
        }
    }

    private enum Stage {
        LIFECYCLE,
        PREPARE,
        PREREQUISITES,
        APPROVAL,
        RELEASE,
        EFFECTS,
        VERIFY;

        static List<Stage> pipeline() {
            return List.of(PREPARE, PREREQUISITES, APPROVAL, RELEASE, EFFECTS, VERIFY);
        }

        Stage next() {
            return pipeline().get(pipeline().indexOf(this) + 1);
        }

        String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class PipelineTask {
        private final int index;
        private final String evidenceId;
        private final ScenarioRequest request;
        private final long startedNanos;
        private final Map<Stage, StageOutcome> outcomes = new EnumMap<>(Stage.class);
        private EvidenceScenario.PipelineItem item;

        private PipelineTask(int index,
                             String evidenceId,
                             ScenarioRequest request,
                             long startedNanos) {
            this.index = index;
            this.evidenceId = evidenceId;
            this.request = request;
            this.startedNanos = startedNanos;
        }
    }

    private record StageOutcome(long durationMillis, boolean successful) {
        private StageOutcome {
            if (durationMillis < 0) {
                throw new IllegalArgumentException("negative stage duration");
            }
        }
    }

    private record ExecutionResult(List<ItemResult> items,
                                   int maximumObservedInFlight) {
        private ExecutionResult {
            items = List.copyOf(items);
            if (items.isEmpty() || maximumObservedInFlight < 1) {
                throw new IllegalArgumentException("invalid load execution result");
            }
        }
    }

    private record ItemResult(int index,
                              String evidenceId,
                              long latencyMillis,
                              boolean successful,
                              Stage failureStage,
                              DemoError error,
                              Map<Stage, StageOutcome> stages,
                              int submittedMessages,
                              int effectsVerified) {
        private ItemResult {
            stages = Map.copyOf(stages);
            if (index < 1 || evidenceId == null || latencyMillis < 0
                    || successful == (error != null)
                    || successful == (failureStage != null)
                    || submittedMessages < 0 || effectsVerified < 0) {
                throw new IllegalArgumentException("invalid load item result");
            }
        }

        static ItemResult success(int index,
                                  String evidenceId,
                                  long latencyMillis,
                                  Map<Stage, StageOutcome> stages,
                                  int submittedMessages,
                                  int effectsVerified) {
            return new ItemResult(index, evidenceId, latencyMillis, true,
                    null, null, stages, submittedMessages, effectsVerified);
        }

        static ItemResult failure(int index,
                                  String evidenceId,
                                  long latencyMillis,
                                  Stage stage,
                                  DemoError error,
                                  Map<Stage, StageOutcome> stages,
                                  int submittedMessages) {
            return new ItemResult(index, evidenceId, latencyMillis, false,
                    stage, error, stages, submittedMessages, 0);
        }
    }
}
