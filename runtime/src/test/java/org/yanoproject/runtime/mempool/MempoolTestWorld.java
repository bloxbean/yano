package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.util.Phase2EnvDigest;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.chain.MempoolAdmissionLimits;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Fixtures for the ADR-056 Phase 6a mempool gates: a canonical "chain" of {@link MutationWorld} ledger views
 * (each publication is a new generation), the java engine with a stub phase-2 evaluator, and a rebuild executor
 * that holds the worker (tasks queue up and run only when the test says so).
 */
final class MempoolTestWorld {

    static final long SLOT = MutationWorld.SLOT;
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    /** The canonical chain: a view per generation, published like the gate publishes. */
    static final class Chain implements MempoolBaseSource {
        private volatile LedgerView world;
        private volatile long generation = 1;
        private volatile int targetEpoch = 0;
        private final List<Consumer<CanonicalMark>> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger freed = new AtomicInteger();
        final ThreadLocal<Boolean> gateHeld = ThreadLocal.withInitial(() -> false);
        volatile boolean unavailable;
        /** Lock-order probe: set to the mempool's lane check; acquisitions under the lane are counted. */
        volatile BooleanSupplier laneHeld = () -> false;
        final AtomicInteger acquiredUnderLane = new AtomicInteger();

        Chain(LedgerView world) {
            this.world = world;
        }

        /** A canonical publication (forward block, rollback): a new generation with {@code next} as state. */
        void publish(LedgerView next) {
            world = next;
            generation++;
            listeners.forEach(l -> l.accept(current()));
        }

        /** The next slot crossing into another epoch, without a new generation (only the target epoch moves). */
        void crossEpoch() {
            targetEpoch++;
            listeners.forEach(l -> l.accept(current()));
        }

        LedgerView world() {
            return world;
        }

        int liveBases() {
            return acquired.get() - freed.get();
        }

        @Override
        public MempoolBase acquire() {
            if (laneHeld.getAsBoolean()) {
                acquiredUnderLane.incrementAndGet();
            }
            if (unavailable) {
                return MempoolBase.unavailable("test: no canonical state", current());
            }
            acquired.incrementAndGet();
            return MempoolBase.of(world, current(), SLOT, SLOT, freed::incrementAndGet);
        }

        @Override
        public CanonicalMark current() {
            return new CanonicalMark(generation, targetEpoch);
        }

        @Override
        public boolean isHeldByCurrentThread() {
            return gateHeld.get();
        }

        @Override
        public AutoCloseable onPublication(Consumer<CanonicalMark> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }

    /** Queues tasks until {@link #runAll()}: the rebuild worker is held. */
    static final class HeldExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            queue.add(command);
        }

        synchronized int queued() {
            return queue.size();
        }

        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = queue.poll();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    /** A phase-2 evaluator with a switchable verdict that counts script runs. */
    static final class StubEvaluator implements ScriptPhaseEvaluator {
        volatile ScriptPhaseResult result = new ScriptPhaseResult.Passed(List.of());
        final AtomicInteger evaluations = new AtomicInteger();

        @Override
        public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                          ProtocolParams params, SlotConfig slotConfig) {
            evaluations.incrementAndGet();
            return result;
        }

        @Override
        public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                           ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
            return List.of();
        }

        @Override
        public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
            return true;
        }
    }

    static ValidationEnvFactory envFactory() {
        SlotConfig slotConfig = MutationWorld.env().slotConfig();
        return (slot, view) -> {
            ProtocolParams params = view.protocolParams().require("protocol parameters");
            return new ValidationEnv(slot, 0, params.getProtocolMajorVer(), params.getProtocolMinorVer(),
                    NetworkId.TESTNET, slotConfig, Phase2EnvDigest.of(params));
        };
    }

    static LedgerMempool mempool(Chain chain, ScriptPhaseEvaluator evaluator, Executor executor,
                                 LedgerMempool.Settings settings) {
        return new LedgerMempool(new JavaLedgerValidationEngine(evaluator), envFactory(), chain, executor, null,
                settings);
    }

    /** The world with {@code extraUtxos} more 10 ADA outputs at {@code dev-42} ({@link #extraInput(int)}). */
    static InMemoryLedgerView world(ProtocolParams params, int extraUtxos) {
        InMemoryLedgerView.Builder builder = MutationWorld.builder(params);
        String owner = TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK);
        for (int i = 0; i < extraUtxos; i++) {
            TransactionInput in = extraInput(i);
            builder.utxo(in.getTransactionId(), in.getIndex(), MutationWorld.output(owner, ADA.multiply(BigInteger.TEN)));
        }
        return builder.build();
    }

    static TransactionInput extraInput(int i) {
        String hex = String.format("%064x", 0xE000_0000L + i);
        return new TransactionInput(hex, 0);
    }

    /** A payment from {@code input} (owned by {@code dev-42}) of {@code lovelace} to {@code dev-aa}. */
    static TxSpec payment(TransactionInput input, BigInteger lovelace) {
        TxSpec spec = new TxSpec();
        spec.inputs.add(input);
        spec.outputs.add(MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK), lovelace));
        spec.changeAddress = TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK);
        spec.ttl = MutationWorld.TTL;
        spec.signers.add(TestKey.DEV_42);
        return spec;
    }

    /** {@link #payment} with certificates; {@code implicit} is added to the change (deposits negative). */
    static TxSpec withCerts(TxSpec spec, BigInteger implicit, Certificate... certificates) {
        spec.certs.addAll(List.of(certificates));
        spec.changeAdjust = implicit;
        return spec;
    }

    static byte[] build(TxSpec spec, LedgerView view) {
        return ConwayTxBuilder.build(spec, view).cbor();
    }

    static String hash(byte[] tx) {
        return TransactionUtil.getTxHash(tx);
    }

    static MempoolAdmissionResult admit(LedgerMempool mempool, byte[] tx) {
        return mempool.tryAdmit(tx, TxValidationRequest.Origin.LOCAL, null, MempoolAdmissionLimits.unbounded(), null);
    }

    static MempoolAdmissionResult admit(LedgerMempool mempool, byte[] tx, TxValidationRequest.Origin origin) {
        return mempool.tryAdmit(tx, origin, null, MempoolAdmissionLimits.unbounded(), null);
    }

    /** The canonical state after {@code txs} were confirmed on {@code world} (their effects applied). */
    static LedgerView confirm(LedgerView world, ScriptPhaseEvaluator evaluator, byte[]... txs) {
        OverlayLedgerView view = OverlayLedgerView.over(world);
        JavaLedgerValidationEngine engine = new JavaLedgerValidationEngine(evaluator);
        for (byte[] tx : txs) {
            ValidationEnv env = envFactory().create(SLOT, view);
            TxValidationOutcome outcome = engine.validate(new TxValidationRequest(tx, view, env,
                    TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
            if (!(outcome instanceof TxValidationOutcome.Valid valid)) {
                throw new IllegalStateException("cannot confirm " + hash(tx) + ": " + outcome);
            }
            view = view.apply(valid.effects());
        }
        return view;
    }

    private MempoolTestWorld() {
    }
}
