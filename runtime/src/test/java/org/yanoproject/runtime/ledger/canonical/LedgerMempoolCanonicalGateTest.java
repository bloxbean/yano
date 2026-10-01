package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.chain.MempoolAdmissionLimits;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.mempool.CanonicalMark;
import org.yanoproject.runtime.mempool.GateMempoolBaseSource;
import org.yanoproject.runtime.mempool.LedgerMempool;
import org.yanoproject.runtime.mempool.MempoolAccess;
import org.yanoproject.runtime.utxo.UtxoTestRecords;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 6a gates of the ledger-state mempool against the real {@link CanonicalStateGate} over one real
 * RocksDB (the same stores and snapshot source as production): the ownership gate (a canonical UTxO outside the
 * rebuild's read set is admitted from the published state's retained snapshot; a frozen view keeps its generation
 * across two swaps; no leaked snapshots), and the synchronous fallback with real canonical writes arriving while
 * the lane is held (lock order: no violation, {@code CATCHING_UP}, back to {@code READY}).
 */
class LedgerMempoolCanonicalGateTest {

    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    @TempDir
    Path directory;

    private CanonicalTestStores stores;
    private LedgerMempool mempool;
    private final ConcurrentLinkedQueue<Runnable> heldWorker = new ConcurrentLinkedQueue<>();
    private final AtomicInteger readsOfU2 = new AtomicInteger();
    private long blocks;

    @BeforeEach
    void setUp() throws Exception {
        stores = new CanonicalTestStores(directory, false, epoch -> Optional.empty());
        block(100, () -> {
            for (int i = 0; i < 4; i++) {
                put(utxo(i), 5_000_000);
            }
        });
        Executor held = heldWorker::add;
        mempool = new LedgerMempool(new UtxoEngine(), envFactory(), new GateMempoolBaseSource(() -> stores.gate),
                held, null, new LedgerMempool.Settings(2, 2, 60_000));
        mempool.start();
    }

    @AfterEach
    void tearDown() {
        if (mempool != null) {
            mempool.close();
        }
        assertThat(stores.gate.liveSnapshotCount()).as("no leaked snapshots after close").isZero();
        stores.close();
    }

    @Test
    void ownershipGate() throws Exception {
        admit(spend(utxo(0)));
        assertThat(mempool.rebuildNow()).isTrue();
        long rebuiltAt = stores.gate.generation();

        // A block that does not touch U1 or U2; the rebuild folds only the transaction spending U0.
        block(101, () -> put(utxo(3), 6_000_000));
        readsOfU2.set(0);
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(readsOfU2.get()).as("U2 is outside the rebuild's read set").isZero();
        assertThat(stores.gate.liveSnapshotsByGeneration()).as("the rebuild released everything but the published base")
                .containsOnlyKeys(stores.gate.generation());

        // Admit a transaction spending a canonical UTxO no earlier transaction touched: read from the published
        // state's retained snapshot.
        MempoolAdmissionResult u2 = admit(spend(utxo(2)));
        assertThat(u2.status()).isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
        assertThat(readsOfU2.get()).isEqualTo(1);
        assertThat(stores.gate.generation()).isGreaterThan(rebuiltAt);
    }

    @Test
    void aFrozenViewKeepsItsGenerationAcrossTwoSwaps() throws Exception {
        admit(spend(utxo(0)));
        assertThat(mempool.rebuildNow()).isTrue();
        CapturedView frozen = CapturedView.capture(mempool);
        Outpoint u1 = new Outpoint(utxo(1), 0);
        assertThat(lovelace(frozen.view(), u1)).isEqualTo(5_000_000);

        block(101, () -> put(utxo(1), 7_000_000));
        assertThat(mempool.rebuildNow()).isTrue();
        block(102, () -> put(utxo(1), 8_000_000));
        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(lovelace(frozen.view(), u1)).as("the frozen view still reads its original generation")
                .isEqualTo(5_000_000);
        CapturedView current = CapturedView.capture(mempool);
        assertThat(lovelace(current.view(), u1)).isEqualTo(8_000_000);
        current.release().run();
        assertThat(stores.gate.liveSnapshotCount()).isEqualTo(2);
        frozen.release().run();
        assertThat(stores.gate.liveSnapshotCount()).as("back to the published baseline").isEqualTo(1);
    }

    @Test
    void synchronousFallbackWithRealCanonicalWritesKeepsTheLockOrder() throws Exception {
        admit(spend(utxo(0)));
        AtomicBoolean fastSync = new AtomicBoolean(true);
        List<String> events = new ArrayList<>();
        mempool.setObserver(new LedgerMempool.RebuildObserver() {
            @Override
            public void foldStarted(CanonicalMark target, int transactions, boolean synchronous) {
                events.add(synchronous ? "sync" : "fold");
                if (fastSync.get()) {
                    // A canonical writer on its own thread; with the lane held (synchronous) it must not need it.
                    CompletableFuture.runAsync(() -> block(200 + events.size(), () -> put(utxo(3), 1_000_000)))
                            .join();
                }
            }

            @Override
            public void discarded(CanonicalMark target, String reason) {
                events.add("discard");
            }
        });

        assertThat(mempool.rebuildNow()).isFalse();
        assertThat(events).containsExactly("fold", "discard", "fold", "discard", "sync", "discard", "sync",
                "discard");
        assertThat(mempool.status()).isEqualTo(LedgerMempool.Status.CATCHING_UP);
        assertThat(admit(spend(utxo(1))).status()).isEqualTo(MempoolAdmissionResult.Status.CATCHING_UP);
        assertThat(heldWorker).as("every canonical publication requested a (coalesced) rebuild").isNotEmpty();

        fastSync.set(false);
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(mempool.status()).isEqualTo(LedgerMempool.Status.READY);
        assertThat(mempool.ledgerStatus().lockOrderViolations()).isZero();
        assertThat(stores.gate.liveSnapshotCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    /** A frozen shadow view: the published overlay plus a retained reference to its base. */
    private record CapturedView(LedgerView view, Runnable release) {
        static CapturedView capture(LedgerMempool mempool) {
            MempoolAccess.State state = MempoolAccess.published(mempool);
            return new CapturedView(state.view(), state.release());
        }
    }

    private long lovelace(LedgerView view, Outpoint outpoint) {
        return view.utxo(outpoint).require("utxo").output().getValue().getCoin().longValueExact();
    }

    private MempoolAdmissionResult admit(byte[] tx) {
        return mempool.tryAdmit(tx, TxValidationRequest.Origin.LOCAL, null, MempoolAdmissionLimits.unbounded(), null);
    }

    private void block(long slot, Runnable writes) {
        long number = ++blocks;
        byte[] hash = HexUtil.decodeHexString(String.format("%064x", number));
        stores.gate.runWrite(() -> {
            writes.run();
            stores.chain.storeBlockHeader(hash, number, slot, new byte[]{0});
            stores.chain.storeBlock(hash, number, slot, new byte[]{0});
        });
    }

    private void put(String txHash, long lovelace) {
        try {
            UtxoTestRecords.putLovelace(stores.chain, txHash, 0, BigInteger.valueOf(lovelace));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String utxo(int i) {
        return String.format("%064x", 0xAB00 + i);
    }

    private static byte[] spend(String txHash) {
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(txHash, 0)))
                .outputs(List.of(new TransactionOutput(ADDRESS, new Value(BigInteger.valueOf(1_000_000), null))))
                .fee(BigInteger.valueOf(200_000)).build();
        try {
            return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValidationEnvFactory envFactory() {
        return (slot, view) -> new ValidationEnv(slot, 0, 10, 0, NetworkId.TESTNET,
                new SlotConfig(1000, 0, 1_600_000_000_000L), new byte[32]);
    }

    /** Checks that every spending input exists in the view and derives UTxO effects (no ledger rules). */
    private final class UtxoEngine implements LedgerValidationEngine {
        @Override
        public String name() {
            return "utxo-test";
        }

        @Override
        public TxValidationOutcome validate(TxValidationRequest request) {
            try {
                byte[] cbor = request.txCbor();
                Transaction tx = Transaction.deserialize(cbor);
                String txId = TxIdentity.txIdHex(cbor);
                List<Outpoint> consumed = new ArrayList<>();
                for (TransactionInput in : tx.getBody().getInputs()) {
                    Outpoint outpoint = new Outpoint(in.getTransactionId(), in.getIndex());
                    if (in.getTransactionId().equals(utxo(2))) {
                        readsOfU2.incrementAndGet();
                    }
                    Lookup<UtxoEntry> read = request.view().utxo(outpoint);
                    if (!read.isPresent()) {
                        return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO",
                                LedgerFailure.Phase.PHASE_1, String.valueOf(read)));
                    }
                    consumed.add(outpoint);
                }
                List<UtxoEntry> produced = new ArrayList<>();
                for (int i = 0; i < tx.getBody().getOutputs().size(); i++) {
                    produced.add(new UtxoEntry(new Outpoint(txId, i), tx.getBody().getOutputs().get(i), null));
                }
                return new TxValidationOutcome.Valid(new TxEffects(txId, true, consumed, produced, List.of()),
                        new ValidatedTx(cbor, TxIdentity.txId(cbor), 10, 0, new byte[32], true, request.origin()),
                        false);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

}
