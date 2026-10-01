package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.runtime.chain.MempoolAdmissionLimits;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.validation.ValidationEnvFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.ADA;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.admit;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.build;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.confirm;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.extraInput;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.hash;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.payment;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.withCerts;

/**
 * ADR-056 Phase 6a gates for the ledger-state mempool, with the java engine over {@link MutationWorld} states: the
 * publication race, multi-generation lag, removals during a rebuild, removal without a rebuild, the pending-mempool
 * hard fork, the synchronous fallback with {@code CATCHING_UP}, ownership, and the phase-2-invalid policy.
 *
 * <p>The rebuild worker is held throughout ({@link MempoolTestWorld.HeldExecutor}): triggers only queue it, and each
 * test runs rebuild cycles itself with {@link LedgerMempool#rebuildNow()}, so a queued follow-up can never mask a
 * stale publication.</p>
 */
class LedgerMempoolTest {

    private static final CredentialKey DEV_42 = MutationWorld.credentialKey(TestKey.DEV_42);
    private static final StakePoolId POOL_77 = new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()));

    private final MempoolTestWorld.StubEvaluator evaluator = new MempoolTestWorld.StubEvaluator();
    private final MempoolTestWorld.HeldExecutor worker = new MempoolTestWorld.HeldExecutor();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Revalidation> revalidations = new CopyOnWriteArrayList<>();
    private LedgerView world;
    private MempoolTestWorld.Chain chain;
    private LedgerMempool mempool;
    private Hooks hooks;

    private record Revalidation(String txHash, boolean hadPrevious, boolean reapplied, boolean valid) {
    }

    /** Test hooks run inside the rebuild's steps; each defaults to nothing. */
    private static class Hooks {
        Runnable onFold = () -> { };
        Runnable onSyncFold = () -> { };
        Runnable onReconcile = () -> { };
    }

    @BeforeEach
    void setUp() {
        world = MempoolTestWorld.world(MutationWorld.protocolParams(), 16);
        chain = new MempoolTestWorld.Chain(world);
        events.clear();
        revalidations.clear();
        start(LedgerMempool.Settings.defaults());
    }

    /** Runs {@code body} once per value, each on a fresh chain and mempool, checking for leaks after each. */
    private <E> void forEach(E[] values, ThrowingConsumer<E> body) throws Exception {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                tearDown();
                setUp();
            }
            try {
                body.accept(values[i]);
            } catch (AssertionError e) {
                throw new AssertionError("case " + values[i] + ": " + e.getMessage(), e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<E> {
        void accept(E value) throws Exception;
    }

    private void start(LedgerMempool.Settings settings) {
        if (mempool != null) {
            mempool.close();
        }
        mempool = MempoolTestWorld.mempool(chain, evaluator, worker, settings);
        hooks = new Hooks();
        LedgerMempool m = mempool;
        chain.laneHeld = m::laneHeldByCurrentThread;
        mempool.setObserver(new LedgerMempool.RebuildObserver() {
            @Override
            public void foldStarted(CanonicalMark target, int transactions, boolean synchronous) {
                events.add((synchronous ? "sync-fold:" : "fold:") + target.generation());
                if (synchronous) {
                    assertThat(m.laneHeldByCurrentThread()).as("the synchronous fallback holds the lane").isTrue();
                    hooks.onSyncFold.run();
                } else {
                    assertThat(m.laneHeldByCurrentThread()).as("step 3 runs outside the lane").isFalse();
                    hooks.onFold.run();
                }
            }

            @Override
            public void reconcilingAppends(CanonicalMark target, List<String> txHashes) {
                events.add("reconcile:" + target.generation() + ":" + txHashes.size());
                hooks.onReconcile.run();
            }

            @Override
            public void discarded(CanonicalMark target, String reason) {
                events.add("discard:" + target.generation() + ":" + target.targetEpoch() + ":" + reason);
            }

            @Override
            public void published(CanonicalMark target, long mempoolGeneration) {
                events.add("publish:" + target.generation() + ":" + target.targetEpoch());
            }

            @Override
            public void revalidated(String txHash, TxValidationRequest request, TxValidationOutcome outcome) {
                revalidations.add(new Revalidation(txHash, request != null && request.previous() != null,
                        outcome instanceof TxValidationOutcome.Valid v && v.reapplied(), outcome.isValid()));
            }
        });
        mempool.start();
    }

    @AfterEach
    void tearDown() {
        mempool.close();
        mempool = null;
        assertThat(chain.liveBases()).as("no leaked canonical bases").isZero();
        assertThat(chain.acquiredUnderLane.get()).as("no snapshot acquired under the lane").isZero();
    }

    // ------------------------------------------------------------------ transactions

    /** A: registers dev-42's stake credential (KEY_INPUT). */
    private byte[] registerA() {
        return build(withCerts(payment(MutationWorld.KEY_INPUT, MutationWorld.PAYMENT), ADA.multiply(BigInteger.TWO)
                .negate(), new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), ADA.multiply(BigInteger.TWO))),
                world);
    }

    /** B: delegates dev-42 to dev-77's pool, from an independent input (RICH_INPUT): depends on A's registration. */
    private byte[] delegateB() {
        return build(withCerts(payment(MutationWorld.RICH_INPUT, MutationWorld.PAYMENT), BigInteger.ZERO,
                new StakeDelegation(MutationWorld.stakeCredential(TestKey.DEV_42), POOL_77)), world);
    }

    private byte[] pay(int extra) {
        return build(payment(extraInput(extra), ADA.multiply(BigInteger.TWO)), world);
    }

    private void accept(byte[] tx) {
        MempoolAdmissionResult result = admit(mempool, tx);
        assertThat(result.status()).as(result.detail() + " " + result.rejections())
                .isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
    }

    private List<String> ids() {
        return mempool.published().entries().stream().map(MempoolEntry::txHash).toList();
    }

    private void consistent() {
        mempool.published().checkConsistent();
    }

    private String mark(MempoolTestWorld.Chain c) {
        return c.current().generation() + ":" + c.current().targetEpoch();
    }

    // ------------------------------------------------------------------ admission over the overlay

    @Test
    void admissionSeesTheCertificateEffectsOfEarlierMempoolTransactions() {
        byte[] a = registerA();
        byte[] b = delegateB();

        MempoolAdmissionResult alone = admit(mempool, b);
        assertThat(alone.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(alone.rejections()).anyMatch(r -> r.reason().contains("DELEG.StakeKeyNotRegisteredDELEG"));

        accept(a);
        accept(b);

        assertThat(ids()).containsExactly(hash(a), hash(b));
        AccountState account = mempool.published().overlay().account(DEV_42).require("dev-42");
        assertThat(account.delegatedPool()).isEqualTo(MutationWorld.poolId(TestKey.DEV_77));
        assertThat(mempool.published().entries().getLast().validated().resolvedInputsDigest())
                .as("the java engine records the resolved inputs for re-application").isNotNull();
        consistent();
        assertThat(chain.liveBases()).as("the published state retains one base").isEqualTo(1);
    }

    @Test
    void aVoteSeesAProposalOfAnEarlierMempoolTransactionAndCascadesWithIt() {
        TxSpec proposalSpec = payment(MutationWorld.GOV_INPUT, MutationWorld.PAYMENT);
        proposalSpec.proposals.add(ProposalProcedure.builder().deposit(MutationWorld.GOV_ACTION_DEPOSIT)
                .rewardAccount(MutationWorld.rewardAccount(TestKey.DEV_77, MutationWorld.NETWORK))
                .govAction(new InfoAction())
                .anchor(new Anchor("https://example.com/proposal.json", new byte[32]))
                .build());
        proposalSpec.changeAdjust = MutationWorld.GOV_ACTION_DEPOSIT.negate();
        byte[] proposal = build(proposalSpec, world);
        TxSpec voteSpec = payment(extraInput(14), ADA.multiply(BigInteger.TWO));
        VotingProcedures votes = new VotingProcedures();
        votes.add(new Voter(VoterType.DREP_KEY_HASH, MutationWorld.credential(TestKey.DEV_77)),
                new GovActionId(hash(proposal), 0), new VotingProcedure(Vote.YES, null));
        voteSpec.votingProcedures = votes;
        voteSpec.signers.add(TestKey.DEV_77);
        byte[] vote = build(voteSpec, world);

        MempoolAdmissionResult early = admit(mempool, vote);
        assertThat(early.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(early.rejections()).anyMatch(r -> r.reason().contains("GOV.GovActionsDoNotExist"));

        accept(proposal);
        accept(vote);   // Phase 5 dependency (c): the proposal comes from the effects overlay

        assertThat(mempool.evictTransaction(hash(proposal))).containsExactly(hash(proposal), hash(vote));
        assertThat(mempool.isEmpty()).isTrue();
        consistent();
    }

    // ------------------------------------------------------------------ removal without a rebuild

    @Test
    void removalTruncatesAndReappliesSoTheRemovedEffectsAreImmediatelyInvisible() {
        byte[] a = registerA();
        byte[] b = delegateB();
        byte[] c = pay(0);
        accept(a);
        accept(b);
        accept(c);

        List<String> removed = mempool.evictTransaction(hash(a));

        assertThat(removed).containsExactly(hash(a), hash(b));
        assertThat(ids()).containsExactly(hash(c));
        assertThat(mempool.published().overlay().account(DEV_42)).isInstanceOf(Lookup.Absent.class);
        MempoolAdmissionResult again = admit(mempool, b);
        assertThat(again.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(again.rejections()).anyMatch(r -> r.reason().contains("DELEG.StakeKeyNotRegisteredDELEG"));
        assertThat(revalidations).as("the suffix is re-applied, not validated in full")
                .filteredOn(r -> r.txHash().equals(hash(c))).singleElement()
                .satisfies(r -> assertThat(r.reapplied()).isTrue());
        assertThat(events).noneMatch(e -> e.startsWith("fold:"));
        assertThat(mempool.stats().cascadedRemovals()).isEqualTo(1);
        consistent();
    }

    @Test
    void ttlEvictionAndClearAlsoTruncate() throws Exception {
        byte[] a = registerA();
        accept(a);
        Thread.sleep(5);
        byte[] b = delegateB();
        accept(b);
        long aInserted = mempool.published().entries().getFirst().transaction().insertedAt();

        assertThat(mempool.removeOlderThan(aInserted + 1)).isEqualTo(2);
        assertThat(mempool.isEmpty()).isTrue();

        accept(pay(1));
        mempool.clear();
        assertThat(mempool.isEmpty()).isTrue();
        assertThat(mempool.published().overlay().layerCount()).isZero();
        consistent();
    }

    // ------------------------------------------------------------------ rebuild

    @Test
    void aRebuildDropsConfirmedTransactionsAndReappliesTheirDependents() {
        byte[] a = registerA();
        byte[] b = delegateB();
        accept(a);
        accept(b);

        chain.publish(confirm(world, evaluator, a));
        assertThat(worker.queued()).as("the publication queued the (held) worker").isPositive();
        assertThat(mempool.isStale()).isTrue();

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(ids()).containsExactly(hash(b));
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        assertThat(revalidations).filteredOn(r -> r.txHash().equals(hash(a))).singleElement()
                .satisfies(r -> assertThat(r.valid()).isFalse());
        assertThat(revalidations).filteredOn(r -> r.txHash().equals(hash(b))).singleElement()
                .satisfies(r -> assertThat(r.reapplied()).isTrue());
        assertThat(chain.liveBases()).as("the replaced base was released").isEqualTo(1);
        consistent();
    }

    enum Injection { BLOCK, ROLLBACK, EPOCH }

    private void inject(Injection injection, byte[] confirmed) {
        switch (injection) {
            case BLOCK -> chain.publish(confirm(world, evaluator, confirmed));
            case ROLLBACK -> chain.publish(world);
            case EPOCH -> chain.crossEpoch();
        }
    }

    @Test
    void aCanonicalChangeDuringTheFoldDiscardsTheCandidate() throws Exception {
        forEach(Injection.values(), this::canonicalChangeDuringTheFold);
    }

    private void canonicalChangeDuringTheFold(Injection injection) {
        byte[] a = registerA();
        byte[] c = pay(2);
        accept(a);
        accept(c);
        if (injection == Injection.ROLLBACK) {
            chain.publish(confirm(world, evaluator, c)); // a block the rollback will undo
            assertThat(mempool.rebuildNow()).isTrue();
            assertThat(ids()).containsExactly(hash(a));
            events.clear();
        }
        String stale = mark(chain);
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                inject(injection, c);
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(events).anyMatch(e -> e.startsWith("discard:" + stale) && e.contains("canonical"));
        assertThat(events).noneMatch(e -> e.equals("publish:" + stale));
        assertThat(events.getLast()).isEqualTo("publish:" + mark(chain));
        assertThat(worker.queued()).as("the follow-up worker is held, not masking the race").isPositive();
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        consistent();
    }

    @Test
    void aCanonicalChangeDuringTheFoldWithAppendsDiscardsTheCandidate() throws Exception {
        forEach(Injection.values(), this::canonicalChangeDuringTheFoldWithAppends);
    }

    private void canonicalChangeDuringTheFoldWithAppends(Injection injection) {
        byte[] a = registerA();
        byte[] c = pay(3);
        byte[] d = pay(4);
        accept(a);
        accept(c);
        String stale = mark(chain);
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                accept(d);                   // an append during step 3 (outside the lane)
                inject(injection, c);
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(events).anyMatch(e -> e.startsWith("discard:" + stale) && e.contains("canonical"));
        assertThat(events).noneMatch(e -> e.equals("publish:" + stale));
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        assertThat(ids()).contains(hash(a), hash(d));
        consistent();
    }

    @Test
    void aBlockDuringAppendReconciliationDiscardsTheCandidate() {
        byte[] a = registerA();
        byte[] d = pay(5);
        accept(a);
        String stale = mark(chain);
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                accept(d);
            }
        };
        AtomicBoolean reconciled = new AtomicBoolean();
        hooks.onReconcile = () -> {
            if (reconciled.compareAndSet(false, true)) {
                chain.publish(world); // under the lane: a canonical publication is a volatile write, never the lane
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(events).contains("reconcile:" + stale.split(":")[0] + ":1");
        assertThat(events).anyMatch(e -> e.startsWith("discard:" + stale) && e.contains("canonical"));
        assertThat(events).noneMatch(e -> e.equals("publish:" + stale));
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        assertThat(ids()).containsExactly(hash(a), hash(d));
        consistent();
    }

    @Test
    void anAppendOnlyInterleavingPublishesWithTheAppendsRevalidatedOnTheNewBase() {
        byte[] a = registerA();
        byte[] b = delegateB();
        accept(a);
        chain.publish(confirm(world, evaluator, a));  // the new base: A confirmed
        String target = mark(chain);
        hooks.onFold = () -> accept(b);                // B appended against the stale state (A in the mempool)

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(events).containsSubsequence("fold:" + target.split(":")[0], "reconcile:" + target.split(":")[0]
                + ":1", "publish:" + target);
        assertThat(events).noneMatch(e -> e.startsWith("discard:"));
        assertThat(ids()).containsExactly(hash(b));
        // B was validated on the new base, where A's registration is canonical.
        assertThat(revalidations).filteredOn(r -> r.txHash().equals(hash(b))).singleElement()
                .satisfies(r -> assertThat(r.valid()).isTrue());
        consistent();
    }

    // ------------------------------------------------------------------ removals during a rebuild

    enum Removal { EVICT, TTL, CLEAR }

    @Test
    void aRemovalDuringTheRebuildRestartsItAndNothingIsResurrected() throws Exception {
        forEach(Removal.values(), this::removalDuringTheRebuild);
    }

    private void removalDuringTheRebuild(Removal removal) throws Exception {
        byte[] a = registerA();
        accept(a);
        Thread.sleep(5);
        byte[] b = delegateB();
        accept(b);
        long aInserted = mempool.published().entries().getFirst().transaction().insertedAt();
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                switch (removal) {
                    case EVICT -> mempool.evictTransaction(hash(a));
                    case TTL -> mempool.removeOlderThan(aInserted + 1);
                    case CLEAR -> mempool.clear();
                }
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();   // tip unchanged

        assertThat(events).anyMatch(e -> e.startsWith("discard:") && e.contains("removal"));
        assertThat(ids()).doesNotContain(hash(a), hash(b));
        assertThat(mempool.published().overlay().account(DEV_42)).isInstanceOf(Lookup.Absent.class);
        consistent();
    }

    @Test
    void anAppendPlusARemovalDuringTheRebuildAlsoRestarts() {
        byte[] a = registerA();
        byte[] b = delegateB();
        byte[] d = pay(6);
        accept(a);
        accept(b);
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                accept(d);
                mempool.evictTransaction(hash(a));
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(events).anyMatch(e -> e.startsWith("discard:") && e.contains("removal"));
        assertThat(ids()).containsExactly(hash(d));
        consistent();
    }

    // ------------------------------------------------------------------ lag

    @Test
    void multiGenerationLagStaysConsistentAndProvisionalAdmissionsAreRevalidated() {
        byte[] a = registerA();
        byte[] spendsConfirmedInput = build(payment(extraInput(7), ADA), world);
        byte[] confirmedElsewhere = build(payment(extraInput(7), ADA.multiply(BigInteger.valueOf(3))), world);
        accept(a);
        AtomicBoolean once = new AtomicBoolean();
        hooks.onFold = () -> {
            if (once.compareAndSet(false, true)) {
                chain.publish(world);
                chain.publish(world);
                chain.publish(world);   // three generations during one fold
            }
        };

        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(events).anyMatch(e -> e.startsWith("discard:"));
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        consistent();

        // The tip moves again (extra input 7 is spent canonically) and no rebuild has run yet: the published state
        // lags, and an admission is provisional against it.
        chain.publish(confirm(world, evaluator, confirmedElsewhere));
        assertThat(mempool.isStale()).isTrue();
        accept(spendsConfirmedInput);     // valid against the lagging base, where extra input 7 is unspent
        consistent();

        assertThat(mempool.rebuildNow()).isTrue();   // the next rebuild re-validates it against the tip and drops it

        assertThat(ids()).containsExactly(hash(a));
        consistent();
    }

    // ------------------------------------------------------------------ pending-mempool hard fork

    @Test
    void aProtocolMajorOrCostModelChangeForcesFullValidationOfEveryPendingTransaction() {
        byte[] p = pay(8);
        byte[] s = build(MutationWorld.scriptSpec(), world);
        accept(p);
        accept(s);
        assertThat(evaluator.evaluations.get()).isEqualTo(1);

        // Control: a new generation with the same parameters re-applies (Plutus skipped).
        chain.publish(world);
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(revalidations).hasSize(2).allMatch(r -> r.hadPrevious() && r.reapplied());
        assertThat(evaluator.evaluations.get()).isEqualTo(1);

        // Protocol major 10 -> 11: every re-application is a full validation, Plutus included.
        revalidations.clear();
        LedgerView pv11 = MempoolTestWorld.world(MutationWorld.protocolParams(11), 16);
        chain.publish(pv11);
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(revalidations).hasSize(2).allMatch(r -> r.hadPrevious() && !r.reapplied() && r.valid());
        assertThat(evaluator.evaluations.get()).isEqualTo(2);
        assertThat(mempool.published().entries()).allMatch(e -> e.validated().validatedProtocolMajor() == 11);

        // A cost-model change (PlutusV2, a language these transactions do not use): full validation again; the
        // script now fails, so the script transaction is dropped.
        revalidations.clear();
        ProtocolParams changed = MutationWorld.protocolParams(11);
        List<Long> v2 = new ArrayList<>(changed.getCostModelsRaw().get("PlutusV2"));
        v2.set(0, v2.get(0) + 1);
        changed.getCostModelsRaw().put("PlutusV2", v2);
        evaluator.result = new ScriptPhaseResult.Failed(List.of());
        chain.publish(MempoolTestWorld.world(changed, 16));
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(revalidations).hasSize(2).allMatch(r -> r.hadPrevious() && !r.reapplied());
        assertThat(evaluator.evaluations.get()).isEqualTo(3);
        assertThat(ids()).containsExactly(hash(p));
        consistent();
    }

    // ------------------------------------------------------------------ synchronous fallback, catching up

    @Test
    void theSynchronousFallbackKeepsTheLockOrderThenCatchingUpThenReady() {
        start(new LedgerMempool.Settings(2, 2, 60_000));
        byte[] a = registerA();
        accept(a);
        AtomicBoolean fastSync = new AtomicBoolean(true);
        AtomicInteger syncFolds = new AtomicInteger();
        hooks.onFold = () -> {
            if (fastSync.get()) {
                chain.publish(world);
            }
        };
        hooks.onSyncFold = () -> {
            syncFolds.incrementAndGet();
            if (fastSync.get()) {
                chain.publish(world);   // blocks keep arriving while the lane is held
            }
        };

        assertThat(mempool.rebuildNow()).isFalse();

        assertThat(syncFolds.get()).isEqualTo(2);
        assertThat(events.stream().filter(e -> e.startsWith("discard:")).count()).isEqualTo(4);
        assertThat(mempool.status()).isEqualTo(LedgerMempool.Status.CATCHING_UP);
        MempoolAdmissionResult refused = admit(mempool, pay(9));
        assertThat(refused.status()).isEqualTo(MempoolAdmissionResult.Status.CATCHING_UP);
        assertThat(refused.retryable()).isTrue();
        assertThat(mempool.ledgerStatus().catchingUp()).isTrue();

        fastSync.set(false);
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(mempool.status()).isEqualTo(LedgerMempool.Status.READY);
        accept(pay(9));
        assertThat(mempool.ledgerStatus().lockOrderViolations()).isZero();
        consistent();
    }

    @Test
    void takingTheLaneWhileTheGateIsHeldIsALockOrderViolation() {
        chain.gateHeld.set(true);
        try {
            assertThatThrownBy(() -> admit(mempool, pay(10))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lock order");
        } finally {
            chain.gateHeld.set(false);
        }
        assertThat(mempool.ledgerStatus().lockOrderViolations()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void aFrozenViewKeepsItsGenerationAcrossTwoSwapsAndNothingLeaks() {
        byte[] c = pay(11);
        accept(c);
        MempoolLedgerState frozenState = mempool.published();
        MempoolBase frozenBase = frozenState.base().retain();   // what a shadow task does
        LedgerView frozen = frozenState.overlay();
        Outpoint spent = new Outpoint(extraInput(12).getTransactionId(), 0);

        chain.publish(confirm(world, evaluator, build(payment(extraInput(12), ADA), world)));
        assertThat(mempool.rebuildNow()).isTrue();
        chain.publish(world);
        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(frozen.utxo(spent)).as("the frozen view still reads its own generation")
                .isInstanceOf(Lookup.Present.class);
        assertThat(chain.liveBases()).isEqualTo(2);
        frozenBase.release();
        assertThat(chain.liveBases()).as("back to the published baseline").isEqualTo(1);
    }

    // ------------------------------------------------------------------ phase-2-invalid policy

    @Test
    void phase2InvalidTransactionsAreRejectedFromEveryOrigin() throws Exception {
        forEach(new TxValidationRequest.Origin[]{TxValidationRequest.Origin.LOCAL, TxValidationRequest.Origin.PEER},
                this::phase2InvalidRejected);
    }

    private void phase2InvalidRejected(TxValidationRequest.Origin origin) {
        evaluator.result = new ScriptPhaseResult.Failed(List.of());

        MempoolAdmissionResult failing = admit(mempool, build(MutationWorld.scriptSpec(), world), origin);
        assertThat(failing.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(failing.rejections()).anyMatch(r -> r.reason().contains("UTXOS.ValidationTagMismatch"));

        TxSpec claimedInvalid = MutationWorld.scriptSpec();
        claimedInvalid.isValid = false;
        MempoolAdmissionResult flagged = admit(mempool, build(claimedInvalid, world), origin);
        assertThat(flagged.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(flagged.rejections()).anyMatch(r -> r.reason().contains("ENGINE.Phase2InvalidTxNotSupported"));
        assertThat(mempool.isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ unavailable ledger state

    /**
     * ADR-056 Phase 7c: an admission that fails only with {@code ENGINE.LedgerStateUnavailable} (here the validation
     * environment cannot be built, as across the Conway bootstrap boundary) is not a verdict: the submitter gets the
     * retryable {@code CATCHING_UP} (REST 503), not {@code LEDGER_REJECTED} (REST 400). A real rejection stays one.
     */
    @Test
    void anUnavailableLedgerStateIsRetryableNotARejection() {
        ValidationEnvFactory unavailable = (slot, view) -> {
            throw new LedgerStateUnavailableException("test: governance enactments at the boundary are unknown");
        };
        mempool.close();
        mempool = new LedgerMempool(new JavaLedgerValidationEngine(evaluator), unavailable, chain, worker, null,
                LedgerMempool.Settings.defaults());
        mempool.start();

        MempoolAdmissionResult retry = admit(mempool, pay(10));
        assertThat(retry.status()).isEqualTo(MempoolAdmissionResult.Status.CATCHING_UP);
        assertThat(retry.retryable()).isTrue();
        assertThat(mempool.isEmpty()).isTrue();
        assertThat(mempool.ledgerStatus().catchingUpRejections()).isEqualTo(1);

        MempoolAdmissionResult rejected = mempool.tryAdmit(pay(11), TxValidationRequest.Origin.LOCAL,
                (bytes, txHash, resolver) -> List.of(new VetoableEvent.Rejection("Plugin", "refused")),
                MempoolAdmissionLimits.unbounded(), null);
        assertThat(rejected.status()).isEqualTo(MempoolAdmissionResult.Status.LEDGER_REJECTED);
        assertThat(mempool.isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ review minors

    @Test
    void aThrowingObserverDoesNotReleaseThePublishedBaseTwice() {
        accept(pay(15));
        mempool.setObserver(new LedgerMempool.RebuildObserver() {
            @Override
            public void published(CanonicalMark target, long mempoolGeneration) {
                throw new IllegalStateException("observer failure");
            }
        });
        chain.publish(world);

        assertThat(mempool.rebuildNow()).isTrue();

        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        assertThat(mempool.published().base().refCount()).as("owned once by the published state").isEqualTo(1);
        assertThat(chain.liveBases()).isEqualTo(1);
        consistent();
    }

    @Test
    void anInvalidationWhileStaleIsDeferredToTheRebuild() {
        byte[] a = registerA();
        byte[] b = delegateB();
        accept(a);
        accept(b);
        chain.publish(confirm(world, evaluator, a));   // A confirmed; the published state lags

        assertThat(mempool.removeInvalidated(Set.of(hash(a)))).isZero();
        assertThat(ids()).as("B is not cascaded against the old base").containsExactly(hash(a), hash(b));
        assertThat(mempool.ledgerStatus().deferredRemovals()).isEqualTo(1);

        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(ids()).containsExactly(hash(b));
        assertThat(mempool.removeInvalidated(Set.of(hash(b)))).as("fresh: removed at once").isEqualTo(1);
    }

    @Test
    void closingDuringARebuildPublishesNothingAndLeaksNothing() {
        accept(pay(3));
        chain.publish(world);
        hooks.onFold = () -> mempool.close();

        assertThat(mempool.rebuildNow()).isFalse();

        assertThat(mempool.published()).isNull();
        assertThat(chain.liveBases()).isZero();
    }

    // ------------------------------------------------------------------ no canonical state

    @Test
    void withoutCanonicalStateTheRebuildCatchesUpInsteadOfDroppingTransactions() {
        byte[] c = pay(13);
        accept(c);
        chain.unavailable = true;
        chain.publish(world);

        assertThat(mempool.rebuildNow()).isFalse();

        assertThat(mempool.status()).isEqualTo(LedgerMempool.Status.CATCHING_UP);
        assertThat(ids()).containsExactly(hash(c));
        chain.unavailable = false;
        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(ids()).containsExactly(hash(c));
    }
}
