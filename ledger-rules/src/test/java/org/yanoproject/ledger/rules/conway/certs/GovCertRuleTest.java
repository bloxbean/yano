package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.ada;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.balanced;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.run;
import static org.yanoproject.ledger.rules.conway.certs.CertTestSupport.spec;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.credential;
import static org.yanoproject.ledger.rules.fixtures.tx.MutationWorld.credentialKey;

/**
 * {@code GOVCERT} (Conway/Rules/GovCert.hs:180-276). World: {@code dev-77} a DRep (deposit 500 ADA) and an elected
 * committee member without a hot key; {@code dev-bb} an elected member that resigned; {@code dev-42} neither.
 */
class GovCertRuleTest {

    @Test
    @Covers("GOVCERT.ConwayDRepAlreadyRegistered")
    void aRegisteredDRepCannotRegisterAgain() {
        var again = balanced(spec(new RegDRepCert(credential(TestKey.DEV_77), ada(500), null), TestKey.DEV_77),
                ada(-500));
        assertThat(run(again)).containsExactly("GOVCERT.ConwayDRepAlreadyRegistered");
        var fresh = balanced(spec(new RegDRepCert(credential(TestKey.DEV_42), ada(500), null)), ada(-500));
        assertThat(run(fresh)).containsExactly("Valid");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepIncorrectDeposit")
    void theDRepDepositIsPpDRepDeposit() {
        // Value conservation charges ppDRepDeposit (conwayDRepDepositsTxCerts), not the stated amount.
        var wrong = balanced(spec(new RegDRepCert(credential(TestKey.DEV_42), ada(400), null)), ada(-500));
        assertThat(run(wrong)).containsExactly("GOVCERT.ConwayDRepIncorrectDeposit");
        // Both, in execution order.
        var both = balanced(spec(new RegDRepCert(credential(TestKey.DEV_77), ada(400), null), TestKey.DEV_77),
                ada(-500));
        assertThat(run(both)).containsExactly("GOVCERT.ConwayDRepAlreadyRegistered",
                "GOVCERT.ConwayDRepIncorrectDeposit");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepNotRegistered")
    void onlyARegisteredDRepDeregistersOrUpdates() {
        assertThat(run(spec(new UpdateDRepCert(credential(TestKey.DEV_42), null))))
                .containsExactly("GOVCERT.ConwayDRepNotRegistered");
        // The refund is the certificate's (conwayDRepRefundsTxCerts); an unregistered DRep's refund is not judged.
        var unregister = balanced(spec(new UnregDRepCert(credential(TestKey.DEV_42), ada(400))), ada(400));
        assertThat(run(unregister)).containsExactly("GOVCERT.ConwayDRepNotRegistered");
        assertThat(run(spec(new UpdateDRepCert(credential(TestKey.DEV_77), null), TestKey.DEV_77)))
                .containsExactly("Valid");
    }

    @Test
    @Covers("GOVCERT.ConwayDRepIncorrectRefund")
    void theDRepRefundIsTheRecordedDeposit() {
        var wrong = balanced(spec(new UnregDRepCert(credential(TestKey.DEV_77), ada(400)), TestKey.DEV_77), ada(400));
        assertThat(run(wrong)).containsExactly("GOVCERT.ConwayDRepIncorrectRefund");
        var right = balanced(spec(new UnregDRepCert(credential(TestKey.DEV_77), ada(500)), TestKey.DEV_77), ada(500));
        assertThat(run(right)).containsExactly("Valid");
    }

    @Test
    @Covers("GOVCERT.ConwayCommitteeHasPreviouslyResigned")
    void aResignedMemberCannotAuthorizeOrResignAgain() {
        assertThat(run(spec(new AuthCommitteeHotCert(credential(TestKey.DEV_BB), credential(TestKey.DEV_42)),
                TestKey.DEV_BB))).containsExactly("GOVCERT.ConwayCommitteeHasPreviouslyResigned");
        assertThat(run(spec(new ResignCommitteeColdCert(credential(TestKey.DEV_BB), null), TestKey.DEV_BB)))
                .containsExactly("GOVCERT.ConwayCommitteeHasPreviouslyResigned");
        // A resigned candidate that is neither elected nor proposed: both failures, in execution order.
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .committeeMember(new CommitteeMemberState(credentialKey(TestKey.DEV_42), null, true, null))
                .build();
        assertThat(run(spec(new AuthCommitteeHotCert(credential(TestKey.DEV_42), credential(TestKey.DEV_AA))), view,
                10)).containsExactly("GOVCERT.ConwayCommitteeHasPreviouslyResigned", "GOVCERT.ConwayCommitteeIsUnknown");
    }

    @Test
    @Covers("GOVCERT.ConwayCommitteeIsUnknown")
    void theColdCredentialIsAMemberOrAProposedMember() {
        assertThat(run(spec(new AuthCommitteeHotCert(credential(TestKey.DEV_42), credential(TestKey.DEV_AA)))))
                .containsExactly("GOVCERT.ConwayCommitteeIsUnknown");
        assertThat(run(spec(new ResignCommitteeColdCert(credential(TestKey.DEV_42), null))))
                .containsExactly("GOVCERT.ConwayCommitteeIsUnknown");
        // An elected member authorizes a hot key.
        assertThat(run(spec(new AuthCommitteeHotCert(credential(TestKey.DEV_77), credential(TestKey.DEV_AA)),
                TestKey.DEV_77))).containsExactly("Valid");
        // A candidate of a pending UpdateCommittee proposal may authorize and resign before it is elected.
        InMemoryLedgerView proposed = MutationWorld.builder(MutationWorld.protocolParams())
                .committeeCandidate(credentialKey(TestKey.DEV_42))
                .build();
        assertThat(run(spec(new AuthCommitteeHotCert(credential(TestKey.DEV_42), credential(TestKey.DEV_AA))),
                proposed, 10)).containsExactly("Valid");
        assertThat(run(spec(List.of(new AuthCommitteeHotCert(credential(TestKey.DEV_42), credential(TestKey.DEV_AA)),
                new ResignCommitteeColdCert(credential(TestKey.DEV_42), null))), proposed, 10)).containsExactly("Valid");
    }
}
