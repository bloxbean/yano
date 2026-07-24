package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Restart-safe, bounded, one-job-at-a-time prover coordinator. */
public final class EutxoProverService implements AutoCloseable {
    private final String proverId;
    private final EutxoProverStore store;
    private final EutxoProofBackend backend;
    private final Clock clock;
    private final Duration timeout;
    private final int maximumAttempts;
    private final int maximumJobs;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("eutxo-zk-prover-", 0).factory());

    public EutxoProverService(
            String proverId,
            EutxoProverStore store,
            EutxoProofBackend backend,
            Clock clock,
            Duration timeout,
            int maximumAttempts,
            int maximumJobs
    ) {
        if (proverId == null || proverId.isBlank() || proverId.length() > 128) {
            throw new IllegalArgumentException("invalid prover id");
        }
        this.proverId = proverId;
        this.store = Objects.requireNonNull(store, "store");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || maximumAttempts < 1 || maximumJobs < 1) {
            throw new IllegalArgumentException("invalid prover service bounds");
        }
        this.maximumAttempts = maximumAttempts;
        this.maximumJobs = maximumJobs;
        store.saveVerificationKey(backend.verificationKey());
        recoverInterruptedJobs();
        reconcile();
    }

    public EutxoProverJob submit(
            EutxoZkStatement statement,
            EutxoZkBatchData batchData,
            EutxoKeyPaymentBatch witness
    ) {
        return store.create(
                statement, batchData, witness, clock.instant(), maximumJobs);
    }

    public Optional<EutxoProverJob> workOnce() {
        Optional<EutxoProverJob> candidate = store.list().stream()
                .filter(job -> job.status() == EutxoProverJob.Status.QUEUED)
                .findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        EutxoProverJob queued = candidate.orElseThrow();
        if (queued.attempts() >= maximumAttempts) {
            EutxoProverJob failed = transition(
                    queued, EutxoProverJob.Status.FAILED,
                    queued.proofDigest(), "maximum attempts exhausted",
                    queued.attempts());
            return Optional.of(failed);
        }
        EutxoProverJob running = transition(
                queued, EutxoProverJob.Status.RUNNING,
                "", "", queued.attempts() + 1);
        Future<EutxoZkProofArtifact> future = worker.submit(() ->
                backend.prove(
                        store.statement(running.id()),
                        store.witness(running.id()),
                        proverId));
        try {
            EutxoZkProofArtifact proof = future.get(
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!backend.verify(proof)) {
                throw new IllegalStateException(
                        "generated proof failed local verification");
            }
            store.saveProof(running.id(), proof);
            return Optional.of(transition(
                    running, EutxoProverJob.Status.PROVED,
                    proof.digestHex(), "", running.attempts()));
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            return Optional.of(fail(running, "proof attempt timed out"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.of(fail(running, "proof attempt interrupted"));
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null
                    ? failure : failure.getCause();
            return Optional.of(fail(
                    running, "proof attempt failed: " + cause.getMessage()));
        } catch (RuntimeException failure) {
            return Optional.of(fail(
                    running, "proof attempt failed: " + failure.getMessage()));
        }
    }

    public EutxoProverJob retry(String id) {
        EutxoProverJob job = requireJob(id);
        if (job.status() != EutxoProverJob.Status.FAILED) {
            throw new IllegalStateException("only failed prover jobs can be retried");
        }
        if (job.attempts() >= maximumAttempts) {
            throw new IllegalStateException("maximum prover attempts exhausted");
        }
        return transition(job, EutxoProverJob.Status.QUEUED,
                "", "", job.attempts());
    }

    public EutxoProverJob cancel(String id) {
        EutxoProverJob job = requireJob(id);
        if (job.status() != EutxoProverJob.Status.QUEUED
                && job.status() != EutxoProverJob.Status.FAILED) {
            throw new IllegalStateException(
                    "only queued or failed prover jobs can be cancelled");
        }
        return transition(job, EutxoProverJob.Status.CANCELLED,
                "", "cancelled by operator", job.attempts());
    }

    /**
     * Reconciles durable metadata with proof artifacts. No consensus state is
     * read or mutated.
     */
    public void reconcile() {
        for (EutxoProverJob job : store.list()) {
            if (job.status() != EutxoProverJob.Status.PROVED) {
                continue;
            }
            Optional<EutxoZkProofArtifact> proof = store.proof(job.id());
            if (proof.isEmpty()
                    || !proof.orElseThrow().digestHex().equals(job.proofDigest())
                    || !backend.verify(proof.orElseThrow())) {
                transition(job, EutxoProverJob.Status.FAILED,
                        "", "proof artifact missing or invalid",
                        job.attempts());
            }
        }
    }

    public Health health() {
        Metrics metrics = metrics();
        boolean healthy = metrics.running() <= 1
                && metrics.queued() + metrics.failed() < maximumJobs;
        return new Health(
                healthy,
                backend.verificationKey().digestHex(),
                metrics,
                healthy ? "" : "prover backlog or concurrency invariant exceeded");
    }

    public Metrics metrics() {
        Map<EutxoProverJob.Status, Long> counts =
                new EnumMap<>(EutxoProverJob.Status.class);
        for (EutxoProverJob.Status status : EutxoProverJob.Status.values()) {
            counts.put(status, 0L);
        }
        List<EutxoProverJob> jobs = store.list();
        for (EutxoProverJob job : jobs) {
            counts.compute(job.status(), (ignored, value) -> value + 1);
        }
        return new Metrics(
                counts.get(EutxoProverJob.Status.QUEUED),
                counts.get(EutxoProverJob.Status.RUNNING),
                counts.get(EutxoProverJob.Status.PROVED),
                counts.get(EutxoProverJob.Status.FAILED),
                counts.get(EutxoProverJob.Status.CANCELLED),
                jobs.stream().mapToInt(EutxoProverJob::attempts).sum());
    }

    public EutxoProverStore store() {
        return store;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        backend.close();
    }

    private void recoverInterruptedJobs() {
        for (EutxoProverJob job : store.list()) {
            if (job.status() == EutxoProverJob.Status.RUNNING) {
                transition(job, EutxoProverJob.Status.QUEUED,
                        "", "recovered after prover restart", job.attempts());
            }
        }
    }

    private EutxoProverJob fail(EutxoProverJob running, String error) {
        return transition(running, EutxoProverJob.Status.FAILED,
                "", error, running.attempts());
    }

    private EutxoProverJob transition(
            EutxoProverJob previous,
            EutxoProverJob.Status status,
            String proofDigest,
            String error,
            int attempts
    ) {
        EutxoProverJob updated = new EutxoProverJob(
                previous.id(), status, attempts,
                previous.createdAt(), clock.instant(),
                proofDigest, error);
        store.save(updated);
        return updated;
    }

    private EutxoProverJob requireJob(String id) {
        return store.find(id).orElseThrow(() ->
                new IllegalArgumentException("unknown prover job " + id));
    }

    public record Metrics(
            long queued,
            long running,
            long proved,
            long failed,
            long cancelled,
            long attempts
    ) {
    }

    public record Health(
            boolean healthy,
            String verificationKeyDigest,
            Metrics metrics,
            String message
    ) {
    }
}
