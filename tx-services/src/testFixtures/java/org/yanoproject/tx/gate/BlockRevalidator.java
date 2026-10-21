package org.yanoproject.tx.gate;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.ChainQuery;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.CanonicalTip;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The independent re-validation of every produced block (ADR-056 Phase 6b gate): each block's transactions are
 * re-read from the stored block bytes (so the block encoding is checked too) and validated in block order with rule
 * {@code LEDGER}, origin {@code SYNC} and no {@code previous} (full validation, Plutus included), by each given
 * engine, against the canonical snapshot taken at the publication just before the block (the pre-block state,
 * after the producer's boundary section).
 *
 * <p>The pre-block snapshot is acquired by a gate publication listener (which runs on the writer's thread after
 * the gate is released), and handed to a worker when the block's {@code BlockAppliedEvent} arrives; the worker
 * releases it.</p>
 */
public final class BlockRevalidator implements AutoCloseable {

    /**
     * One re-validated block.
     *
     * @param blockNumber   block number
     * @param slot          block slot
     * @param txHashes      the transaction ids, from the stored body bytes
     * @param failures      per engine, the failures ({@code txHash: RULE.Constructor}); empty when every transaction
     *                      is valid
     * @param effectsAgree  true when every engine derived the same effects for every transaction
     * @param invalidTxs    the block's {@code invalid_txs} indexes (must be empty, ADR-056 §6)
     * @param idsMatchEvent the ids from the stored bytes equal those of the applied block
     */
    public record BlockCheck(long blockNumber, long slot, List<String> txHashes,
                             java.util.Map<String, List<String>> failures, boolean effectsAgree,
                             List<Integer> invalidTxs, boolean idsMatchEvent) {
        public boolean clean() {
            return failures.values().stream().allMatch(List::isEmpty) && effectsAgree && invalidTxs.isEmpty()
                    && idsMatchEvent;
        }
    }

    private final CanonicalStateGate gate;
    private final ChainQuery chain;
    private final ValidationEnvFactory envFactory;
    private final List<LedgerValidationEngine> engines;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gate-block-revalidator");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<CanonicalSnapshot> latest = new AtomicReference<>();
    private final List<BlockCheck> checks = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final Consumer<CanonicalTip> publicationListener = this::onPublication;
    private final SubscriptionHandle subscription;

    public BlockRevalidator(CanonicalStateGate gate, EventBus eventBus, ChainQuery chain,
                            ValidationEnvFactory envFactory, List<LedgerValidationEngine> engines) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.chain = Objects.requireNonNull(chain, "chain");
        this.envFactory = Objects.requireNonNull(envFactory, "envFactory");
        this.engines = List.copyOf(engines);
        gate.addPublicationListener(publicationListener);
        onPublication(gate.tip());
        this.subscription = eventBus.subscribe(BlockAppliedEvent.class, ctx -> onBlock(ctx.event()),
                SubscriptionOptions.builder().build());
    }

    private void onPublication(CanonicalTip tip) {
        Lookup<CanonicalSnapshot> acquired = gate.acquireSnapshot(SnapshotPurpose.BLOCK_BUILD);
        CanonicalSnapshot next = acquired instanceof Lookup.Present<CanonicalSnapshot> p ? p.value() : null;
        CanonicalSnapshot previous = latest.getAndSet(next);
        if (previous != null) {
            previous.release();
        }
    }

    private void onBlock(BlockAppliedEvent event) {
        List<String> eventIds = new ArrayList<>();
        if (event.block() != null && event.block().getTransactionBodies() != null) {
            event.block().getTransactionBodies().forEach(body -> eventIds.add(body.getTxHash()));
        }
        if (eventIds.isEmpty()) {
            return;
        }
        CanonicalSnapshot pre = latest.get();
        if (pre == null) {
            errors.add(new IllegalStateException("no pre-block snapshot for block " + event.blockNumber()));
            return;
        }
        pre.retain();
        byte[] blockBytes = chain.getBlock(HexUtil.decodeHexString(event.blockHash()));
        worker.execute(() -> {
            try {
                checks.add(check(pre, blockBytes, event.slot(), event.blockNumber(), eventIds));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                pre.release();
            }
        });
    }

    private BlockCheck check(CanonicalSnapshot pre, byte[] blockBytes, long slot, long blockNumber,
                             List<String> eventIds) {
        Array root = (Array) CborSerializationUtil.deserializeOne(blockBytes);
        List<DataItem> items = root.getDataItems();
        List<DataItem> content = items.size() == 2 && items.get(0) instanceof UnsignedInteger
                ? ((Array) items.get(1)).getDataItems() : items;
        List<DataItem> bodies = ((Array) content.get(1)).getDataItems();
        List<DataItem> witnesses = ((Array) content.get(2)).getDataItems();
        Map aux = (Map) content.get(3);
        List<Integer> invalid = new ArrayList<>();
        for (DataItem index : ((Array) content.get(4)).getDataItems()) {
            invalid.add(((UnsignedInteger) index).getValue().intValue());
        }
        List<byte[]> txs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Array tx = new Array();
            tx.add(bodies.get(i));
            tx.add(witnesses.get(i));
            tx.add(invalid.contains(i) ? SimpleValue.FALSE : SimpleValue.TRUE);
            DataItem auxData = aux.get(new UnsignedInteger(i));
            tx.add(auxData != null ? auxData : SimpleValue.NULL);
            txs.add(CborSerializationUtil.serialize(tx));
            ids.add(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                    CborSerializationUtil.serialize(bodies.get(i)))));
        }
        java.util.Map<String, List<String>> failures = new LinkedHashMap<>();
        List<List<TxEffects>> effectsByEngine = new ArrayList<>();
        try (CanonicalLedgerView canonical = CanonicalLedgerView.over(pre);
             TickedLedgerView ticked = TickedLedgerView.of(canonical, slot)) {
            long basis = pre.tip().slot() >= 0 ? pre.tip().slot() + 1 : 0;
            ValidationEnv env = envFactory.create(slot, ticked).withForecastBasisSlot(basis);
            for (LedgerValidationEngine engine : engines) {
                OverlayLedgerView overlay = OverlayLedgerView.over(ticked);
                List<String> engineFailures = new ArrayList<>();
                List<TxEffects> effects = new ArrayList<>();
                for (int i = 0; i < txs.size(); i++) {
                    TxValidationOutcome outcome = engine.validate(new TxValidationRequest(txs.get(i), overlay, env,
                            TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
                    if (outcome instanceof TxValidationOutcome.Valid valid) {
                        overlay = overlay.apply(valid.effects());
                        effects.add(valid.effects());
                    } else {
                        TxValidationOutcome.Invalid bad = (TxValidationOutcome.Invalid) outcome;
                        engineFailures.add(ids.get(i) + ": " + bad.failures().stream()
                                .map(LedgerFailure::qualifiedName).toList());
                        effects.add(null);
                    }
                }
                failures.put(engine.name(), engineFailures);
                effectsByEngine.add(effects);
            }
        }
        boolean agree = effectsByEngine.stream().allMatch(e -> e.equals(effectsByEngine.getFirst()));
        return new BlockCheck(blockNumber, slot, ids, failures, agree, invalid, ids.equals(eventIds));
    }

    /** Waits until every block handed to the worker so far has been re-validated. */
    public void drain() throws InterruptedException {
        var done = worker.submit(() -> { });
        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("block re-validation did not finish", e);
        }
    }

    public List<BlockCheck> checks() {
        return List.copyOf(checks);
    }

    public List<Throwable> errors() {
        return List.copyOf(errors);
    }

    @Override
    public void close() {
        subscription.close();
        gate.removePublicationListener(publicationListener);
        worker.shutdown();
        try {
            worker.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CanonicalSnapshot last = latest.getAndSet(null);
        if (last != null) {
            last.release();
        }
    }
}
