package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationsTracker;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.IpfsPinDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.ipfs.config.IpfsEffectConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClientFactory;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsProviderException;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Executes the frozen Kubo-backed {@code ipfs.pin} v1 action. */
public final class IpfsPinExecutor implements AppEffectExecutor {
    /** Exact action routed by this executor. */
    public static final String TYPE = ConnectorTypes.IPFS_PIN;

    private static final String ID = "ipfs-pin";

    private final IpfsEffectConfig config;
    private final Function<IpfsEffectConfig.Target, IpfsPinClientFactory> clientFactories;
    private final ConnectorDetailArchive detailArchive;
    private final Map<String, Semaphore> targetPermits;
    private final Duration closeDuration;
    private final Object lifecycleLock = new Object();
    private final Map<String, IpfsPinClient> clients = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final EffectExecutorOperationsTracker operations =
            new EffectExecutorOperationsTracker();

    /**
     * Creates a lifecycle-owned pin executor.
     *
     * @param config strict immutable target configuration
     * @param clientFactories maps a target to a bound fresh-client factory
     * @param detailArchive optional durable detail archive owned by this executor
     */
    public IpfsPinExecutor(
            IpfsEffectConfig config,
            Function<IpfsEffectConfig.Target, IpfsPinClientFactory> clientFactories,
            ConnectorDetailArchive detailArchive) {
        this.config = Objects.requireNonNull(config, "config");
        this.clientFactories = Objects.requireNonNull(clientFactories, "clientFactories");
        this.detailArchive = detailArchive;
        Map<String, Semaphore> permits = new LinkedHashMap<>();
        config.targets().keySet().forEach(alias -> permits.put(alias, new Semaphore(1, true)));
        this.targetPermits = Map.copyOf(permits);
        this.closeDuration = config.targets().values().stream()
                .map(IpfsEffectConfig.Target::closeTimeout)
                .max(Duration::compareTo).orElse(Duration.ofSeconds(5));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
        return operations.observe(() -> executeAttempt(context, effect));
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return operations.snapshot();
    }

    private EffectExecution executeAttempt(EffectExecutionContext context, PendingEffect effect) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(effect, "effect");

        IpfsPinCommandV1 command;
        try {
            command = IpfsPinCommandV1.decode(effect.payload());
        } catch (ConnectorContractException invalid) {
            return failed(invalid.code());
        } catch (RuntimeException invalid) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }

        if (closed.get()) {
            return failed(ConnectorErrorCode.SHUTDOWN);
        }
        if (!TYPE.equals(effect.type())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        byte[] effectIdHash = effect.idHash();
        if (effectIdHash == null || effectIdHash.length != 32
                || !MessageDigest.isEqual(effectIdHash, effect.effectId().hash())) {
            return failed(ConnectorErrorCode.INVALID_PAYLOAD);
        }
        effectIdHash = effectIdHash.clone();
        if (!effect.record().chainId().equals(context.chainId())) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (!config.enabled()) {
            return failed(ConnectorErrorCode.TARGET_DISABLED);
        }

        Optional<IpfsEffectConfig.Target> configured = config.target(command.target());
        if (configured.isEmpty()) {
            return failed(ConnectorErrorCode.UNKNOWN_TARGET);
        }
        Optional<IpfsEffectConfig.Target> resolved;
        try {
            resolved = config.resolve(command);
        } catch (RuntimeException invalidPolicy) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (resolved.isEmpty()) {
            return failed(ConnectorErrorCode.POLICY_DENIED);
        }
        IpfsEffectConfig.Target target = resolved.orElseThrow();

        byte[] submittedRef = context.submittedRef();
        if (submittedRef == null) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        Semaphore permit = targetPermits.get(command.target());
        if (permit == null) {
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }
        if (!permit.tryAcquire()) {
            return new EffectExecution.Retry(Duration.ofMillis(100));
        }
        try {
            IpfsPinClient client;
            try {
                client = client(command.target(), target);
            } catch (IpfsProviderException providerFailure) {
                return failed(providerFailure.code());
            } catch (RuntimeException providerFailure) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
            }
            if (closed.get()) {
                return failed(ConnectorErrorCode.SHUTDOWN);
            }
            return submittedRef.length == 0
                    ? createOrReconcile(client, target, command, effectIdHash)
                    : resumeSubmitted(client, target, command, effectIdHash, submittedRef);
        } finally {
            permit.release();
        }
    }

    private EffectExecution resumeSubmitted(IpfsPinClient client,
                                            IpfsEffectConfig.Target target,
                                            IpfsPinCommandV1 command,
                                            byte[] effectIdHash,
                                            byte[] submittedRef) {
        IpfsPinReceiptV1 receipt;
        try {
            receipt = IpfsPinReceiptV1.decode(submittedRef);
            if (!MessageDigest.isEqual(receipt.targetFingerprint(),
                    target.targetFingerprint().bytes())
                    || !MessageDigest.isEqual(receipt.cidFingerprint(),
                    IpfsCidFingerprint.compute(command.cid()).bytes())) {
                return failed(ConnectorErrorCode.TARGET_CHANGED);
            }
        } catch (RuntimeException invalidReference) {
            // Kubo submitted state is only an already-confirmed receipt whose
            // optional detail archive failed. It never authorizes another pin.
            return failed(ConnectorErrorCode.INTERNAL_ERROR);
        }

        try {
            PinState state = client.probe(command.cid(), effectIdHash);
            if (!state.satisfies(command.recursive())) {
                return failed(ConnectorErrorCode.ACK_UNKNOWN);
            }
            return confirm(receipt, command, effectIdHash, state, true);
        } catch (IpfsProviderException providerFailure) {
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private EffectExecution createOrReconcile(IpfsPinClient client,
                                               IpfsEffectConfig.Target target,
                                               IpfsPinCommandV1 command,
                                               byte[] effectIdHash) {
        PinState before;
        try {
            before = client.probe(command.cid(), effectIdHash);
            if (before.satisfies(command.recursive())) {
                return confirm(target, command, effectIdHash, before, false);
            }
        } catch (IpfsProviderException providerFailure) {
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.INTERNAL_ERROR);
        }

        boolean acknowledgementUnknown = false;
        try {
            client.add(command.cid(), command.recursive(), effectIdHash);
        } catch (IpfsProviderException providerFailure) {
            if (providerFailure.code() != ConnectorErrorCode.ACK_UNKNOWN) {
                return failed(providerFailure.code());
            }
            acknowledgementUnknown = true;
        } catch (RuntimeException providerFailure) {
            acknowledgementUnknown = true;
        }

        try {
            PinState after = client.probe(command.cid(), effectIdHash);
            if (!after.satisfies(command.recursive())) {
                return failed(ConnectorErrorCode.ACK_UNKNOWN);
            }
            return confirm(target, command, effectIdHash, after, false);
        } catch (IpfsProviderException providerFailure) {
            if (acknowledgementUnknown
                    && providerFailure.code() == ConnectorErrorCode.SERVICE_UNAVAILABLE) {
                return failed(ConnectorErrorCode.ACK_UNKNOWN);
            }
            return failed(providerFailure.code());
        } catch (RuntimeException providerFailure) {
            return failed(closed.get()
                    ? ConnectorErrorCode.SHUTDOWN : ConnectorErrorCode.ACK_UNKNOWN);
        }
    }

    private EffectExecution confirm(IpfsEffectConfig.Target target,
                                    IpfsPinCommandV1 command,
                                    byte[] effectIdHash,
                                    PinState state,
                                    boolean submittedRefAlreadyPersisted) {
        IpfsPinReceiptV1 receipt = new IpfsPinReceiptV1(target.targetFingerprint(),
                IpfsCidFingerprint.compute(command.cid()));
        return confirm(receipt, command, effectIdHash, state,
                submittedRefAlreadyPersisted);
    }

    private EffectExecution confirm(IpfsPinReceiptV1 receipt,
                                    IpfsPinCommandV1 command,
                                    byte[] effectIdHash,
                                    PinState state,
                                    boolean submittedRefAlreadyPersisted) {
        byte[] externalRef = receipt.encode();
        if (detailArchive == null) {
            return EffectExecution.confirmed(externalRef);
        }
        try {
            ConnectorDetailDocumentV1 detail = ConnectorDetailDocumentV1.of(effectIdHash,
                    new IpfsPinDetailV1(receipt.targetFingerprint(), command.cid(),
                            state == PinState.RECURSIVE, null));
            ConnectorDetailHash detailHash = detailArchive.archive(detail);
            return EffectExecution.confirmed(externalRef, detailHash.bytes());
        } catch (IOException | RuntimeException archiveFailure) {
            if (submittedRefAlreadyPersisted) {
                return failed(closed.get()
                        ? ConnectorErrorCode.SHUTDOWN
                        : ConnectorErrorCode.DETAIL_ARCHIVE_FAILED);
            }
            return EffectExecution.submitted(externalRef);
        }
    }

    private IpfsPinClient client(String targetAlias, IpfsEffectConfig.Target target) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("executor is closed");
            }
            IpfsPinClient existing = clients.get(targetAlias);
            if (existing != null) {
                return existing;
            }
        }

        IpfsPinClientFactory boundFactory = Objects.requireNonNull(
                clientFactories.apply(target), "client factory");
        IpfsPinClient created = Objects.requireNonNull(
                boundFactory.open(), "client factory product");
        IpfsPinClient selected;
        boolean reject;
        synchronized (lifecycleLock) {
            reject = closed.get();
            IpfsPinClient existing = clients.get(targetAlias);
            if (reject) {
                selected = null;
            } else if (existing != null) {
                selected = existing;
            } else {
                clients.put(targetAlias, created);
                return created;
            }
        }
        closeOneBounded(created, target.closeTimeout());
        if (reject) {
            throw new IllegalStateException("executor is closed");
        }
        return selected;
    }

    private static EffectExecution failed(ConnectorErrorCode code) {
        return EffectExecution.failed(code.wireCode(), code.disposition().retryable());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<AutoCloseable> resources = new ArrayList<>();
        synchronized (lifecycleLock) {
            resources.addAll(clients.values());
            clients.clear();
        }
        if (detailArchive != null) {
            resources.add(detailArchive);
        }
        closeBounded(resources, closeDuration);
    }

    private static void closeOneBounded(AutoCloseable resource, Duration duration) {
        closeBounded(List.of(resource), duration);
    }

    private static void closeBounded(List<AutoCloseable> resources, Duration duration) {
        ExecutorService closer = Executors.newCachedThreadPool(task ->
                Thread.ofPlatform().daemon().name("ipfs-pin-close").unstarted(task));
        List<Future<?>> futures = new ArrayList<>(resources.size());
        for (AutoCloseable resource : resources) {
            futures.add(closer.submit(() -> {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Ownership is already fenced; close remains best effort.
                }
            }));
        }
        long started = System.nanoTime();
        try {
            for (Future<?> future : futures) {
                long remaining = duration.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) {
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException ignored) {
                    // The resource task contains its own failure.
                } catch (java.util.concurrent.TimeoutException timeout) {
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            futures.forEach(future -> future.cancel(true));
            closer.shutdownNow();
        }
    }
}
