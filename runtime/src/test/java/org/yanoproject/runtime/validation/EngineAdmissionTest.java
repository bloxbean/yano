package org.yanoproject.runtime.validation;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.events.TransactionValidateEvent;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.TransactionValidator;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.blockproducer.TransactionValidationException;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshotSource;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;
import org.yanoproject.runtime.tx.TxSubsystem;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 step 1d: mempool admission through the validation-engine API, end to end through
 * {@link TxSubsystem} (the validation event, the default listener, the mempool lane), with fake engines.
 */
class EngineAdmissionTest {

    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final long TIP_SLOT = 1_000;
    private static final long EPOCH = 432_000;

    private final PropagatingEventBus eventBus = new PropagatingEventBus();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger released = new AtomicInteger();
    private final List<TxSubsystem> subsystems = new ArrayList<>();

    @AfterEach
    void tearDown() {
        subsystems.forEach(TxSubsystem::close);
        eventBus.close();
        scheduler.shutdownNow();
    }

    @Test
    void admissionRunsTheEngineOnATickedSnapshotAndReleasesIt() {
        CanonicalStateGate gate = gate(true);
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate, engines(engine, List.of(), null));
        Outpoint canonicalInput = new Outpoint("aa".repeat(32), 0);

        String hash = subsystem.submitTransaction(tx(canonicalInput, 1_000_000), null);

        assertThat(subsystem.containsTransaction(hash)).isTrue();
        TxValidationRequest request = engine.requests.getFirst();
        assertThat(request.rule()).isEqualTo(TxValidationRequest.Rule.MEMPOOL);
        assertThat(request.origin()).isEqualTo(TxValidationRequest.Origin.LOCAL);
        assertThat(request.previous()).isNull();
        assertThat(request.env().currentSlot()).isEqualTo(TIP_SLOT + 1);
        // The canonical read went to the snapshot (no UTxO store in this capture: fail closed, never absent).
        assertThat(engine.utxoReads.getFirst().isUnavailable()).isTrue();
        assertThat(gate.liveSnapshotCount()).isZero();
        assertThat(released.get()).isEqualTo(1);
        assertThat(subsystem.health().details()).containsEntry("validationEngine", "amaru");
    }

    @Test
    void chainedMempoolOutputsResolveThroughTheMempool() {
        CanonicalStateGate gate = gate(true);
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate, engines(engine, List.of(), null));
        String parent = subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        subsystem.submitTransaction(tx(new Outpoint(parent, 0), 900_000), null);

        Lookup<UtxoEntry> chained = engine.utxoReads.getLast();
        assertThat(chained.isPresent()).isTrue();
        assertThat(((Lookup.Present<UtxoEntry>) chained).value().output().getValue().getCoin())
                .isEqualTo(BigInteger.valueOf(1_000_000));
    }

    @Test
    void rejectionsKeepTheLegacyRejectionPath() {
        CanonicalStateGate gate = gate(true);
        TxSubsystem subsystem = subsystem(gate, engines(new RecordingEngine("amaru", Verdict.REJECT),
                List.of(), null));

        assertThatThrownBy(() -> subsystem.admitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000),
                "txsubmission"))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("UTXO.BadInputsUTxO");
        assertThat(gate.liveSnapshotCount()).isZero();
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    void peerOriginsAreMappedToPeer() {
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate(true), engines(engine, List.of(), null));

        subsystem.admitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), "tx-diffusion:peer-1");

        assertThat(engine.requests.getFirst().origin()).isEqualTo(TxValidationRequest.Origin.PEER);
        assertThat(EngineAdmission.origin("txsubmission")).isEqualTo(TxValidationRequest.Origin.PEER);
        assertThat(EngineAdmission.origin("rest-api")).isEqualTo(TxValidationRequest.Origin.LOCAL);
    }

    @Test
    void withoutASnapshotAdmissionFailsClosed() {
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate(false), engines(engine, List.of(), null));

        assertThatThrownBy(() -> subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining(LedgerFailure.LEDGER_STATE_UNAVAILABLE);
        assertThat(engine.requests).isEmpty();
    }

    @Test
    void shadowDisagreementIsCountedDumpedAndReleasesItsSnapshot(@TempDir Path dumps) throws Exception {
        CanonicalStateGate gate = gate(true);
        RecordingEngine admission = new RecordingEngine("scalus", Verdict.ACCEPT);
        RecordingEngine agreeing = new RecordingEngine("agree", Verdict.ACCEPT);
        RecordingEngine shadow = new RecordingEngine("amaru", Verdict.REJECT);
        ValidationEngines engines = engines(admission, List.of(agreeing, shadow), dumps);
        TxSubsystem subsystem = subsystem(gate, engines);

        String hash = subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        ShadowValidationRunner runner = engines.shadowRunner();
        awaitTrue(() -> runner.stats().compared() == 2 && gate.liveSnapshotCount() == 0);
        // The shadow retained the admission's own snapshot: one capture, freed once, after both finished.
        assertThat(released.get()).isEqualTo(1);
        assertThat(runner.stats().agreements()).isEqualTo(1);
        assertThat(runner.disagreements("amaru", "UTXO")).isEqualTo(1);
        assertThat(runner.disagreements("agree", "UTXO")).isZero();
        assertThat(gate.liveSnapshotCount()).isZero();
        // The shadow saw the input the admission view resolved (pinned), and the same slot.
        assertThat(shadow.requests.getFirst().env()).isEqualTo(admission.requests.getFirst().env());
        awaitTrue(() -> runner.stats().dumpsWritten() == 1);
        Path file;
        try (Stream<Path> files = Files.list(dumps)) {
            file = files.filter(p -> p.getFileName().toString().endsWith("-amaru.json")).findFirst().orElseThrow();
        }
        ShadowDumpBundle bundle = ShadowDumpBundle.read(file);
        assertThat(bundle.txHash()).isEqualTo(hash);
        assertThat(bundle.admission().verdict().valid()).isTrue();
        assertThat(bundle.shadow().verdict().label()).isEqualTo("UTXO.BadInputsUTxO");
        assertThat(bundle.reads()).anyMatch(r -> r.method().equals("utxo"));
    }

    @Test
    void shadowTasksAreDroppedAtTheSnapshotCap() {
        CanonicalStateGate gate = gate(true);
        gate.setMaxLiveSnapshots(1);
        RecordingEngine shadow = new RecordingEngine("amaru", Verdict.REJECT);
        ValidationEngines engines = engines(new RecordingEngine("scalus", Verdict.ACCEPT), List.of(shadow), null);
        TxSubsystem subsystem = subsystem(gate, engines);

        subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        assertThat(engines.shadowRunner().stats().droppedCap()).isEqualTo(1);
        assertThat(gate.shadowRefusals()).isEqualTo(1);
        assertThat(shadow.requests).isEmpty();
        assertThat(gate.liveSnapshotCount()).isZero();
    }

    @Test
    void noEnginesKeepsTheLegacyPath() {
        TxSubsystem subsystem = new TxSubsystem(eventBus, scheduler, RuntimeOptions.defaults(),
                EngineAdmissionTest::emptyUtxoState, LoggerFactory.getLogger(getClass()));
        subsystems.add(subsystem);
        subsystem.setTransactionEvaluator((txCbor, inputs) -> ValidationResult.success());
        subsystem.start();
        assertThat(subsystem.transactionValidationService().engineAdmission()).isNull();
        assertThat(subsystem.validationEngines()).isNull();
    }


    @Test
    void producerBoundaryWindowValidatesAtTheLedgerEpoch() {
        // The producer applied the boundary into epoch 1 while the tip is still in epoch 0 (ADR-056 6b).
        CanonicalStateGate gate = gate(true, 2 * EPOCH / 3, 1);
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate, engines(engine, List.of(), null));

        subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        ValidationEnv env = engine.requests.getFirst().env();
        assertThat(env.currentSlot()).isEqualTo(EPOCH);
        assertThat(env.currentEpoch()).isEqualTo(1);
        // Canonical (unticked) view: the UTxO read reaches the capture, it is not refused as "behind".
        assertThat(((Lookup.Unavailable<UtxoEntry>) engine.utxoReads.getFirst()).reason())
                .contains("UTxO store is disabled");
        assertThat(gate.liveSnapshotCount()).isZero();
    }

    @Test
    void lastSlotOfAnEpochAdmitsAgainstTheTickedView() {
        CanonicalStateGate gate = gate(true, EPOCH - 1, -1);
        RecordingEngine engine = new RecordingEngine("amaru", Verdict.ACCEPT);
        TxSubsystem subsystem = subsystem(gate, engines(engine, List.of(), null));
        AtomicInteger contexts = new AtomicInteger();
        eventBus.subscribe(TransactionValidateEvent.class, ctx -> {
            AdmissionContext context = AdmissionContext.current();
            if (context != null && context.view().mode() == TickedLedgerView.Mode.TICKED) {
                contexts.incrementAndGet();
            }
        }, SubscriptionOptions.builder().build());

        subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        assertThat(contexts.get()).isEqualTo(1);
        assertThat(engine.requests.getFirst().env().currentSlot()).isEqualTo(EPOCH);
        assertThat(engine.requests.getFirst().env().currentEpoch()).isEqualTo(1);
        assertThat(gate.liveSnapshotCount()).isZero();
    }

    @Test
    void shadowsNextToTheLegacyValidatorNeverChangeAdmission() throws Exception {
        CanonicalStateGate gate = gate(true);
        RecordingEngine shadow = new RecordingEngine("amaru", Verdict.REJECT);
        ValidationEngines engines = engines(null, List.of(shadow), null);
        AtomicInteger legacyCalls = new AtomicInteger();
        TxSubsystem subsystem = subsystem(gate, engines, (txCbor, inputs) -> {
            legacyCalls.incrementAndGet();
            return ValidationResult.success();
        });

        String hash = subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

        assertThat(subsystem.containsTransaction(hash)).as("the legacy verdict admits").isTrue();
        assertThat(legacyCalls.get()).isEqualTo(1);
        ShadowValidationRunner runner = engines.shadowRunner();
        awaitTrue(() -> runner.stats().compared() == 1 && gate.liveSnapshotCount() == 0);
        assertThat(runner.disagreements("amaru", "UTXO")).isEqualTo(1);
        assertThat(subsystem.health().details()).containsEntry("validationEngine", ValidationEngines.LEGACY);
    }

    @Test
    void legacyRejectionsCompareByMappedConstructorOrVerdictOnly() throws Exception {
        CanonicalStateGate gate = gate(true);
        RecordingEngine shadow = new RecordingEngine("amaru", Verdict.REJECT);
        ValidationEngines engines = engines(null, List.of(shadow), null);
        AtomicInteger n = new AtomicInteger();
        TxSubsystem subsystem = subsystem(gate, engines, (txCbor, inputs) -> ValidationResult.failure(
                new ValidationError(n.getAndIncrement() == 0 ? "BadInputsUTxO" : "FeesOk", "legacy",
                        ValidationError.Phase.PHASE_1)));

        assertThatThrownBy(() -> subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null))
                .isInstanceOf(TransactionValidationException.class);
        assertThatThrownBy(() -> subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 1), 1_000_000), null))
                .isInstanceOf(TransactionValidationException.class);

        ShadowValidationRunner runner = engines.shadowRunner();
        awaitTrue(() -> runner.stats().compared() == 2 && gate.liveSnapshotCount() == 0);
        // BadInputsUTxO maps to UTXO.BadInputsUTxO (agrees); FeesOk has no single constructor (verdict only).
        assertThat(runner.stats().agreements()).isEqualTo(2);
        assertThat(runner.stats().disagreementTotal()).isZero();
    }

    @Test
    void legacyShadowSnapshotsObeyTheCap() {
        CanonicalStateGate gate = gate(true);
        gate.setMaxLiveSnapshots(1);
        CanonicalSnapshot held = gate.acquireSnapshot(SnapshotPurpose.ADMISSION).require("snapshot");
        try {
            RecordingEngine shadow = new RecordingEngine("amaru", Verdict.REJECT);
            ValidationEngines engines = engines(null, List.of(shadow), null);
            TxSubsystem subsystem = subsystem(gate, engines, (txCbor, inputs) -> ValidationResult.success());

            subsystem.submitTransaction(tx(new Outpoint("aa".repeat(32), 0), 1_000_000), null);

            assertThat(engines.shadowRunner().stats().droppedCap()).isEqualTo(1);
            assertThat(shadow.requests).isEmpty();
        } finally {
            held.release();
        }
    }

    // ------------------------------------------------------------------ fixtures

    enum Verdict { ACCEPT, REJECT }

    /** Records its requests and the UTxO reads it made, and returns a fixed verdict. */
    static final class RecordingEngine implements LedgerValidationEngine {
        final String name;
        final Verdict verdict;
        final List<TxValidationRequest> requests = new CopyOnWriteArrayList<>();
        final List<Lookup<UtxoEntry>> utxoReads = new CopyOnWriteArrayList<>();

        RecordingEngine(String name, Verdict verdict) {
            this.name = name;
            this.verdict = verdict;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TxValidationOutcome validate(TxValidationRequest request) {
            requests.add(request);
            try {
                Transaction tx = Transaction.deserialize(request.txCbor());
                for (TransactionInput in : tx.getBody().getInputs()) {
                    utxoReads.add(request.view().utxo(new Outpoint(in.getTransactionId(), in.getIndex())));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (verdict == Verdict.REJECT) {
                return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO",
                        LedgerFailure.Phase.PHASE_1, "fake"));
            }
            byte[] txCbor = request.txCbor();
            return new TxValidationOutcome.Valid(new TxEffects(TxIdentity.txIdHex(txCbor), true, List.of(),
                    List.of(), List.of()), new ValidatedTx(txCbor, TxIdentity.txId(txCbor), 10, 1, new byte[32], true,
                    request.origin()), false);
        }
    }

    private ValidationEngines engines(LedgerValidationEngine admission, List<LedgerValidationEngine> shadows,
                                      Path dumpDir) {
        ValidationEngineSettings settings = new ValidationEngineSettings(admission != null ? admission.name()
                : "scalus", shadows.stream().map(LedgerValidationEngine::name).toList(), dumpDir, false, 30_000, 4);
        ValidationEnvFactory envFactory = (slot, view) -> new ValidationEnv(slot, slot / EPOCH, 10, 0,
                NetworkId.TESTNET, new SlotConfig(1000, 0, 1_600_000_000_000L), new byte[32]);
        return new ValidationEngines(settings, admission, shadows, envFactory, shadows.isEmpty() ? null
                : new ShadowValidationRunner(shadows, dumpDir, 30_000, 2, 16));
    }

    private CanonicalStateGate gate(boolean withSource) {
        return gate(withSource, TIP_SLOT, -1);
    }

    /**
     * @param completedBoundary the ledger's last completed boundary epoch (-1: none), as a producer's early
     *                          boundary section records it
     */
    private CanonicalStateGate gate(boolean withSource, long tipSlot, int completedBoundary) {
        CanonicalStateGate gate = new CanonicalStateGate(() -> new ChainTip(tipSlot, HexUtil.decodeHexString(
                "cc".repeat(32)), 10));
        gate.configureEpochCalculator(slot -> (int) (slot / EPOCH));
        gate.configureEpochStartSlot(epoch -> epoch * EPOCH);
        gate.configureLedgerEpochReader(() -> completedBoundary);
        gate.refreshTip();
        if (withSource) {
            gate.installSnapshotSource(tip -> new CanonicalSnapshotSource.Captured(null, null, null,
                    released::incrementAndGet));
        }
        return gate;
    }

    private TxSubsystem subsystem(CanonicalStateGate gate, ValidationEngines engines) {
        return subsystem(gate, engines, (txCbor, inputs) -> {
            throw new AssertionError("the legacy validator must not run in engine mode");
        });
    }

    private TxSubsystem subsystem(CanonicalStateGate gate, ValidationEngines engines, TransactionValidator legacy) {
        TxSubsystem subsystem = new TxSubsystem(eventBus, scheduler, RuntimeOptions.defaults(),
                EngineAdmissionTest::emptyUtxoState, LoggerFactory.getLogger(getClass()));
        subsystems.add(subsystem);
        subsystem.setTransactionEvaluator(legacy);
        subsystem.setValidationEngines(engines, () -> gate);
        subsystem.start();
        eventBus.subscribe(TransactionValidateEvent.class, ctx -> { }, SubscriptionOptions.builder().build());
        return subsystem;
    }

    private static byte[] tx(Outpoint input, long lovelace) {
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(input.txHash(), input.index())))
                .outputs(List.of(new TransactionOutput(ADDRESS, new Value(BigInteger.valueOf(lovelace), null))))
                .fee(BigInteger.valueOf(200_000)).build();
        try {
            return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(10);
        }
    }

    /**
     * A live UTxO store holding a 2 ADA output at every outpoint: only the legacy path reads it (engine mode
     * resolves canonical inputs from its snapshot).
     */
    static UtxoState emptyUtxoState() {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.of(new Utxo(outpoint, ADDRESS, BigInteger.valueOf(2_000_000), List.of(), null, null,
                        null, null, false, 0, 0, null));
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }
}
