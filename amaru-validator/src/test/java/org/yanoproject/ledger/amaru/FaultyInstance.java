package org.yanoproject.ledger.amaru;

import org.yanoproject.ledger.amaru.runtime.AmaruInstance;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A real module instance with fault injection and call recording, for the engine's failure-path tests.
 */
final class FaultyInstance implements AmaruInstance {

    enum Fault {
        NONE,
        /** {@code validate} with an out-of-bounds buffer: a real trap inside the wasm guest. */
        TRAP,
        /** {@code validate} spins, honouring the interrupt flag the way Endive's compiled code does. */
        LOOP_INTERRUPTIBLE,
        /** {@code validate} blocks and ignores interrupts until {@link Shared#release} opens. */
        STUCK
    }

    /** State shared by all instances of one engine. */
    static final class Shared {
        final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger requiredKeysCalls = new AtomicInteger();
        final AtomicInteger validateCalls = new AtomicInteger();
        final List<byte[]> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);

        Supplier<AmaruInstance> factory() {
            return () -> {
                created.incrementAndGet();
                return new FaultyInstance(this, new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES));
            };
        }
    }

    private final Shared shared;
    private final WasmAmaruInstance delegate;

    private FaultyInstance(Shared shared, WasmAmaruInstance delegate) {
        this.shared = shared;
        this.delegate = delegate;
    }

    @Override
    public int abiVersion() {
        return delegate.abiVersion();
    }

    @Override
    public String amaruVersion() {
        return delegate.amaruVersion();
    }

    @Override
    public byte[] requiredKeys(byte[] transaction, byte[] env) {
        shared.requiredKeysCalls.incrementAndGet();
        return delegate.requiredKeys(transaction, env);
    }

    @Override
    public byte[] validate(byte[] request) {
        shared.validateCalls.incrementAndGet();
        shared.requests.add(request.clone());
        switch (shared.fault.getAndSet(Fault.NONE)) {
            case TRAP -> delegate.callValidateOutOfBounds();
            case LOOP_INTERRUPTIBLE -> {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.onSpinWait();
                }
                throw new IllegalStateException("interrupted");
            }
            case STUCK -> {
                while (true) {
                    try {
                        shared.release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Stuck outside wasm code: the interrupt is not honoured.
                    }
                }
            }
            case NONE -> {
            }
        }
        return delegate.validate(request);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
