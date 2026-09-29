package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.ProducerControl;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.devnet.YanoDevnetAssembly;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.blockproducer.TransactionValidationException;
import org.yanoproject.runtime.kernel.NodeKernel;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.CanonicalTip;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.mempool.LedgerMempool;
import org.yanoproject.runtime.mempool.LedgerMempoolStatus;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.tx.TxSubsystem;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;
import org.yanoproject.testkit.devnet.YanoDevnetTestConfig;
import org.yanoproject.tx.DefaultTransactionServicesFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * An in-process devnet block producer for the ADR-056 Phase 6b gates, with an engine-API admission engine
 * ({@code java} behind the experimental flag, or {@code amaru}): isolated temporary storage and a free port, short
 * epochs, and the governance parameters patched so a proposal expires within the test (lifetime 1 epoch).
 */
public final class DevnetGateNode implements AutoCloseable {

    /** Gate devnet settings. */
    public record Settings(String engine, int blockTimeMillis, long epochLength, Map<String, Object> extraOptions) {
        public static Settings of(String engine) {
            return new Settings(engine, 500, 100, Map.of());
        }
    }

    /** A submission's verdict: accepted into the mempool, or rejected with the node's message. */
    public record Verdict(boolean accepted, String message) {
        /** @return the first {@code RULE.Constructor}-looking token of the rejection, or the message */
        public String constructor() {
            if (accepted) {
                return "ACCEPTED";
            }
            for (String token : message.split("[\\s:,\\[\\]()]+")) {
                if (token.matches("[A-Z]+\\.[A-Za-z0-9]+")) {
                    return token;
                }
            }
            return message;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Settings settings;
    private final YanoDevnetTestConfig config;
    private final Yano node;
    private final TxSubsystem tx;
    private final CanonicalStateGate gate;
    private final EventBus eventBus;
    private final BlockRevalidator revalidator;
    private final Map<String, Long> confirmations = new ConcurrentHashMap<>();
    private final Map<Long, Long> blockSlots = new ConcurrentHashMap<>();
    private final Map<String, List<String>> drops = new ConcurrentHashMap<>();
    /** (slot, arrival millis) of the last applied block. */
    private volatile long[] lastBlock = {0, System.currentTimeMillis()};
    /** The devnet genesis slot length (0.2 s). */
    private static final long SLOT_MILLIS = 200;
    private final SubscriptionHandle blockSubscription;

    private DevnetGateNode(Settings settings) {
        this.settings = settings;
        YanoDevnetTestConfig.Builder builder = YanoDevnetTestConfig.builder()
                .temporaryRocksDbStorage()
                .blockTimeMillis(settings.blockTimeMillis())
                .epochLength(settings.epochLength())
                .runtimeOption(YanoPropertyKeys.Validation.ENGINE, settings.engine())
                .runtimeOption(YanoPropertyKeys.Validation.JAVA_ENGINE_EXPERIMENTAL, "true")
                .runtimeOption(YanoPropertyKeys.Validation.MAX_LIVE_SNAPSHOTS, "16");
        settings.extraOptions().forEach(builder::runtimeOption);
        this.config = builder.build();
        patchGovernance(config.devnetProfileDir().orElseThrow());
        this.node = YanoDevnetAssembly.devnet(config.yanoConfig())
                .runtimeOptions(config.runtimeOptions())
                .transactionBootstrap(TransactionBootstrapOptions.enabled(true, false, "scalus"),
                        DefaultTransactionServicesFactory::create)
                .build();
        node.start();
        NodeKernel kernel = node.kernel().orElseThrow();
        RuntimeNode runtime = (RuntimeNode) node.chain();
        this.tx = runtime.getTxSubsystem();
        this.gate = runtime.getCanonicalStateGate();
        this.eventBus = kernel.context().eventBus();
        ValidationEngines engines = node.validationEngines().orElseThrow();
        List<LedgerValidationEngine> independent = new ArrayList<>();
        independent.add(new JavaLedgerValidationEngine(new ScalusScriptPhaseEvaluator()));
        if (!JavaLedgerValidationEngine.NAME.equals(engines.admissionEngineName())) {
            independent.add(engines.admissionEngine());
        }
        this.revalidator = new BlockRevalidator(gate, eventBus, runtime, engines.envFactory(), independent);
        this.blockSubscription = eventBus.subscribe(BlockAppliedEvent.class, ctx -> onBlock(ctx.event()),
                SubscriptionOptions.builder().build());
        tx.ledgerMempool().setObserver(new LedgerMempool.RebuildObserver() {
            @Override
            public void revalidated(String txHash, TxValidationRequest request, TxValidationOutcome outcome) {
                record(txHash, "rebuild", outcome);
            }

            @Override
            public void blockCandidateValidated(String txHash, TxValidationRequest request,
                                                TxValidationOutcome outcome) {
                record(txHash, "block-selection", outcome);
            }
        });
    }

    private void record(String txHash, String where, TxValidationOutcome outcome) {
        if (outcome instanceof TxValidationOutcome.Invalid invalid) {
            drops.computeIfAbsent(txHash, h -> new CopyOnWriteArrayList<>()).add(where + ":" + invalid.failures()
                    .stream().map(LedgerFailure::qualifiedName).toList());
        }
    }

    /** @return the failures the mempool rebuilds and block selections recorded for {@code txHash} */
    public List<String> failuresOf(String txHash) {
        return List.copyOf(drops.getOrDefault(txHash, List.of()));
    }

    public static DevnetGateNode start(Settings settings) {
        return new DevnetGateNode(settings);
    }

    /**
     * Governance parameters for the gate: a proposal lives one epoch after the one it is submitted in, and costs
     * 1,000 ADA, in both the Conway genesis and the node's protocol-parameter file.
     */
    private static void patchGovernance(Path profileDir) {
        try {
            Path conway = profileDir.resolve("conway-genesis.json");
            ObjectNode genesis = (ObjectNode) JSON.readTree(conway.toFile());
            genesis.put("govActionLifetime", 1);
            genesis.put("govActionDeposit", 1_000_000_000L);
            JSON.writerWithDefaultPrettyPrinter().writeValue(conway.toFile(), genesis);
            Path params = profileDir.resolve("protocol-param.json");
            ObjectNode protocol = (ObjectNode) JSON.readTree(params.toFile());
            protocol.put("gov_action_lifetime", "1");
            protocol.put("gov_action_deposit", "1000000000");
            JSON.writerWithDefaultPrettyPrinter().writeValue(params.toFile(), protocol);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Settings settings() {
        return settings;
    }

    public Yano node() {
        return node;
    }

    public BlockRevalidator revalidator() {
        return revalidator;
    }

    public ValidationEngines engines() {
        return node.validationEngines().orElseThrow();
    }

    // ------------------------------------------------------------------ submission and mempool

    public Verdict submit(byte[] txCbor, String txHash) {
        try {
            node.txGateway().submitTransaction(txCbor);
            return new Verdict(true, "accepted");
        } catch (TransactionValidationException e) {
            return new Verdict(false, e.getMessage());
        } catch (RuntimeException e) {
            // Relaying an admitted transaction to an upstream peer fails on a devnet without one.
            if (tx.containsTransaction(txHash)) {
                return new Verdict(true, "accepted");
            }
            return new Verdict(false, e.toString());
        }
    }

    public List<String> mempoolHashes() {
        LedgerMempool mempool = tx.ledgerMempool();
        if (mempool == null) {
            throw new IllegalStateException("the gate needs the ledger-state mempool (an engine-API engine)");
        }
        return mempool.snapshotTransactions(Integer.MAX_VALUE, Long.MAX_VALUE).stream()
                .map(t -> HexUtil.encodeHexString(t.txHash())).toList();
    }

    public LedgerMempoolStatus mempoolStatus() {
        return tx.ledgerMempoolStatus();
    }

    public LedgerMempool mempool() {
        return tx.ledgerMempool();
    }

    /** Waits until the ledger-state mempool is fresh for the canonical tip (its rebuild published). */
    public void awaitMempoolFresh(long timeoutMillis) throws InterruptedException {
        await("mempool fresh", timeoutMillis, () -> !tx.ledgerMempool().isStale());
    }

    // ------------------------------------------------------------------ producer and chain

    public ProducerControl producer() {
        return node.producerControl().orElseThrow();
    }

    public void rollbackToSlot(long slot) {
        node.devnetControl().orElseThrow().rollbackDevnetToSlot(slot);
    }

    public CanonicalTip tip() {
        return gate.tip();
    }

    public long epochOf(long slot) {
        return node.ledger().slotToEpoch(slot);
    }

    public long epochStartSlot(long epoch) {
        return epoch * settings.epochLength();
    }

    /**
     * @return the slot the wall clock is in now: devnet block slots follow the wall clock, so it is the last block's
     *         slot plus the slots elapsed since it arrived
     */
    public long wallClockSlot() {
        long[] last = lastBlock;
        return last[0] + (System.currentTimeMillis() - last[1]) / SLOT_MILLIS;
    }

    public void awaitWallClockSlot(long slot, long timeoutMillis) throws InterruptedException {
        await("wall-clock slot " + slot, timeoutMillis, () -> wallClockSlot() >= slot);
    }

    public void awaitBlocks(int count, long timeoutMillis) throws InterruptedException {
        long target = tipBlockNumber() + count;
        await(count + " more blocks", timeoutMillis, () -> tipBlockNumber() >= target
                && blockSlot(target) >= 0 && blockSlot(target) <= gate.tip().slot());
    }

    public long tipBlockNumber() {
        var tip = node.chain().getLocalTip();
        return tip != null ? tip.getBlockNumber() : -1;
    }

    public void await(String what, long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------ canonical state

    /** Reads the canonical ledger view of the current tip. */
    public <T> T read(Function<LedgerView, T> reader) {
        Lookup<CanonicalSnapshot> acquired = gate.acquireSnapshot(SnapshotPurpose.ADMISSION);
        if (!(acquired instanceof Lookup.Present<CanonicalSnapshot> present)) {
            throw new IllegalStateException("no canonical snapshot: " + acquired);
        }
        CanonicalSnapshot snapshot = present.value();
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            return reader.apply(view);
        } finally {
            snapshot.release();
        }
    }

    public ProtocolParams protocolParams() {
        return read(view -> view.protocolParams().require("protocol parameters"));
    }

    public BigInteger treasury() {
        return read(view -> view.treasury().require("treasury"));
    }

    /** @return the account's reward balance, or {@code null} when it is not registered */
    public BigInteger rewardBalance(CredentialKey credential) {
        return read(view -> view.account(credential) instanceof Lookup.Present<AccountState> p
                ? p.value().rewardBalance() : null);
    }

    /** @return the number of the canonical block that contains {@code txHash}, or -1 */
    public long blockOf(String txHash) {
        Long block = confirmations.get(txHash);
        return block != null && block <= tipBlockNumber() ? block : -1;
    }

    public boolean confirmed(String txHash) {
        return blockOf(txHash) >= 0;
    }

    /**
     * Waits until every transaction is in a canonical block and the gate has published that block (the block's
     * {@code BlockAppliedEvent} arrives inside its write section, before the publication; a caller that then checks
     * the mempool's freshness must see the new generation).
     */
    public void awaitConfirmed(List<String> txHashes, long timeoutMillis) throws InterruptedException {
        await("confirmation of " + txHashes, timeoutMillis, () -> txHashes.stream().allMatch(this::confirmed)
                && txHashes.stream().mapToLong(h -> blockSlot(blockOf(h))).max().orElse(-1) <= gate.tip().slot());
    }

    /** @return the slot of canonical block {@code blockNumber}, or -1 */
    public long blockSlot(long blockNumber) {
        Long slot = blockSlots.get(blockNumber);
        return slot != null ? slot : -1;
    }

    private void onBlock(BlockAppliedEvent event) {
        // A rolled-back block's number is reused by the next block on the new branch, which overwrites it.
        confirmations.values().removeIf(block -> block >= event.blockNumber());
        blockSlots.put(event.blockNumber(), event.slot());
        lastBlock = new long[]{event.slot(), System.currentTimeMillis()};
        if (event.block() != null && event.block().getTransactionBodies() != null) {
            event.block().getTransactionBodies()
                    .forEach(body -> confirmations.put(body.getTxHash(), event.blockNumber()));
        }
    }

    /**
     * The genesis UTxO of {@code address} (Shelley genesis funds: transaction id {@code blake2b-256(address bytes)},
     * index 0, as Haskell's {@code initialFundsPseudoTxIn}), when it is still unspent.
     */
    public Optional<Utxo> genesisUtxo(String address) {
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(new Address(address).getBytes()));
        return node.ledger().getUtxoState().getUtxo(new Outpoint(txHash, 0)).map(u -> toQuickTx(u, address));
    }

    private static Utxo toQuickTx(org.yanoproject.api.utxo.model.Utxo utxo, String address) {
        return new Utxo(utxo.outpoint().txHash(), utxo.outpoint().index(), address,
                List.of(Amount.lovelace(utxo.lovelace())), null, null, null);
    }

    @Override
    public void close() {
        try {
            blockSubscription.close();
            revalidator.close();
        } finally {
            try {
                node.close();
            } finally {
                config.close();
            }
        }
    }
}
