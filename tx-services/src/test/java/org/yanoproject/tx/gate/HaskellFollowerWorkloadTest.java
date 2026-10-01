package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 6b Haskell-follower workload: submits the Phase 6 dependent chains to a running Yano devnet node
 * (the release-QA {@code haskell-sync} run, started with the java-julc engine) over its REST API, so the Haskell
 * follower has to validate blocks that carry them. Runs only with {@code -Dyano.gate.remote-url=http://host:port}.
 *
 * <p>Expected genesis (the harness patches the pv10 devnet copy): epoch length 600 slots of 0.2 s, governance
 * action lifetime 1 epoch. Steps: the dependent chains back to back (one or a few blocks); a chain across blocks;
 * after the first boundary, a {@code currentTreasuryValue} transaction; after the proposal refund (boundary into
 * epoch 3), a withdrawal of the refunded reward balance. Everything is paid from the devnet genesis funds, so every
 * input exists on the Haskell side too.</p>
 */
@EnabledIfSystemProperty(named = "yano.gate.remote-url", matches = ".+")
class HaskellFollowerWorkloadTest {

    private static final Anchor ANCHOR = new Anchor("https://example.com/proposal.json", new byte[32]);
    private static final long EPOCH_LENGTH = Long.getLong("yano.gate.epoch-length", 600);

    private final RemoteYanoNode node = new RemoteYanoNode(System.getProperty("yano.gate.remote-url"));
    private final List<String> log = new ArrayList<>();
    private final PrintStream out = System.out;

    private final Account payer = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 0);
    private final GateWallet wallet = new GateWallet(Set.of(payer.baseAddress()));
    private final GateTxFactory factory = new GateTxFactory(node::protocolParams);

    @Test
    void submitTheDependentChainsToAFollowedDevnet() throws Exception {
        Utxo genesis = node.genesisUtxo(payer.baseAddress());
        wallet.add(genesis);
        Account staker = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 101);
        Account drep = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 102);
        Account returnAccount = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 103);
        Account registerDeregister = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 104);
        Account stakerB = account(LedgerRulesDevnetMatrix.DEVNET_MNEMONIC, 105);

        // A: the four chains, back to back (each admitted while its parents are pending).
        List<String> chainA = new ArrayList<>();
        chainA.add(submit("A.stake-register", new Tx().registerStakeAddress(staker.baseAddress())
                .from(payer.baseAddress()), signer()));
        chainA.add(submit("A.stake-delegate", new Tx().delegateTo(staker.baseAddress(),
                LedgerRulesDevnetMatrix.DEVNET_POOL).from(payer.baseAddress()), signer(stake(staker))));
        chainA.add(submit("A.drep-register", new Tx().registerDRep(drep.drepCredential(), ANCHOR)
                .from(payer.baseAddress()), signer(SignerProviders.drepKeySignerFrom(drep))));
        chainA.add(submit("A.vote-delegate", new Tx().delegateVotingPowerTo(staker.baseAddress(), drepOf(drep))
                .from(payer.baseAddress()), signer(stake(staker))));
        chainA.add(submit("A.return-register", new Tx().registerStakeAddress(returnAccount.baseAddress())
                .from(payer.baseAddress()), signer()));
        chainA.add(submit("A.return-vote-delegate", new Tx().delegateVotingPowerTo(returnAccount.baseAddress(),
                drepOf(drep)).from(payer.baseAddress()), signer(stake(returnAccount))));
        String proposal = submit("A.proposal", new Tx().createProposal(new InfoAction(),
                returnAccount.stakeAddress(), ANCHOR).from(payer.baseAddress()), signer());
        chainA.add(proposal);
        chainA.add(submit("A.vote", new Tx().createVote(voter(drep), new GovActionId(proposal, 0), Vote.YES)
                .from(payer.baseAddress()), signer(SignerProviders.drepKeySignerFrom(drep))));
        chainA.add(submit("A.register", new Tx().registerStakeAddress(registerDeregister.baseAddress())
                .from(payer.baseAddress()), signer()));
        chainA.add(submit("A.deregister", new Tx().deregisterStakeAddress(registerDeregister.baseAddress())
                .from(payer.baseAddress()), signer(stake(registerDeregister))));
        awaitConfirmed(chainA, 120_000);
        long proposalEpoch = epochOf(slotOf(proposal));
        note("A confirmed; proposal epoch " + proposalEpoch);

        // B: a chain across blocks.
        String regB = submit("B.stake-register", new Tx().registerStakeAddress(stakerB.baseAddress())
                .from(payer.baseAddress()), signer());
        String delegB = submit("B.stake-delegate", new Tx().delegateTo(stakerB.baseAddress(),
                LedgerRulesDevnetMatrix.DEVNET_POOL).from(payer.baseAddress()), signer(stake(stakerB)));
        awaitConfirmed(List.of(regB, delegB), 60_000);
        String proposalB = submit("B.proposal", new Tx().createProposal(new InfoAction(),
                returnAccount.stakeAddress(), ANCHOR).from(payer.baseAddress()), signer());
        String voteB = submit("B.vote", new Tx().createVote(voter(drep), new GovActionId(proposalB, 0), Vote.NO)
                .from(payer.baseAddress()), signer(SignerProviders.drepKeySignerFrom(drep)));
        awaitConfirmed(List.of(proposalB, voteB), 60_000);
        note("B confirmed");

        // After the next boundary: a currentTreasuryValue transaction (ADR-056 Phase 6a dependency (d)).
        long nextEpoch = epochOf(tipSlot()) + 1;
        await("epoch " + nextEpoch, 600_000, () -> epochOf(tipSlot()) >= nextEpoch);
        Thread.sleep(3_000);
        long epoch = epochOf(tipSlot());
        BigInteger treasury = node.treasury(epoch);
        String treasuryTx = submitWith("C.treasury-value", new Tx().payToAddress(payer.baseAddress(), Amount.ada(2))
                .from(payer.baseAddress()), signer(), treasury);
        awaitConfirmed(List.of(treasuryTx), 60_000);
        note("treasury tx confirmed in epoch " + epoch + " (treasury " + treasury + ")");

        // The proposals expire after proposalEpoch + 1 and are refunded at the boundary into proposalEpoch + 3.
        String returnStake = returnAccount.stakeAddress();
        await("proposal refund", 900_000, () -> node.withdrawable(returnStake).signum() > 0);
        BigInteger refund = node.withdrawable(returnStake);
        String withdrawal = submit("C.withdraw-refund", new Tx().withdraw(returnStake, refund)
                .from(payer.baseAddress()), signer(stake(returnAccount)));
        awaitConfirmed(List.of(withdrawal), 60_000);
        note("withdrew " + refund + " lovelace in epoch " + epochOf(tipSlot()));
        note("WORKLOAD PASS: last workload block slot " + slotOf(withdrawal));
        String report = System.getProperty("yano.gate.workload-report");
        if (report != null && !report.isBlank()) {
            Files.write(Path.of(report), log);
        }
        assertThat(node.withdrawable(returnStake)).isZero();
    }

    // ------------------------------------------------------------------ transactions

    private String submit(String label, Tx tx, TxSigner signer) throws Exception {
        return submitBuilt(label, factory.build(wallet, tx, signer));
    }

    private String submitWith(String label, Tx tx, TxSigner signer, BigInteger treasury) throws Exception {
        return submitBuilt(label, factory.build(wallet, tx, signer,
                (ctx, txn) -> txn.getBody().setCurrentTreasuryValue(treasury)));
    }

    private String submitBuilt(String label, GateTxFactory.Built built) throws Exception {
        Result<String> result = node.backend().getTransactionService().submitTransaction(built.cbor());
        if (!result.isSuccessful()) {
            note(label + " REJECTED " + result.getResponse());
            throw new AssertionError(label + " rejected: " + result.getResponse());
        }
        built.commit();
        note(label + " accepted " + built.hash());
        return built.hash();
    }

    private TxSigner signer(TxSigner... more) {
        TxSigner signer = SignerProviders.signerFrom(payer);
        for (TxSigner extra : more) {
            signer = signer.andThen(extra);
        }
        return signer;
    }

    private static TxSigner stake(Account account) {
        return SignerProviders.stakeKeySignerFrom(account);
    }

    private static DRep drepOf(Account account) {
        return DRep.addrKeyHash(account.drepCredential().getBytes());
    }

    private static Voter voter(Account account) {
        return new Voter(VoterType.DREP_KEY_HASH, account.drepCredential());
    }

    private static Account account(String mnemonic, int account) {
        return Account.createFromMnemonic(Networks.testnet(), mnemonic, account, 0);
    }

    // ------------------------------------------------------------------ node queries

    private long tipSlot() {
        return node.tipSlot();
    }

    private long epochOf(long slot) {
        return slot / EPOCH_LENGTH;
    }

    private long slotOf(String txHash) throws Exception {
        return node.txSlot(txHash);
    }

    private void awaitConfirmed(List<String> txHashes, long timeoutMillis) throws InterruptedException {
        await("confirmation of " + txHashes.size() + " transactions", timeoutMillis,
                () -> txHashes.stream().allMatch(node::confirmed));
    }

    private void await(String what, long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                note("TIMEOUT waiting for " + what);
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(250);
        }
    }

    private void note(String line) {
        String stamped = System.currentTimeMillis() + " " + line;
        log.add(stamped);
        out.println("WORKLOAD " + stamped);
    }
}
