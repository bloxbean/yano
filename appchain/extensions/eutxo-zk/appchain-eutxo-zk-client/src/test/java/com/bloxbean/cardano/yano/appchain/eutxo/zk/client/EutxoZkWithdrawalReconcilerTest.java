package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZkWithdrawalReconcilerTest {

    @Test
    void tracksProofSubmissionStabilityAndRollbackWithoutSkipping() {
        EutxoZkWithdrawalReconciler reconciler =
                new EutxoZkWithdrawalReconciler();
        var finalized = reconciler.proofFinalized(proof(30), 0);
        assertThat(finalized.stage()).isEqualTo(
                EutxoZkWithdrawalReconciler.Stage.PROOF_FINALIZED);
        assertThat(finalized.lovelace()).isEqualTo(30);

        String tx = "11".repeat(32);
        String block = "22".repeat(32);
        assertThat(reconciler.submitted(finalized.key(), tx).stage())
                .isEqualTo(EutxoZkWithdrawalReconciler.Stage.L1_SUBMITTED);
        assertThat(reconciler.stable(
                finalized.key(), tx, block).stage())
                .isEqualTo(EutxoZkWithdrawalReconciler.Stage.L1_STABLE);
        assertThat(reconciler.rolledBack(finalized.key()).stage())
                .isEqualTo(EutxoZkWithdrawalReconciler.Stage.L1_SUBMITTED);
        assertThatThrownBy(() -> reconciler.stable(
                finalized.key(), "33".repeat(32), block))
                .isInstanceOf(IllegalStateException.class);
    }

    private static EutxoZkProofArtifact proof(long withdrawal) {
        var inputs = new EutxoZkSettlementPublicInputs(
                BigInteger.ONE,
                BigInteger.TWO,
                BigInteger.valueOf(3),
                BigInteger.valueOf(4),
                BigInteger.ONE,
                BigInteger.valueOf(5),
                BigInteger.valueOf(6),
                BigInteger.valueOf(withdrawal));
        var statement = new EutxoZkStatement(
                "payments", 7, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, new byte[32]);
        String keyDigest = "44".repeat(32);
        return new EutxoZkProofArtifact(
                statement.digestHex(), keyDigest, "reconciler-test",
                statement, new byte[48], new byte[96], new byte[48], 1);
    }
}
