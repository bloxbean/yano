package org.yanoproject.ledger.rules.shadow;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.view.Fixtures.ADDRESS;
import static org.yanoproject.ledger.rules.view.Fixtures.env;
import static org.yanoproject.ledger.rules.view.Fixtures.hash28;
import static org.yanoproject.ledger.rules.view.Fixtures.hash32;
import static org.yanoproject.ledger.rules.view.Fixtures.keyCred;
import static org.yanoproject.ledger.rules.view.Fixtures.poolRegistration;
import static org.yanoproject.ledger.rules.view.Fixtures.protocolParams;

/** ADR-056 §7: a recording view's reads survive a dump bundle round trip and replay exactly. */
class ShadowDumpBundleTest {

    private static final Outpoint IN = new Outpoint(hash32(0x01), 0);
    private static final Outpoint SPENT = new Outpoint(hash32(0x02), 1);
    private static final GovActionId ACTION = new GovActionId(hash32(0x03), 0);

    @Test
    void everyRecordedReadReplaysFromTheBundle(@TempDir Path dir) {
        byte[] inlineDatum = BigIntPlutusData.of(42).serializeToBytes();
        TransactionOutput output = TransactionOutput.builder().address(ADDRESS)
                .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build())
                .inlineDatum(BigIntPlutusData.of(42)).build();
        PoolState pool = new PoolState(new PoolId(hash28(0x10)), BigInteger.valueOf(500_000_000), hash32(0x11), 120L,
                poolRegistration(0x10, 0x11), null);
        ProposalState proposal = new ProposalState(ACTION, GovActionType.INFO_ACTION, new InfoAction(), null, 90, 96,
                BigInteger.valueOf(100_000_000_000L), "e0" + hash28(0x12), null);
        InMemoryLedgerView base = InMemoryLedgerView.builder()
                .utxo(new UtxoEntry(IN, output, inlineDatum))
                .account(new AccountState(keyCred(0x20), BigInteger.valueOf(2_000_000), BigInteger.TEN,
                        pool.id(), DRepTarget.ALWAYS_ABSTAIN))
                .pool(pool)
                .drep(new DRepState(keyCred(0x21), BigInteger.valueOf(500_000_000), 130))
                .committeeMember(new CommitteeMemberState(keyCred(0x22), keyCred(0x23), false, 140L))
                .proposal(proposal)
                .enactedRoots(new EnactedRoots(ACTION, null, null, null))
                .guardrailScriptHash(hash28(0x24))
                .dormantEpochs(3)
                .treasury(BigInteger.valueOf(123_456))
                .protocolParams(protocolParams())
                .build();
        RecordingLedgerView recording = new RecordingLedgerView(base);
        recording.utxo(IN);
        recording.utxo(SPENT);
        recording.account(keyCred(0x20));
        recording.account(keyCred(0x2f));
        recording.pool(pool.id());
        recording.poolByVrfKeyHash(hash32(0x11));
        recording.drep(keyCred(0x21));
        recording.committeeMemberByCold(keyCred(0x22));
        recording.committeeMembersByHot(keyCred(0x23));
        recording.committeeMembers();
        recording.committeeCandidates();
        recording.proposal(ACTION);
        recording.activeProposals();
        recording.enactedRoots();
        recording.guardrailScriptHash();
        recording.dormantEpochs();
        recording.treasury();
        recording.protocolParams();

        TxValidationOutcome admission = TxValidationOutcome.Invalid.of(
                new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO", LedgerFailure.Phase.PHASE_1, "gone"));
        TxValidationOutcome shadow = TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable("x"));
        ShadowDumpBundle bundle = new ShadowDumpBundle(hash32(0x55), new byte[]{(byte) 0x84, 1, 2, 3},
                TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.PEER, env(),
                RecordedOutcome.of("scalus", admission), RecordedOutcome.of("amaru", shadow), recording.reads());

        Path file = bundle.write(dir);
        assertThat(file.getFileName().toString()).isEqualTo(hash32(0x55) + "-amaru.json");
        ShadowDumpBundle read = ShadowDumpBundle.read(file);

        assertThat(read.txHash()).isEqualTo(bundle.txHash());
        assertThat(read.txCbor()).isEqualTo(bundle.txCbor());
        assertThat(read.env()).isEqualTo(env());
        assertThat(read.rule()).isEqualTo(TxValidationRequest.Rule.MEMPOOL);
        assertThat(read.origin()).isEqualTo(TxValidationRequest.Origin.PEER);
        assertThat(read.admission().verdict().label()).isEqualTo("UTXO.BadInputsUTxO");
        assertThat(read.shadow().verdict().label()).isEqualTo("ENGINE.LedgerStateUnavailable");
        assertThat(read.reads()).hasSize(recording.reads().size());

        LedgerView replay = read.replayView();
        UtxoEntry entry = replay.utxo(IN).orElseThrowUnavailable().orElseThrow();
        assertThat(entry.inlineDatumCbor()).isEqualTo(inlineDatum);
        assertThat(entry.output().getValue().getCoin()).isEqualTo(BigInteger.valueOf(5_000_000));
        assertThat(replay.utxo(SPENT).isAbsent()).isTrue();
        assertThat(replay.account(keyCred(0x20))).isEqualTo(base.account(keyCred(0x20)));
        assertThat(replay.account(keyCred(0x2f)).isAbsent()).isTrue();
        PoolState replayedPool = replay.pool(pool.id()).orElseThrowUnavailable().orElseThrow();
        assertThat(replayedPool.retiringEpoch()).isEqualTo(120L);
        assertThat(replayedPool.params().getCost()).isEqualTo(pool.params().getCost());
        assertThat(replay.poolByVrfKeyHash(hash32(0x11))).isEqualTo(base.poolByVrfKeyHash(hash32(0x11)));
        assertThat(replay.drep(keyCred(0x21))).isEqualTo(base.drep(keyCred(0x21)));
        assertThat(replay.committeeMemberByCold(keyCred(0x22))).isEqualTo(base.committeeMemberByCold(keyCred(0x22)));
        assertThat(replay.committeeMembersByHot(keyCred(0x23))).isEqualTo(base.committeeMembersByHot(keyCred(0x23)));
        assertThat(replay.committeeMembers()).isEqualTo(base.committeeMembers());
        assertThat(replay.committeeCandidates()).isEqualTo(Lookup.present(Set.of()));
        ProposalState replayedProposal = replay.proposal(ACTION).orElseThrowUnavailable().orElseThrow();
        assertThat(replayedProposal.type()).isEqualTo(GovActionType.INFO_ACTION);
        assertThat(replayedProposal.expiresAfterEpoch()).isEqualTo(96);
        assertThat(replay.activeProposals().orElseThrowUnavailable().orElseThrow()).hasSize(1);
        assertThat(replay.enactedRoots()).isEqualTo(base.enactedRoots());
        assertThat(replay.guardrailScriptHash()).isEqualTo(base.guardrailScriptHash());
        assertThat(replay.dormantEpochs()).isEqualTo(base.dormantEpochs());
        assertThat(replay.treasury()).isEqualTo(base.treasury());
        assertThat(replay.protocolParams().orElseThrowUnavailable().orElseThrow().getKeyDeposit())
                .isEqualTo(protocolParams().getKeyDeposit());
        // Anything not recorded fails closed.
        assertThat(replay.drep(keyCred(0x30)).isUnavailable()).isTrue();
        assertThat(read.replayRequest().view()).isInstanceOf(ReplayLedgerView.class);
    }

    @Test
    void verdictsCompareOnValidityAndTheFirstRuleAndConstructor() {
        Verdict valid = new Verdict(true, null);
        Verdict bad1 = new Verdict(false, new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO",
                LedgerFailure.Phase.PHASE_1, "a"));
        Verdict bad1OtherDetail = new Verdict(false, new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO",
                LedgerFailure.Phase.PHASE_1, "b"));
        Verdict bad2 = new Verdict(false, new LedgerFailure(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG",
                LedgerFailure.Phase.PHASE_1, ""));

        assertThat(Verdict.compare(valid, valid)).isEmpty();
        assertThat(Verdict.compare(bad1, bad1OtherDetail)).isEmpty();
        assertThat(Verdict.compare(valid, bad2)).hasValueSatisfying(d -> {
            assertThat(d.kind()).isEqualTo(Verdict.Disagreement.Kind.VERDICT);
            assertThat(d.ruleLabel()).isEqualTo("DELEG");
        });
        assertThat(Verdict.compare(bad1, bad2)).hasValueSatisfying(d -> {
            assertThat(d.kind()).isEqualTo(Verdict.Disagreement.Kind.FAILURE);
            assertThat(d.ruleLabel()).isEqualTo("UTXO");
        });
        assertThat(RecordedOutcome.of("x", TxValidationOutcome.Invalid.of(LedgerFailure.eraNotSupported("pv"))).valid())
                .isFalse();
        assertThat(List.of(valid.label(), bad2.label())).containsExactly("VALID", "DELEG.StakeKeyNotRegisteredDELEG");
    }
}
