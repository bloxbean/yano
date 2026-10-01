package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.TxSigner;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.runtime.mempool.LedgerMempoolStatus;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ADR-056 Phase 6 devnet matrix (Phase 6b), run against an in-process {@link DevnetGateNode}; ADR-057 Phase C
 * runs the same matrix with {@code engine: amaru-scalus} and compares the {@link Report#lines() report}.
 *
 * <ul>
 *   <li><b>A, one block:</b> the producer is stopped, the four dependent chains (stake register → delegate → vote
 *       delegation; DRep register → delegate; proposal → vote; register → deregister) are admitted while their
 *       parents are pending, then all are forged in one block. Three rejections (unregistered delegator, vote on a
 *       missing proposal, double registration) are checked on the way.</li>
 *   <li><b>B, across blocks:</b> with the producer running, a chain whose children are admitted before their
 *       parents are confirmed.</li>
 *   <li><b>C, epoch crossing:</b> chains pending across a boundary; a vote on a proposal that expires at it (never
 *       forged: {@code GOV.VotingOnExpiredGovAction}); a {@code currentTreasuryValue} admitted before the boundary
 *       block (rejected after it when the treasury moved) and one built after it (forged, dependency (d)); a
 *       withdrawal of the reward balance a proposal refund credits at a later boundary.</li>
 *   <li><b>D, rollback:</b> a rollback removes the parent of pending children; the rebuild drops them, keeps the
 *       independent one; resubmitted, they are forged again.</li>
 * </ul>
 * <p>Every produced block is re-validated independently ({@link BlockRevalidator}). The genesis funds of the
 * devnet mnemonic pay for everything, so every input is on chain (a Haskell follower sees the same state).</p>
 */
public final class LedgerRulesDevnetMatrix {

    /** The devnet genesis funds' mnemonic (public devnet fixture). */
    public static final String DEVNET_MNEMONIC = "test test test test test test test test test test test test test "
            + "test test test test test test test test test test sauce";
    /** The devnet genesis pool. */
    public static final String DEVNET_POOL = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";

    private static final Network NETWORK = Networks.testnet();
    private static final Anchor ANCHOR = new Anchor("https://example.com/proposal.json", new byte[32]);

    /**
     * The result of one run.
     *
     * @param lines    engine-independent observations (verdicts, block placement, mempool contents), compared
     *                 between engines by ADR-057 Phase C
     * @param problems gate failures (must be empty)
     * @param metrics  measurements that differ between runs (block numbers, latencies)
     */
    public record Report(List<String> lines, List<String> problems, Map<String, Object> metrics) {
    }

    private final DevnetGateNode node;
    private final GateTxFactory factory;
    private final Account payer = account(DEVNET_MNEMONIC, 0);
    private final Account payer2 = account(DEVNET_MNEMONIC, 1);
    private final Account payer3 = account(DEVNET_MNEMONIC, 2);
    private final String stakeMnemonic;
    private final GateWallet wallet;
    private final GateWallet wallet2;
    private final GateWallet wallet3;
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, Object> metrics = new LinkedHashMap<>();

    public LedgerRulesDevnetMatrix(DevnetGateNode node, String stakeMnemonic) {
        this.node = node;
        this.stakeMnemonic = stakeMnemonic;
        this.factory = new GateTxFactory(node::protocolParams);
        this.wallet = new GateWallet(Set.of(payer.baseAddress()));
        this.wallet2 = new GateWallet(Set.of(payer2.baseAddress()));
        this.wallet3 = new GateWallet(Set.of(payer3.baseAddress()));
    }

    /** Account {@code account} of {@code mnemonic} (m/1852'/1815'/account'/0/0); the devnet funds accounts 0-19. */
    private static Account account(String mnemonic, int account) {
        return Account.createFromMnemonic(NETWORK, mnemonic, account, 0);
    }

    /** Stake, DRep and return accounts: fresh keys (indexes above the funded genesis addresses). */
    private Account staker(int index) {
        return account(stakeMnemonic, 100 + index);
    }

    public Report run() throws Exception {
        node.await("first blocks", 60_000, () -> node.tipBlockNumber() >= 2);
        node.genesisUtxo(payer.baseAddress()).ifPresent(wallet::add);
        node.genesisUtxo(payer2.baseAddress()).ifPresent(wallet2::add);
        node.genesisUtxo(payer3.baseAddress()).ifPresent(wallet3::add);
        if (wallet.size() == 0 || wallet2.size() == 0 || wallet3.size() == 0) {
            throw new IllegalStateException("the devnet genesis funds of accounts 0-2 are missing");
        }
        caseOneBlock();
        caseAcrossBlocks();
        caseRollback();
        caseEpochCrossing();
        caseBudgetWhileForging();
        node.awaitMempoolFresh(30_000);
        node.revalidator().drain();
        checkRevalidation();
        return new Report(List.copyOf(lines), List.copyOf(problems), Map.copyOf(metrics));
    }

    // ------------------------------------------------------------------ case A: one block

    private GovActionId proposalA;
    private Account stakerA;
    private Account drep;
    private Account returnAccount;

    private void caseOneBlock() throws Exception {
        stakerA = staker(0);
        drep = staker(1);
        returnAccount = staker(2);
        Account registerDeregister = staker(3);
        Account unregistered = staker(4);

        node.producer().stopProducer();
        node.awaitMempoolFresh(30_000);
        var regS = submit("A.stake-register", factory.build(wallet,
                new Tx().registerStakeAddress(stakerA.baseAddress()).from(payer.baseAddress()), signer(payer)));
        var delegS = submit("A.stake-delegate", factory.build(wallet,
                new Tx().delegateTo(stakerA.baseAddress(), DEVNET_POOL).from(payer.baseAddress()),
                signer(payer, stake(stakerA))));
        var regD = submit("A.drep-register", factory.build(wallet,
                new Tx().registerDRep(drep.drepCredential(), ANCHOR).from(payer.baseAddress()),
                signer(payer, drepKey(drep))));
        var voteDeleg = submit("A.vote-delegate", factory.build(wallet,
                new Tx().delegateVotingPowerTo(stakerA.baseAddress(), drepOf(drep)).from(payer.baseAddress()),
                signer(payer, stake(stakerA))));
        var regR = submit("A.return-register", factory.build(wallet,
                new Tx().registerStakeAddress(returnAccount.baseAddress()).from(payer.baseAddress()), signer(payer)));
        // From protocol version 10 a withdrawal needs a DRep delegation (LEDGER.ConwayWdrlNotDelegatedToDRep): the
        // return account will withdraw its proposal refunds in case C.
        var returnVoteDeleg = submit("A.return-vote-delegate", factory.build(wallet,
                new Tx().delegateVotingPowerTo(returnAccount.baseAddress(), drepOf(drep)).from(payer.baseAddress()),
                signer(payer, stake(returnAccount))));
        var proposal = submit("A.proposal", factory.build(wallet,
                new Tx().createProposal(new InfoAction(), returnAccount.stakeAddress(), ANCHOR)
                        .from(payer.baseAddress()), signer(payer)));
        proposalA = new GovActionId(proposal.hash(), 0);
        var vote = submit("A.vote", factory.build(wallet,
                new Tx().createVote(drepVoter(drep), proposalA, Vote.YES).from(payer.baseAddress()),
                signer(payer, drepKey(drep))));
        var regX = submit("A.register", factory.build(wallet,
                new Tx().registerStakeAddress(registerDeregister.baseAddress()).from(payer.baseAddress()),
                signer(payer)));
        var deregX = submit("A.deregister", factory.build(wallet,
                new Tx().deregisterStakeAddress(registerDeregister.baseAddress()).from(payer.baseAddress()),
                signer(payer, stake(registerDeregister))));

        // Rejections, from an independent payer (never applied to its wallet).
        submitRejected("A.reject-unregistered-delegator", factory.build(wallet2,
                new Tx().delegateTo(unregistered.baseAddress(), DEVNET_POOL).from(payer2.baseAddress()),
                signer(payer2, stake(unregistered))));
        submitRejected("A.reject-vote-missing-proposal", factory.build(wallet2,
                new Tx().createVote(drepVoter(drep), new GovActionId("ab".repeat(32), 0), Vote.NO)
                        .from(payer2.baseAddress()), signer(payer2, drepKey(drep))));
        submitRejected("A.reject-double-registration", factory.build(wallet2,
                new Tx().registerStakeAddress(stakerA.baseAddress()).from(payer2.baseAddress()), signer(payer2)));

        List<String> chain = List.of(regS, delegS, regD, voteDeleg, regR, returnVoteDeleg, proposal, vote, regX, deregX)
                .stream()
                .map(GateTxFactory.Built::hash).toList();
        lines.add("A.mempool-before-forging " + labelsOf(node.mempoolHashes()));
        node.producer().startProducer();
        node.awaitConfirmed(chain, 60_000);
        Set<Long> blocks = new HashSet<>();
        chain.forEach(h -> blocks.add(node.blockOf(h)));
        lines.add("A.forged-in-one-block " + (blocks.size() == 1));
        if (blocks.size() != 1) {
            problems.add("case A: the chains were forged in " + blocks.size() + " blocks: " + blocks);
        }
        metrics.put("A.block", blocks);
        node.awaitMempoolFresh(30_000);
        lines.add("A.mempool-after " + labelsOf(node.mempoolHashes()));
    }

    // ------------------------------------------------------------------ case B: across blocks

    private GovActionId proposalB;

    private void caseAcrossBlocks() throws Exception {
        Account stakerB = staker(5);
        // Each parent-child pair is built first and submitted right after a block, so the child is admitted
        // while its parent is still pending (checked, not assumed: a block landing in between fails the gate).
        var regTx = factory.build(wallet, new Tx().registerStakeAddress(stakerB.baseAddress())
                .from(payer.baseAddress()), signer(payer)).commit();
        var delegTx = factory.build(wallet, new Tx().delegateTo(stakerB.baseAddress(), DEVNET_POOL)
                .from(payer.baseAddress()), signer(payer, stake(stakerB))).commit();
        node.awaitBlocks(1, 30_000);
        var reg = submitBuilt("B.stake-register", regTx);
        var deleg = submitBuilt("B.stake-delegate", delegTx);
        boolean registrationPending = !node.confirmed(reg.hash());
        node.awaitConfirmed(List.of(reg.hash(), deleg.hash()), 30_000);
        var voteDeleg = submit("B.vote-delegate", factory.build(wallet,
                new Tx().delegateVotingPowerTo(stakerB.baseAddress(), drepOf(drep)).from(payer.baseAddress()),
                signer(payer, stake(stakerB))));
        node.awaitConfirmed(List.of(voteDeleg.hash()), 30_000);
        var proposalTx = factory.build(wallet, new Tx().createProposal(new InfoAction(),
                returnAccount.stakeAddress(), ANCHOR).from(payer.baseAddress()), signer(payer)).commit();
        proposalB = new GovActionId(proposalTx.hash(), 0);
        var voteTx = factory.build(wallet, new Tx().createVote(drepVoter(drep), proposalB, Vote.ABSTAIN)
                .from(payer.baseAddress()), signer(payer, drepKey(drep))).commit();
        node.awaitBlocks(1, 30_000);
        var proposal = submitBuilt("B.proposal", proposalTx);
        var vote = submitBuilt("B.vote", voteTx);
        boolean proposalPending = !node.confirmed(proposal.hash());
        List<String> chain = List.of(reg.hash(), deleg.hash(), voteDeleg.hash(), proposal.hash(), vote.hash());
        node.awaitConfirmed(chain, 60_000);
        Set<Long> blocks = new HashSet<>();
        chain.forEach(h -> blocks.add(node.blockOf(h)));
        metrics.put("B.blocks", blocks);
        boolean pending = registrationPending && proposalPending;
        lines.add("B.children-admitted-with-parents-pending " + pending);
        if (!pending) {
            problems.add("case B: a parent was confirmed before its child was admitted (the case is vacuous)");
        }
        lines.add("B.forged-across-blocks " + (blocks.size() >= 3));
        if (blocks.size() < 3) {
            problems.add("case B: expected the chain across at least three blocks, got " + blocks);
        }
        proposalEpochB = node.epochOf(node.blockSlot(node.blockOf(proposal.hash())));
    }

    private long proposalEpochB;

    // ------------------------------------------------------------------ case D: rollback

    private void caseRollback() throws Exception {
        Account stakerD = staker(6);
        Map<String, Utxo> before = wallet.snapshot();
        var parent = submit("D.stake-register", factory.build(wallet,
                new Tx().registerStakeAddress(stakerD.baseAddress()).from(payer.baseAddress()), signer(payer)));
        node.awaitConfirmed(List.of(parent.hash()), 30_000);
        long parentSlot = node.blockSlot(node.blockOf(parent.hash()));
        node.producer().stopProducer();
        node.awaitMempoolFresh(30_000);
        var certChild = submit("D.stake-delegate", factory.build(wallet,
                new Tx().delegateTo(stakerD.baseAddress(), DEVNET_POOL).from(payer.baseAddress()),
                signer(payer, stake(stakerD))));
        var independent = submit("D.independent-payment", factory.build(wallet3,
                new Tx().payToAddress(payer3.baseAddress(), Amount.ada(3))
                        .from(payer3.baseAddress()), signer(payer3)));
        lines.add("D.mempool-before-rollback " + labelsOf(node.mempoolHashes()));

        // Roll back to the block before the parent's.
        node.rollbackToSlot(parentSlot - 1);
        node.awaitMempoolFresh(30_000);
        List<String> afterRollback = node.mempoolHashes();
        lines.add("D.mempool-after-rollback " + labelsOf(afterRollback));
        lines.add("D.child-dropped-with " + distinctFailures(certChild.hash()));
        lines.add("D.parent-confirmed-after-rollback " + node.confirmed(parent.hash()));
        if (afterRollback.contains(certChild.hash())) {
            problems.add("case D: the delegation survived the rollback of its registration");
        }
        if (!afterRollback.contains(independent.hash())) {
            problems.add("case D: the independent payment was dropped by the rollback rebuild");
        }
        node.producer().startProducer();
        node.awaitConfirmed(List.of(independent.hash()), 30_000);
        node.awaitBlocks(2, 30_000);
        lines.add("D.child-confirmed-without-parent " + node.confirmed(certChild.hash()));
        if (node.confirmed(certChild.hash())) {
            problems.add("case D: the delegation was forged without its registration");
        }
        // Resubmit the chain (the rollback did not return the parent to the mempool).
        wallet.restore(before);
        var parentAgain = submit("D.stake-register-again", factory.build(wallet,
                new Tx().registerStakeAddress(stakerD.baseAddress()).from(payer.baseAddress()), signer(payer)));
        var childAgain = submit("D.stake-delegate-again", factory.build(wallet,
                new Tx().delegateTo(stakerD.baseAddress(), DEVNET_POOL).from(payer.baseAddress()),
                signer(payer, stake(stakerD))));
        node.awaitConfirmed(List.of(parentAgain.hash(), childAgain.hash()), 30_000);
        lines.add("D.resubmitted-confirmed true");
    }

    // ------------------------------------------------------------------ case C: epoch crossing

    private void caseEpochCrossing() throws Exception {
        // The proposal of case B expires after epoch proposalEpochB + 1 (lifetime 1).
        long lastEpoch = proposalEpochB + 1;
        long boundary = node.epochStartSlot(lastEpoch + 1);
        node.awaitWallClockSlot(boundary - 25, 180_000);
        node.producer().stopProducer();
        node.awaitMempoolFresh(30_000);
        long stoppedEpoch = node.epochOf(node.blockSlot(node.tipBlockNumber()));
        lines.add("C.stopped-in-last-epoch-of-proposal " + (stoppedEpoch == lastEpoch));
        Account stakerC = staker(7);
        Map<String, Utxo> wallet2Before = wallet2.snapshot();
        Map<String, Utxo> wallet3Before = wallet3.snapshot();
        var expiringVote = submit("C.vote-on-expiring-proposal", factory.build(wallet2,
                new Tx().createVote(drepVoter(drep), proposalB, Vote.NO).from(payer2.baseAddress()),
                signer(payer2, drepKey(drep))));
        var reg = submit("C.stake-register", factory.build(wallet,
                new Tx().registerStakeAddress(stakerC.baseAddress()).from(payer.baseAddress()), signer(payer)));
        var deleg = submit("C.stake-delegate", factory.build(wallet,
                new Tx().delegateTo(stakerC.baseAddress(), DEVNET_POOL).from(payer.baseAddress()),
                signer(payer, stake(stakerC))));
        BigInteger treasuryBefore = node.treasury();
        var oldTreasury = submit("C.treasury-value-before-boundary", factory.build(wallet3,
                new Tx().payToAddress(payer3.baseAddress(), Amount.ada(2))
                        .from(payer3.baseAddress()), signer(payer3),
                (ctx, txn) -> txn.getBody().setCurrentTreasuryValue(treasuryBefore)));
        lines.add("C.mempool-before-boundary " + labelsOf(node.mempoolHashes()));

        node.awaitWallClockSlot(boundary + 10, 60_000);
        node.producer().startProducer();
        node.awaitConfirmed(List.of(reg.hash(), deleg.hash()), 60_000);
        long firstBlockEpoch = node.epochOf(node.blockSlot(node.blockOf(reg.hash())));
        lines.add("C.chain-forged-after-boundary " + (firstBlockEpoch == lastEpoch + 1));
        node.awaitBlocks(2, 30_000);
        node.awaitMempoolFresh(30_000);
        boolean voteForged = node.confirmed(expiringVote.hash());
        lines.add("C.expired-vote-forged " + voteForged);
        lines.add("C.expired-vote-in-mempool " + node.mempoolHashes().contains(expiringVote.hash()));
        lines.add("C.expired-vote-dropped-with " + distinctFailures(expiringVote.hash()));
        if (voteForged) {
            problems.add("case C: a vote on an expired proposal was forged");
        } else {
            wallet2.restore(wallet2Before);   // the dropped vote's outputs never existed
        }
        BigInteger treasuryAfter = node.treasury();
        boolean treasuryMoved = !treasuryAfter.equals(treasuryBefore);
        lines.add("C.treasury-moved " + treasuryMoved);
        if (!treasuryMoved) {
            problems.add("case C: the treasury did not move at the boundary, so the stale currentTreasuryValue case "
                    + "(dependency (d)) is vacuous");
        }
        boolean oldForged = node.confirmed(oldTreasury.hash());
        lines.add("C.stale-treasury-value-forged " + oldForged);
        lines.add("C.stale-treasury-value-dropped-with " + distinctFailures(oldTreasury.hash()));
        if (treasuryMoved && oldForged) {
            problems.add("case C: a stale currentTreasuryValue was forged after the boundary");
        }
        if (!oldForged) {
            wallet3.restore(wallet3Before);
        }
        var newTreasury = submit("C.treasury-value-after-boundary", factory.build(wallet3,
                new Tx().payToAddress(payer3.baseAddress(), Amount.ada(2))
                        .from(payer3.baseAddress()), signer(payer3),
                (ctx, txn) -> txn.getBody().setCurrentTreasuryValue(treasuryAfter)));
        node.awaitConfirmed(List.of(newTreasury.hash()), 30_000);
        lines.add("C.treasury-value-after-boundary-forged true");

        // Withdrawal of freshly credited rewards: the proposals of cases A and B are removed at the boundary after
        // their last epoch, and their deposits are refunded to the return account's reward balance.
        CredentialKey returnCredential = CredentialKey.key(HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(returnAccount.stakeHdKeyPair().getPublicKey().getKeyData())));
        node.await("proposal refund", 240_000, () -> {
            BigInteger balance = node.rewardBalance(returnCredential);
            return balance != null && balance.signum() > 0;
        });
        BigInteger refund = node.rewardBalance(returnCredential);
        lines.add("C.refund-credited-ada " + refund.divide(BigInteger.valueOf(1_000_000)));
        var withdrawal = submit("C.withdraw-refund", factory.build(wallet,
                new Tx().withdraw(returnAccount.stakeAddress(), refund).from(payer.baseAddress()),
                signer(payer, stake(returnAccount))));
        node.awaitConfirmed(List.of(withdrawal.hash()), 30_000);
        node.awaitMempoolFresh(30_000);
        lines.add("C.balance-after-withdrawal " + node.rewardBalance(returnCredential));
    }


    // ------------------------------------------------------------------ budget while forging

    private void caseBudgetWhileForging() throws Exception {
        int count = Integer.getInteger("yano.gate.budget-txs", 400);
        List<GateTxFactory.Built> txs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GateWallet w = i % 2 == 0 ? wallet2 : wallet3;
            Account a = i % 2 == 0 ? payer2 : payer3;
            txs.add(factory.build(w, new Tx().payToAddress(a.baseAddress(),
                    Amount.ada(1)).from(a.baseAddress()), signer(a)).commit());
        }
        long[] nanos = new long[count];
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            DevnetGateNode.Verdict verdict = node.submit(txs.get(i).cbor(), txs.get(i).hash());
            nanos[i] = System.nanoTime() - started;
            if (!verdict.accepted()) {
                rejected++;
            }
        }
        node.awaitConfirmed(txs.stream().map(GateTxFactory.Built::hash).toList(), 120_000);
        Arrays.sort(nanos);
        metrics.put("budget.txs", count);
        metrics.put("budget.rejected", rejected);
        metrics.put("budget.admission-p50-ms", nanos[count / 2] / 1e6);
        metrics.put("budget.admission-p99-ms", nanos[(int) Math.min(count - 1, Math.ceil(count * 0.99) - 1)] / 1e6);
        metrics.put("budget.admission-max-ms", nanos[count - 1] / 1e6);
        LedgerMempoolStatus status = node.mempoolStatus();
        metrics.put("budget.last-block-selection-ms", status.lastBlockSelectionMillis());
        metrics.put("budget.last-rebuild-ms", status.lastRebuildMillis());
        if (rejected > 0) {
            problems.add("budget: " + rejected + " chained payments were rejected");
        }
        lines.add("budget.confirmed " + count);
    }

    // ------------------------------------------------------------------ block re-validation

    private void checkRevalidation() {
        List<BlockRevalidator.BlockCheck> checks = node.revalidator().checks();
        long clean = checks.stream().filter(BlockRevalidator.BlockCheck::clean).count();
        long txs = checks.stream().mapToLong(c -> c.txHashes().size()).sum();
        metrics.put("revalidation.blocks", checks.size());
        metrics.put("revalidation.txs", txs);
        metrics.put("revalidation.engines", checks.isEmpty() ? List.of() : List.copyOf(checks.getFirst().failures().keySet()));
        for (BlockRevalidator.BlockCheck check : checks) {
            if (!check.clean()) {
                problems.add("block " + check.blockNumber() + " failed independent re-validation: " + check);
            }
        }
        for (Throwable error : node.revalidator().errors()) {
            problems.add("re-validation error: " + error);
        }
        if (clean == 0) {
            problems.add("no block was re-validated");
        }
        LedgerMempoolStatus status = node.mempoolStatus();
        metrics.put("mempool.status", status);
        metrics.put("C.expired-vote-seen", node.failuresOf(labelHash("C.vote-on-expiring-proposal")));
        metrics.put("C.stale-treasury-seen", node.failuresOf(labelHash("C.treasury-value-before-boundary")));
        if (status.lockOrderViolations() != 0) {
            problems.add("lock-order violations: " + status.lockOrderViolations());
        }
    }

    // ------------------------------------------------------------------ helpers

    private GateTxFactory.Built submit(String label, GateTxFactory.Built tx) {
        labels.put(tx.hash(), label);
        DevnetGateNode.Verdict verdict = node.submit(tx.cbor(), tx.hash());
        lines.add(label + " " + verdict.constructor());
        if (!verdict.accepted()) {
            problems.add(label + " was rejected: " + verdict.message());
            throw new IllegalStateException(label + " was rejected: " + verdict.message());
        }
        return tx.commit();
    }

    /** Submits a transaction already applied to its wallet ({@link GateTxFactory.Built#commit()}). */
    private GateTxFactory.Built submitBuilt(String label, GateTxFactory.Built tx) {
        labels.put(tx.hash(), label);
        DevnetGateNode.Verdict verdict = node.submit(tx.cbor(), tx.hash());
        lines.add(label + " " + verdict.constructor());
        if (!verdict.accepted()) {
            problems.add(label + " was rejected: " + verdict.message());
            throw new IllegalStateException(label + " was rejected: " + verdict.message());
        }
        return tx;
    }

    private void submitRejected(String label, GateTxFactory.Built tx) {
        labels.put(tx.hash(), label);
        DevnetGateNode.Verdict verdict = node.submit(tx.cbor(), tx.hash());
        lines.add(label + " " + verdict.constructor());
        if (verdict.accepted()) {
            problems.add(label + " was accepted");
        }
    }

    private String labelHash(String label) {
        return labels.entrySet().stream().filter(e -> e.getValue().equals(label)).map(Map.Entry::getKey)
                .findFirst().orElse("");
    }

    /** The distinct failure constructors recorded for {@code txHash}, without where they were seen. */
    private List<String> distinctFailures(String txHash) {
        return node.failuresOf(txHash).stream().map(f -> f.substring(f.indexOf(':') + 1)).distinct().sorted()
                .toList();
    }

    private List<String> labelsOf(List<String> hashes) {
        return hashes.stream().map(h -> labels.getOrDefault(h, "unlabelled")).toList();
    }

    private static TxSigner signer(Account payer, TxSigner... more) {
        TxSigner signer = SignerProviders.signerFrom(payer);
        for (TxSigner extra : more) {
            signer = signer.andThen(extra);
        }
        return signer;
    }

    private static TxSigner stake(Account account) {
        return SignerProviders.stakeKeySignerFrom(account);
    }

    private static TxSigner drepKey(Account account) {
        return SignerProviders.drepKeySignerFrom(account);
    }

    private static DRep drepOf(Account account) {
        return DRep.addrKeyHash(account.drepCredential().getBytes());
    }

    private static Voter drepVoter(Account account) {
        return new Voter(VoterType.DREP_KEY_HASH, account.drepCredential());
    }
}
