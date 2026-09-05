package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCandidate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificateVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRequest;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscription;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTick;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Function;

/**
 * Bounded node-local acquisition, report collection, certification, and
 * periodic re-diffusion. It reads committed rounds only; neither provider
 * completion nor journal contents participate directly in block validity.
 */
final class ObservationRuntime implements AutoCloseable {
    private final ObservationProfileV1 profile;
    private final ObservationSettings settings;
    private final ObservationProviders providers;
    private final ObservationKernel.Reader reader;
    private final ObservationJournal journal;
    private final SignerProvider signer;
    private final MemberGroup members;
    private final byte[] chainGenesisId;
    private final String chainId;
    private final byte[] consensusProfileDigest;
    private final BiConsumer<String, byte[]> diffusion;
    private final LongSupplier committedHeight;
    private final Logger log;
    private final ScheduledExecutorService coordinator;
    private final ExecutorService workers;
    private final int requestsPerSecond;
    private final Map<String, Long> nextDefinitionRequest = new LinkedHashMap<>();
    private long requestWindow = System.nanoTime();
    private int requestsInWindow;
    private final Set<RoundKey> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore coordinatorSlots = new Semaphore(1024);
    private static final int COORDINATOR_BYTE_BUDGET = 16 * 1024 * 1024;
    private final Semaphore coordinatorBytes = new Semaphore(COORDINATOR_BYTE_BUDGET);
    private final AtomicLong acquisitionAttempts = new AtomicLong();
    private final AtomicLong acquisitionFailures = new AtomicLong();
    private final AtomicLong reportsAccepted = new AtomicLong();
    private final AtomicLong certificatesReady = new AtomicLong();
    private final AtomicLong backpressureEvents = new AtomicLong();
    private volatile boolean lastTickFailed;

    ObservationRuntime(ObservationSettings settings, ObservationProviders providers,
                       AppLedgerStore ledger, SignerProvider signer, MemberGroup members,
                       String chainId, byte[] chainGenesisId, byte[] consensusProfileDigest,
                       BiConsumer<String, byte[]> diffusion, int workerCount,
                       int journalMaxEntries, long journalMaxBytes, Logger log) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.profile = settings.profile();
        if (!profile.enabled()) {
            throw new IllegalArgumentException("Cannot start runtime for disabled observations");
        }
        this.providers = Objects.requireNonNull(providers, "providers");
        this.reader = Objects.requireNonNull(ledger, "ledger").observationReader();
        this.committedHeight = ledger::tipHeight;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.members = Objects.requireNonNull(members, "members");
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.chainGenesisId = Objects.requireNonNull(chainGenesisId, "chainGenesisId").clone();
        this.consensusProfileDigest = Objects.requireNonNull(
                consensusProfileDigest, "consensusProfileDigest").clone();
        this.diffusion = Objects.requireNonNull(diffusion, "diffusion");
        this.log = Objects.requireNonNull(log, "log");
        if (workerCount < 1 || workerCount > 64) {
            throw new IllegalArgumentException("Observation worker count must be 1..64");
        }
        this.journal = new ObservationJournal(ledger, chainGenesisId, chainId,
                signer.publicKey(), profile.digest(), journalMaxEntries, journalMaxBytes);
        this.coordinator = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "observation-coordinator-" + chainId);
            thread.setDaemon(true);
            return thread;
        });
        this.requestsPerSecond = workerCount;
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workerCount), runnable -> {
            Thread thread = new Thread(runnable, "observation-worker-" + chainId);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void start() {
        coordinator.scheduleWithFixedDelay(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    Optional<AppMessage> heartbeat(long stableSlot, Function<byte[], AppMessage> envelope) {
        if (closed.get() || profile.logicalTimeVersion() != 2 || stableSlot <= 0
                || profile.maxTicksPerBlock() == 0 || reader.activeCount() == 0) return Optional.empty();
        boolean needed = !reader.dueAtOrBefore(ObservationAnchorType.VERIFIED_L1_SLOT,
                stableSlot, 1).isEmpty();
        if (!needed) {
            needed = reader.openRounds(profile.maxOpenRounds()).stream().anyMatch(round ->
                    round.anchorType() == ObservationAnchorType.VERIFIED_L1_SLOT
                            && (stableSlot > round.reportDeadlineAnchor()
                                || committedHeight.getAsLong() >= round.absoluteMaxRoundHeight()));
        }
        if (!needed) return Optional.empty();
        byte[] body = new ObservationTick(1, ObservationAnchorType.VERIFIED_L1_SLOT, stableSlot).encode();
        return Optional.of(journal.pendingTick(body, () -> envelope.apply(body)));
    }

    void onFinalized() {
        if (!closed.get()) {
            submitCoordinator(this::safeTick);
        }
    }

    void onReport(byte[] body) {
        if (closed.get()) return;
        final ObservationReport report;
        try {
            if (body == null || body.length > profile.maxReportBytes()) return;
            report = ObservationReport.decode(body);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        submitCoordinator(() -> acceptReport(report, true), body.length);
    }

    void onCertificate(byte[] body) {
        if (closed.get()) return;
        final ObservationCertificate certificate;
        try {
            if (body == null || body.length > profile.maxCertificateBytes()) return;
            certificate = ObservationCertificate.decode(body);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        submitCoordinator(() -> acceptCertificate(certificate, true), body.length);
    }

    List<ObservationCertificate> readyCertificates(int limit) {
        if (limit <= 0) return List.of();
        limit = Math.min(limit, profile.maxResultsPerBlock());
        List<ObservationCertificate> ready = new ArrayList<>();
        for (ObservationCertificate certificate : journal.readyCertificates(limit * 2 + 1)) {
            ObservationSubscription subscription = reader.subscription(
                    certificate.subscriptionId()).orElse(null);
            ObservationRound round = reader.round(certificate.subscriptionId(),
                    certificate.roundNumber()).orElse(null);
            if (subscription != null && round != null
                    && subscription.status() == ObservationSubscriptionStatus.ACTIVE
                    && subscription.nextRoundNumber() == certificate.roundNumber()) {
                ready.add(certificate);
                if (ready.size() == limit) break;
            }
        }
        ready.sort(CERTIFICATE_ORDER);
        return List.copyOf(ready);
    }

    Map<String, Long> status() {
        Map<String, Long> status = new LinkedHashMap<>();
        status.put("acquisitionAttempts", acquisitionAttempts.get());
        status.put("acquisitionFailures", acquisitionFailures.get());
        status.put("reportsAccepted", reportsAccepted.get());
        status.put("certificatesReady", certificatesReady.get());
        status.put("journalEntries", (long) journal.entries());
        status.put("journalBytes", journal.bytes());
        status.put("backpressureEvents", backpressureEvents.get());
        status.put("inFlight", (long) inFlight.size());
        status.put("coordinatorQueued", (long) (1024 - coordinatorSlots.availablePermits()));
        status.put("coordinatorReservedBytes", (long) (COORDINATOR_BYTE_BUDGET - coordinatorBytes.availablePermits()));
        status.put("queuedWorkers", (long) ((ThreadPoolExecutor) workers).getQueue().size());
        status.put("activeSubscriptions", reader.activeCount());
        status.put("openRounds", reader.openRoundCount());
        status.put("highWaterSlot", reader.highWaterSlot());
        return Map.copyOf(status);
    }

    boolean ready() {
        return !closed.get() && !lastTickFailed && coordinatorSlots.availablePermits() > 0
                && coordinatorBytes.availablePermits() > 0
                && journal.canReserve(3, 4L * profile.maxReportBytes())
                && ((ThreadPoolExecutor) workers).getQueue().remainingCapacity() > 0;
    }

    private void safeTick() {
        try {
            tick();
            lastTickFailed = false;
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            lastTickFailed = true;
            log.warn("Observation runtime tick failed (errorType={})",
                    failure.getClass().getName());
        }
    }

    private void submitCoordinator(Runnable task) {
        submitCoordinator(task, 0);
    }

    private void submitCoordinator(Runnable task, int encodedBytes) {
        if (closed.get()) return;
        // Include conservative headroom for defensive copies and decoded records.
        int weight = Math.toIntExact(Math.min(Integer.MAX_VALUE, 4L * (encodedBytes + 256L)));
        if (!coordinatorBytes.tryAcquire(weight)) {
            backpressureEvents.incrementAndGet();
            return;
        }
        if (!coordinatorSlots.tryAcquire()) {
            coordinatorBytes.release(weight);
            backpressureEvents.incrementAndGet();
            return;
        }
        try {
            coordinator.execute(() -> {
                try {
                    task.run();
                } finally {
                    coordinatorSlots.release();
                    coordinatorBytes.release(weight);
                }
            });
        } catch (RejectedExecutionException stopped) {
            coordinatorSlots.release();
            coordinatorBytes.release(weight);
            if (!closed.get()) throw stopped;
        }
    }

    void tick() {
        if (closed.get()) return;
        List<ObservationRound> rounds = new ArrayList<>(reader.openRounds(profile.maxOpenRounds() + 1));
        if (rounds.size() > profile.maxOpenRounds()) {
            throw new IllegalStateException("Observation open-round index exceeds profile bound");
        }
        rounds.sort(Comparator.comparingInt((ObservationRound round) -> round.anchorType().code())
                .thenComparingLong(ObservationRound::dueAnchor)
                .thenComparing(ObservationRound::subscriptionId, Arrays::compareUnsigned));
        long height = committedHeight.getAsLong();
        for (ObservationJournal.RoundRef retained : journal.retainedRounds(
                profile.maxOpenRounds() + profile.maxResultsPerBlock() + 1)) {
            ObservationSubscription subscription = reader.subscription(
                    retained.subscriptionId()).orElse(null);
            ObservationRound round = reader.round(
                    retained.subscriptionId(), retained.roundNumber()).orElse(null);
            if (subscription == null || round == null
                    || subscription.status() != ObservationSubscriptionStatus.ACTIVE
                    || subscription.nextRoundNumber() != retained.roundNumber()) {
                journal.markTerminal(retained.subscriptionId(), retained.roundNumber());
            }
        }
        for (ObservationRound round : rounds) {
            ObservationSubscription subscription = reader.subscription(
                    round.subscriptionId()).orElse(null);
            if (subscription == null || subscription.status() != ObservationSubscriptionStatus.ACTIVE) {
                journal.markTerminal(round.subscriptionId(), round.roundNumber());
                continue;
            }
            for (ObservationReport report : journal.reports(
                    round.subscriptionId(), round.roundNumber(), profile.maxReportsPerRound())) {
                diffusion.accept(ObservationTopics.REPORT, report.encode());
            }
            for (ObservationCertificate certificate : journal.readyCertificates(
                    profile.maxResultsPerBlock() * 2)) {
                if (Arrays.equals(certificate.subscriptionId(), round.subscriptionId())
                        && certificate.roundNumber() == round.roundNumber()) {
                    diffusion.accept(ObservationTopics.CERTIFICATE, certificate.encode());
                }
            }
            boolean alreadyReported = journal.reports(round.subscriptionId(), round.roundNumber(),
                    profile.maxReportsPerRound()).stream().anyMatch(report ->
                    Arrays.equals(report.reporterPublicKey(), signer.publicKey()));
            if (!alreadyReported && round.resultExpiryHeight() == 0
                    && height <= round.absoluteMaxRoundHeight()
                    && (round.anchorType() == ObservationAnchorType.APP_HEIGHT
                            ? height : reader.highWaterSlot()) <= round.reportDeadlineAnchor()) {
                RoundKey key = new RoundKey(round.subscriptionId(), round.roundNumber());
                if (inFlight.add(key)) {
                    if (!requestPermit(definition(round.definitionDigest()).id())) {
                        backpressureEvents.incrementAndGet();
                        inFlight.remove(key);
                        continue;
                    }
                    try {
                        workers.execute(() -> acquire(subscription, round, key));
                    } catch (RejectedExecutionException full) {
                        backpressureEvents.incrementAndGet();
                        inFlight.remove(key);
                        break; // Bounded backpressure; retained rounds are retried later.
                    }
                }
            }
        }
    }

    private synchronized boolean requestPermit(String definitionId) {
        long now = System.nanoTime();
        if (now - requestWindow >= TimeUnit.SECONDS.toNanos(1)) {
            requestWindow = now;
            requestsInWindow = 0;
        }
        Long next = nextDefinitionRequest.get(definitionId);
        if (requestsInWindow >= requestsPerSecond || (next != null && now - next < 0)) return false;
        requestsInWindow++;
        nextDefinitionRequest.put(definitionId, now + TimeUnit.MILLISECONDS.toNanos(250));
        return true;
    }

    private boolean stillCollecting(ObservationRound round) {
        ObservationSubscription subscription = reader.subscription(round.subscriptionId()).orElse(null);
        if (closed.get() || subscription == null
                || subscription.status() != ObservationSubscriptionStatus.ACTIVE
                || subscription.nextDueAnchor() != 0
                || subscription.nextRoundNumber() != round.roundNumber()) return false;
        long height = committedHeight.getAsLong();
        long anchor = round.anchorType() == ObservationAnchorType.APP_HEIGHT
                ? height : reader.highWaterSlot();
        return height <= round.absoluteMaxRoundHeight() && anchor <= round.reportDeadlineAnchor();
    }

    private void acquire(ObservationSubscription subscription, ObservationRound round,
                         RoundKey key) {
        try {
            if (!stillCollecting(round)) return;
            acquisitionAttempts.incrementAndGet();
            ObservationDefinition definition = definition(round.definitionDigest());
            ObservationReport unsigned = journal.localCandidate(
                    round.subscriptionId(), round.roundNumber()).orElse(null);
            if (unsigned == null) {
                ObservationProvider provider = providers.require(definition.id());
                ObservationCandidate candidate;
                synchronized (provider) {
                    if (!stillCollecting(round)) return;
                    candidate = provider.acquire(
                            new ObservationRequest(definition, subscription, round));
                }
                if (!stillCollecting(round)) return;
                unsigned = new ObservationReport(1, chainGenesisId, chainId,
                    consensusProfileDigest, profile.digest(), definition.digest(),
                    round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                    round.reporterSetDigest(), signer.publicKey(), candidate.sourceId(),
                    candidate.value(), candidate.evidence(), candidate.sourceVersion(),
                    candidate.freshnessAnchorType(), candidate.freshnessAnchor(), new byte[64]);
                if (unsigned.value().length > definition.maxValueBytes()
                        || unsigned.evidence().length > definition.maxEvidenceBytes()
                        || unsigned.encode().length > profile.maxReportBytes()
                        || !settings.verifierRegistry().evidenceVerifier(definition)
                        .verify(definition, round, unsigned)) {
                    throw new IllegalArgumentException("Invalid observation signing candidate");
                }
                unsigned = journal.prepareLocalReport(unsigned);
            }
            if (!stillCollecting(round)) return;
            ObservationReport report = copyWithSignature(unsigned,
                    signer.sign(unsigned.signingDigest()));
            if (!validReport(definition, round, report)) {
                throw new IllegalArgumentException(
                        "Observation provider produced a non-verifiable candidate");
            }
            acceptReport(report, false);
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            acquisitionFailures.incrementAndGet();
            log.warn("Observation acquisition failed for round {}/{} (errorType={})",
                    HexUtil.encodeHexString(key.subscriptionId()), key.roundNumber(),
                    failure.getClass().getName());
        } finally {
            inFlight.remove(key);
        }
    }

    private void acceptReport(ObservationReport report, boolean rediffuse) {
        ObservationRound round = reader.round(report.subscriptionId(), report.roundNumber())
                .orElse(null);
        ObservationSubscription subscription = reader.subscription(report.subscriptionId())
                .orElse(null);
        if (round == null || subscription == null
                || subscription.status() != ObservationSubscriptionStatus.ACTIVE
                || subscription.nextRoundNumber() != round.roundNumber()) {
            return;
        }
        ObservationDefinition definition = definition(round.definitionDigest());
        if (!validReport(definition, round, report)) {
            return;
        }
        try {
            boolean added = journal.persistReport(report);
            if (added) reportsAccepted.incrementAndGet();
            if (added) {
                diffusion.accept(ObservationTopics.REPORT, report.encode());
            }
            assemble(definition, round);
        } catch (IllegalArgumentException equivocation) {
            log.warn("Rejected observation report equivocation for round {}/{}",
                    HexUtil.encodeHexString(round.subscriptionId()), round.roundNumber());
        }
    }

    private void assemble(ObservationDefinition definition, ObservationRound round) {
        Map<ValueKey, List<ObservationReport>> groups = new LinkedHashMap<>();
        for (ObservationReport report : journal.reports(round.subscriptionId(),
                round.roundNumber(), profile.maxReportsPerRound())) {
            groups.computeIfAbsent(new ValueKey(report), ignored -> new ArrayList<>()).add(report);
        }
        for (List<ObservationReport> reports : groups.values()) {
            reports.sort(REPORT_ORDER);
            if (reports.size() < round.reportThreshold()) continue;
            List<ObservationReport> selected = List.copyOf(
                    reports.subList(0, round.reportThreshold()));
            byte[] output = selected.getFirst().value();
            byte[] resultId = ObservationHashes.resultId(round.subscriptionId(),
                    round.roundNumber(), round.definitionDigest(), ObservationResultStatus.VALUE,
                    ObservationHashes.digest(output));
            ObservationCertificate certificate = new ObservationCertificate(1,
                    round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                    round.definitionDigest(), round.policyDigest(), round.sourceSetDigest(),
                    selected, output, new byte[0], resultId);
            acceptCertificate(certificate, false);
            return;
        }
    }

    private void acceptCertificate(ObservationCertificate certificate, boolean rediffuse) {
        ObservationRound round = reader.round(certificate.subscriptionId(),
                certificate.roundNumber()).orElse(null);
        ObservationSubscription subscription = reader.subscription(
                certificate.subscriptionId()).orElse(null);
        if (round == null || subscription == null
                || subscription.status() != ObservationSubscriptionStatus.ACTIVE
                || subscription.nextRoundNumber() != round.roundNumber()) {
            return;
        }
        ObservationDefinition definition = definition(round.definitionDigest());
        if (!validCertificate(definition, round, certificate)) return;
        boolean added = journal.persistCertificate(certificate);
        if (added) certificatesReady.incrementAndGet();
        if (added) {
            diffusion.accept(ObservationTopics.CERTIFICATE, certificate.encode());
        }
    }

    private boolean validReport(ObservationDefinition definition, ObservationRound round,
                                ObservationReport report) {
        AppChainMembershipEpoch epoch = membership(round.openingHeight());
        return Arrays.equals(report.chainGenesisId(), chainGenesisId)
                && report.chainId().equals(chainId)
                && Arrays.equals(report.consensusProfileDigest(), consensusProfileDigest)
                && Arrays.equals(report.observationProfileDigest(), profile.digest())
                && Arrays.equals(report.definitionDigest(), definition.digest())
                && Arrays.equals(report.subscriptionId(), round.subscriptionId())
                && report.roundNumber() == round.roundNumber()
                && Arrays.equals(report.membershipDigest(), round.membershipDigest())
                && Arrays.equals(report.reporterSetDigest(), round.reporterSetDigest())
                && epoch.members().contains(HexUtil.encodeHexString(
                report.reporterPublicKey()).toLowerCase(Locale.ROOT))
                && report.value().length <= definition.maxValueBytes()
                && report.evidence().length <= definition.maxEvidenceBytes()
                && report.encode().length <= profile.maxReportBytes()
                && AppMessageSigner.verify(report.signature(), report.signingDigest(),
                report.reporterPublicKey())
                && settings.verifierRegistry().evidenceVerifier(definition)
                .verify(definition, round, report);
    }

    private boolean validCertificate(ObservationDefinition definition, ObservationRound round,
                                     ObservationCertificate certificate) {
        AppChainMembershipEpoch epoch = membership(round.openingHeight());
        List<byte[]> reporters = epoch.members().stream().map(HexUtil::decodeHexString).toList();
        return ObservationCertificateVerifier.verify(definition, round, certificate, profile,
                chainGenesisId, chainId, consensusProfileDigest, reporters,
                (publicKey, digest, signature) ->
                        AppMessageSigner.verify(signature, digest, publicKey),
                settings.verifierRegistry().evidenceVerifier(definition),
                settings.verifierRegistry().policy(definition));
    }

    private AppChainMembershipEpoch membership(long height) {
        MemberGroup.Epoch epoch = members.epochAt(height);
        return new AppChainMembershipEpoch(epoch.fromHeight(), new ArrayList<>(epoch.members()),
                epoch.threshold());
    }

    private ObservationDefinition definition(byte[] digest) {
        return profile.definitions().stream()
                .filter(candidate -> Arrays.equals(candidate.digest(), digest)).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Observation round references unknown definition"));
    }

    private static ObservationReport copyWithSignature(ObservationReport report, byte[] signature) {
        return new ObservationReport(report.version(), report.chainGenesisId(), report.chainId(),
                report.consensusProfileDigest(), report.observationProfileDigest(),
                report.definitionDigest(), report.subscriptionId(), report.roundNumber(),
                report.membershipDigest(), report.reporterSetDigest(), report.reporterPublicKey(),
                report.sourceId(), report.value(), report.evidence(), report.sourceVersion(),
                report.freshnessAnchorType(), report.freshnessAnchor(), signature);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        coordinator.shutdownNow();
        workers.shutdownNow();
        boolean interrupted = false;
        while (!coordinator.isTerminated() || !workers.isTerminated()) {
            try {
                coordinator.awaitTermination(1, TimeUnit.SECONDS);
                workers.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                interrupted = true;
                coordinator.shutdownNow();
                workers.shutdownNow();
            }
        }
        providers.close();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final Comparator<ObservationReport> REPORT_ORDER = (left, right) -> {
        int source = Arrays.compareUnsigned(left.sourceId(), right.sourceId());
        return source != 0 ? source
                : Arrays.compareUnsigned(left.reporterPublicKey(), right.reporterPublicKey());
    };
    private static final Comparator<ObservationCertificate> CERTIFICATE_ORDER = (left, right) -> {
        int value = Arrays.compareUnsigned(left.subscriptionId(), right.subscriptionId());
        if (value != 0) return value;
        value = Long.compare(left.roundNumber(), right.roundNumber());
        if (value != 0) return value;
        value = Arrays.compareUnsigned(left.resultId(), right.resultId());
        return value != 0 ? value : Arrays.compareUnsigned(left.digest(), right.digest());
    };

    private record RoundKey(byte[] subscriptionId, long roundNumber) {
        private RoundKey {
            subscriptionId = subscriptionId.clone();
        }
        @Override public byte[] subscriptionId() { return subscriptionId.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof RoundKey key && roundNumber == key.roundNumber
                    && Arrays.equals(subscriptionId, key.subscriptionId);
        }
        @Override public int hashCode() {
            return 31 * Arrays.hashCode(subscriptionId) + Long.hashCode(roundNumber);
        }
    }

    private record ValueKey(byte[] sourceId, byte[] value, byte[] sourceVersion,
                            int freshnessType, long freshnessAnchor) {
        private ValueKey(ObservationReport report) {
            this(report.sourceId(), report.value(), report.sourceVersion(),
                    report.freshnessAnchorType(), report.freshnessAnchor());
        }
        private ValueKey {
            sourceId = sourceId.clone();
            value = value.clone();
            sourceVersion = sourceVersion.clone();
        }
        @Override public boolean equals(Object other) {
            return other instanceof ValueKey key && freshnessType == key.freshnessType
                    && freshnessAnchor == key.freshnessAnchor
                    && Arrays.equals(sourceId, key.sourceId)
                    && Arrays.equals(value, key.value)
                    && Arrays.equals(sourceVersion, key.sourceVersion);
        }
        @Override public int hashCode() {
            int result = Arrays.hashCode(sourceId);
            result = 31 * result + Arrays.hashCode(value);
            result = 31 * result + Arrays.hashCode(sourceVersion);
            result = 31 * result + freshnessType;
            return 31 * result + Long.hashCode(freshnessAnchor);
        }
    }
}
