package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import org.yanoproject.runtime.tx.BlockTransactionSelectors;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.ADA;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.SLOT;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.admit;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.build;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.confirm;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.extraInput;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.hash;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.payment;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.withCerts;

/**
 * ADR-056 Phase 6b, block production: selection validates the published entries on its own block-build base
 * (ticked to the forge slot), with rule {@code LEDGER}, origin {@code BLOCK_BUILD} and each entry's
 * {@code ValidatedTx} as {@code previous}, over a fresh block-local overlay.
 */
class LedgerMempoolBlockSelectionTest {

    private static final StakePoolId POOL_77 = new StakePoolId(HexUtil.decodeHexString(TestKey.DEV_77.keyHash()));

    private record Candidate(String txHash, TxValidationRequest request, TxValidationOutcome outcome) {
    }

    private final MempoolTestWorld.StubEvaluator evaluator = new MempoolTestWorld.StubEvaluator();
    private final MempoolTestWorld.HeldExecutor worker = new MempoolTestWorld.HeldExecutor();
    private final List<Candidate> candidates = new CopyOnWriteArrayList<>();
    private volatile Runnable onCandidate = () -> { };
    private LedgerView world;
    private MempoolTestWorld.Chain chain;
    private LedgerMempool mempool;
    private BlockTransactionSelector selector;

    @BeforeEach
    void setUp() {
        world = MempoolTestWorld.world(MutationWorld.protocolParams(), 16);
        chain = new MempoolTestWorld.Chain(world);
        mempool = MempoolTestWorld.mempool(chain, evaluator, worker, LedgerMempool.Settings.defaults());
        chain.laneHeld = mempool::laneHeldByCurrentThread;
        mempool.setObserver(new LedgerMempool.RebuildObserver() {
            @Override
            public void blockCandidateValidated(String txHash, TxValidationRequest request,
                                                TxValidationOutcome outcome) {
                assertThat(mempool.laneHeldByCurrentThread()).as("selection never holds the lane").isFalse();
                candidates.add(new Candidate(txHash, request, outcome));
                onCandidate.run();
            }
        });
        mempool.start();
        selector = BlockTransactionSelectors.fromMemPool(() -> mempool, () -> null, () -> null,
                LoggerFactory.getLogger(getClass()));
    }

    @AfterEach
    void tearDown() {
        mempool.close();
        assertThat(chain.liveBases()).as("no leaked canonical bases").isZero();
        assertThat(chain.acquiredUnderLane.get()).as("no snapshot acquired under the lane").isZero();
        assertThat(mempool.ledgerStatus().lockOrderViolations()).isZero();
    }

    // ------------------------------------------------------------------ transactions

    private byte[] registerA() {
        return build(withCerts(payment(MutationWorld.KEY_INPUT, MutationWorld.PAYMENT), ADA.multiply(BigInteger.TWO)
                .negate(), new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), ADA.multiply(BigInteger.TWO))),
                world);
    }

    private byte[] delegateB() {
        return build(withCerts(payment(MutationWorld.RICH_INPUT, MutationWorld.PAYMENT), BigInteger.ZERO,
                new StakeDelegation(MutationWorld.stakeCredential(TestKey.DEV_42), POOL_77)), world);
    }

    private byte[] proposal() {
        TxSpec spec = payment(MutationWorld.GOV_INPUT, MutationWorld.PAYMENT);
        spec.proposals.add(ProposalProcedure.builder().deposit(MutationWorld.GOV_ACTION_DEPOSIT)
                .rewardAccount(MutationWorld.rewardAccount(TestKey.DEV_77, MutationWorld.NETWORK))
                .govAction(new InfoAction())
                .anchor(new Anchor("https://example.com/proposal.json", new byte[32]))
                .build());
        spec.changeAdjust = MutationWorld.GOV_ACTION_DEPOSIT.negate();
        return build(spec, world);
    }

    private byte[] vote(byte[] proposal) {
        TxSpec spec = payment(extraInput(14), ADA.multiply(BigInteger.TWO));
        VotingProcedures votes = new VotingProcedures();
        votes.add(new Voter(VoterType.DREP_KEY_HASH, MutationWorld.credential(TestKey.DEV_77)),
                new GovActionId(hash(proposal), 0), new VotingProcedure(Vote.YES, null));
        spec.votingProcedures = votes;
        spec.signers.add(TestKey.DEV_77);
        return build(spec, world);
    }

    /** A payment from {@code extra} valid only up to {@code ttl}. */
    private byte[] pay(int extra, long ttl) {
        TxSpec spec = payment(extraInput(extra), ADA.multiply(BigInteger.TWO));
        spec.ttl = ttl;
        return build(spec, world);
    }

    /** A payment spending the change output (index 1, owned by dev-42) of the mempool transaction {@code parent}. */
    private byte[] child(byte[] parent) {
        TxSpec spec = payment(new TransactionInput(hash(parent), 1), ADA.multiply(BigInteger.TWO));
        return build(spec, mempool.published().overlay());
    }

    private void accept(byte[] tx) {
        MempoolAdmissionResult result = admit(mempool, tx);
        assertThat(result.status()).as(result.detail() + " " + result.rejections())
                .isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
    }

    private List<String> hashes(List<byte[]> txs) {
        return txs.stream().map(MempoolTestWorld::hash).toList();
    }

    private List<String> ids() {
        return mempool.published().entries().stream().map(MempoolEntry::txHash).toList();
    }

    // ------------------------------------------------------------------ gates

    @Test
    void dependentChainsAreSelectedInOrderOverABlockLocalOverlayAndReapplied() {
        byte[] a = registerA();
        byte[] b = delegateB();
        byte[] p = proposal();
        byte[] v = vote(p);
        byte[] c = pay(0, MutationWorld.TTL);
        accept(a);
        accept(b);
        accept(p);
        accept(v);
        accept(c);
        byte[] d = child(c);
        accept(d);

        List<byte[]> selected = selector.drainForBlock(SLOT + 5);

        assertThat(hashes(selected)).containsExactly(hash(a), hash(b), hash(p), hash(v), hash(c), hash(d));
        assertThat(candidates).hasSize(6).allSatisfy(candidate -> {
            assertThat(candidate.request().rule()).isEqualTo(TxValidationRequest.Rule.LEDGER);
            assertThat(candidate.request().origin()).isEqualTo(TxValidationRequest.Origin.BLOCK_BUILD);
            assertThat(candidate.request().previous()).as("the admission verdict is passed as previous")
                    .isNotNull();
            assertThat(candidate.request().env().currentSlot()).isEqualTo(SLOT + 5);
            assertThat(candidate.request().env().forecastBasisSlot()).as("next(tip)").isEqualTo(SLOT);
            assertThat(candidate.outcome()).isInstanceOfSatisfying(TxValidationOutcome.Valid.class,
                    valid -> assertThat(valid.reapplied()).as("same environment: re-applied").isTrue());
        });
        assertThat(evaluator.evaluations.get()).as("no Plutus in these transactions").isZero();
        assertThat(ids()).as("selection never removes selected transactions").hasSize(6);
        assertThat(selector.selectionCurrent()).isTrue();
        assertThat(mempool.ledgerStatus().blockSelections()).isEqualTo(1);
        assertThat(mempool.ledgerStatus().blockSelectionReapplications()).isEqualTo(6);
        selector.blockSelectionCompleted();
    }

    @Test
    void selectionStopsBeforeTheBlockReferenceScriptsExceedTheLimit() {
        // Each payment references the same 180,000-byte script: five fit 1 MiB, the sixth would not.
        List<byte[]> txs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TxSpec spec = payment(extraInput(i), ADA.multiply(BigInteger.TWO));
            spec.referenceInputs.add(MempoolTestWorld.REFERENCE_SCRIPT_INPUT);
            spec.feeAdjust = MinFee.tierRefScriptFee(ConwayLedgerConstants.HASKELL,
                    MutationWorld.protocolParams().getMinFeeRefScriptCostPerByte(),
                    MempoolTestWorld.REFERENCE_SCRIPT_SIZE);
            byte[] tx = build(spec, world);
            accept(tx);
            txs.add(tx);
        }

        List<byte[]> selected = selector.drainForBlock(SLOT + 5);

        assertThat(hashes(selected)).containsExactlyElementsOf(hashes(txs.subList(0, 5)));
        assertThat(5L * MempoolTestWorld.REFERENCE_SCRIPT_SIZE)
                .isLessThanOrEqualTo(LedgerMempool.MAX_REF_SCRIPT_SIZE_PER_BLOCK)
                .isLessThan(6L * MempoolTestWorld.REFERENCE_SCRIPT_SIZE);
        assertThat(ids()).as("the sixth stays in the mempool for the next block").hasSize(6);
        selector.blockSelectionCompleted();
    }

    @Test
    void aLaggingMempoolSelectsTheDependentsOfConfirmedTransactionsAndDefersTheRemoval() {
        byte[] a = registerA();
        byte[] b = delegateB();
        accept(a);
        accept(b);
        chain.publish(confirm(world, evaluator, a));   // A confirmed; the (held) rebuild has not run
        assertThat(mempool.isStale()).isTrue();

        List<byte[]> selected = selector.drainForBlock(SLOT + 1);

        assertThat(hashes(selected)).as("A is canonical now; B (its dependent) is still valid")
                .containsExactly(hash(b));
        assertThat(ids()).as("the removal of A is deferred: it must not cascade B").containsExactly(hash(a), hash(b));
        assertThat(mempool.ledgerStatus().deferredRemovals()).isEqualTo(1);
        selector.blockSelectionCompleted();

        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(ids()).containsExactly(hash(b));
    }

    @Test
    void aTransactionInvalidAtTheForgeSlotIsRejectedAndRemovedWithItsDependents() {
        byte[] expiring = pay(0, SLOT + 10);
        accept(expiring);
        byte[] dependent = child(expiring);
        accept(dependent);
        byte[] independent = pay(1, MutationWorld.TTL);
        accept(independent);

        List<byte[]> selected = selector.drainForBlock(SLOT + 20);

        assertThat(hashes(selected)).containsExactly(hash(independent));
        assertThat(candidates).filteredOn(c -> c.txHash().equals(hash(expiring))).singleElement()
                .satisfies(c -> assertThat(c.outcome().toString()).contains("OutsideValidityIntervalUTxO"));
        assertThat(ids()).as("fresh mempool: removed with its dependent").containsExactly(hash(independent));
        assertThat(mempool.ledgerStatus().blockSelectionRejected()).isEqualTo(2);
        selector.blockSelectionCompleted();
    }

    @Test
    void aCanonicalChangeDuringSelectionDiscardsAndRedoesIt() {
        byte[] a = registerA();
        byte[] b = delegateB();
        accept(a);
        accept(b);
        AtomicBoolean once = new AtomicBoolean();
        onCandidate = () -> {
            if (once.compareAndSet(false, true)) {
                chain.publish(world);   // a new generation (for example a rollback) mid-selection
            }
        };

        List<byte[]> selected = selector.drainForBlock(SLOT + 1);

        assertThat(hashes(selected)).containsExactly(hash(a), hash(b));
        assertThat(mempool.ledgerStatus().blockSelectionRedos()).isEqualTo(1);
        assertThat(chain.blockBuildAcquired.get()).as("a new base for the redo").isEqualTo(2);
        assertThat(selector.selectionCurrent()).isTrue();

        chain.publish(world);
        assertThat(selector.selectionCurrent()).as("the producer's store section discards it").isFalse();
        selector.blockSelectionFailed();
        assertThat(selector.selectionCurrent()).isTrue();
    }

    @Test
    void aTransientFailureSkipsWithoutRemovalAndLaterFailuresAreSkippedToo() {
        byte[] a = registerA();
        byte[] b = delegateB();
        byte[] c = pay(0, MutationWorld.TTL);
        accept(a);
        accept(b);
        accept(c);
        chain.publish(withoutAccounts(world));

        List<byte[]> selected = selector.drainForBlock(SLOT + 1);

        assertThat(hashes(selected)).as("the payment reads no account").containsExactly(hash(c));
        assertThat(ids()).as("nothing removed for an unavailable read").containsExactly(hash(a), hash(b), hash(c));
        assertThat(mempool.ledgerStatus().blockSelectionSkipped()).isEqualTo(2);
        selector.blockSelectionCompleted();
    }

    @Test
    void aTransientFailureThatPersistsIsRemovedAfterTheLimit() {
        byte[] a = registerA();
        byte[] b = delegateB();
        byte[] c = pay(0, MutationWorld.TTL);
        accept(a);
        accept(b);
        accept(c);
        chain.blockBuildWorld = withoutAccounts(world);   // the mempool stays fresh; only selection fails

        for (int i = 1; i < LedgerMempool.TRANSIENT_SKIP_LIMIT; i++) {
            assertThat(hashes(selector.drainForBlock(SLOT + 1))).containsExactly(hash(c));
            selector.blockSelectionCompleted();
        }
        assertThat(ids()).as("not removed before the limit").containsExactly(hash(a), hash(b), hash(c));

        assertThat(hashes(selector.drainForBlock(SLOT + 1))).containsExactly(hash(c));
        selector.blockSelectionCompleted();

        assertThat(ids()).as("a failure that persists is not transient: removed").containsExactly(hash(c));
    }

    @Test
    void anUnavailableBaseOrACatchingUpMempoolSelectsNothing() {
        accept(pay(0, MutationWorld.TTL));
        chain.blockBuildUnavailable = () -> true;
        assertThat(selector.drainForBlock(SLOT + 1)).isEmpty();
        assertThat(mempool.size()).isEqualTo(1);
        chain.blockBuildUnavailable = () -> false;
        assertThat(selector.drainForBlock(SLOT + 1)).hasSize(1);
        selector.blockSelectionCompleted();
    }

    /** {@code base} with every account read unavailable (a transient failure). */
    private static LedgerView withoutAccounts(LedgerView base) {
        return (LedgerView) Proxy.newProxyInstance(LedgerView.class.getClassLoader(), new Class<?>[]{LedgerView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("account")) {
                        return Lookup.unavailable("test: accounts unavailable");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
