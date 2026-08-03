package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoZkClientTest {

    @Test
    void reportsAvailabilityAndVerificationStagesWithoutGuessing() {
        Fixture fixture = fixture();
        MutableSource source = new MutableSource(fixture);
        EutxoZkClient client = new EutxoZkClient(
                source, (proof, key) -> source.proofValid);

        source.statementPresent = false;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.NOT_FOUND);
        source.statementPresent = true;
        source.batchPresent = false;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.WAITING_FOR_DATA);
        source.batchPresent = true;
        source.proofPresent = false;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.WAITING_FOR_PROOF);
        source.proofPresent = true;
        source.keyPresent = false;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.WAITING_FOR_KEY);
        source.keyPresent = true;
        source.proofValid = false;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.INVALID);
        source.proofValid = true;
        assertThat(client.status(fixture.statement.digestHex()).state())
                .isEqualTo(EutxoZkClient.State.VERIFIED);
    }

    @Test
    void availabilityMonitorRejectsMissingCorruptAndExpiredData() {
        Fixture fixture = fixture();
        EutxoDataAvailabilityMonitor monitor =
                new EutxoDataAvailabilityMonitor();

        assertThat(monitor.assess(
                fixture.statement, fixture.batch.canonicalBytes(),
                100, 110, 20).state())
                .isEqualTo(EutxoDataAvailabilityMonitor.State.AVAILABLE);
        assertThat(monitor.assess(
                fixture.statement, null,
                100, 110, 20).state())
                .isEqualTo(EutxoDataAvailabilityMonitor.State.MISSING);
        assertThat(monitor.assess(
                fixture.statement, new byte[]{1},
                100, 110, 20).state())
                .isEqualTo(EutxoDataAvailabilityMonitor.State.CORRUPT);
        assertThat(monitor.assess(
                fixture.statement, fixture.batch.canonicalBytes(),
                100, 121, 20).state())
                .isEqualTo(
                        EutxoDataAvailabilityMonitor.State
                                .OUTSIDE_DECLARED_HORIZON);
    }

    @Test
    void exitPlannerIsPermissionlessOnlyAfterProofVerification() {
        EutxoExitRecoveryPlanner planner =
                new EutxoExitRecoveryPlanner();
        assertThat(planner.plan(
                new EutxoZkClient.Status(
                        EutxoZkClient.State.WAITING_FOR_PROOF,
                        "", ""),
                false, false))
                .isEqualTo(new EutxoExitRecoveryPlanner.Plan(
                        EutxoExitRecoveryPlanner.Action.WAIT_FOR_PROOF,
                        false));
        assertThat(planner.plan(
                new EutxoZkClient.Status(
                        EutxoZkClient.State.VERIFIED,
                        "ab".repeat(32), ""),
                false, false))
                .isEqualTo(new EutxoExitRecoveryPlanner.Plan(
                        EutxoExitRecoveryPlanner.Action
                                .RELAY_VALIDITY_ROOT,
                        true));
        assertThat(planner.plan(
                new EutxoZkClient.Status(
                        EutxoZkClient.State.VERIFIED,
                        "ab".repeat(32), ""),
                true, false).action())
                .isEqualTo(
                        EutxoExitRecoveryPlanner.Action.RELAY_WITHDRAWAL);
    }

    private static Fixture fixture() {
        var payment = new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(4));
        var batch = new EutxoZkBatchData(List.of(payment));
        var inputs = new EutxoZkSettlementPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE,
                BigInteger.valueOf(5), batch.commitmentScalar(),
                BigInteger.valueOf(7));
        var statement = new EutxoZkStatement(
                "client-chain", 8, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, batch.commitment());
        var key = new EutxoZkVerificationKey(
                statement.profile().id(), statement.profile().circuitId(),
                new byte[48], new byte[96], new byte[96], new byte[96],
                Collections.nCopies(9, new byte[48]));
        var proof = new EutxoZkProofArtifact(
                statement.digestHex(), key.digestHex(), "client-test",
                statement, new byte[48], new byte[96], new byte[48], 1);
        return new Fixture(statement, batch, key, proof);
    }

    private record Fixture(
            EutxoZkStatement statement,
            EutxoZkBatchData batch,
            EutxoZkVerificationKey key,
            EutxoZkProofArtifact proof
    ) {
    }

    private static final class MutableSource implements EutxoZkDataSource {
        private final Fixture fixture;
        private boolean statementPresent = true;
        private boolean batchPresent = true;
        private boolean proofPresent = true;
        private boolean keyPresent = true;
        private boolean proofValid = true;

        private MutableSource(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public Optional<EutxoZkStatement> statement(String ignored) {
            return statementPresent
                    ? Optional.of(fixture.statement) : Optional.empty();
        }

        @Override
        public Optional<EutxoZkBatchData> batchData(String ignored) {
            return batchPresent ? Optional.of(fixture.batch) : Optional.empty();
        }

        @Override
        public Optional<EutxoZkProofArtifact> proof(String ignored) {
            return proofPresent ? Optional.of(fixture.proof) : Optional.empty();
        }

        @Override
        public Optional<EutxoZkVerificationKey> verificationKey(String ignored) {
            return keyPresent ? Optional.of(fixture.key) : Optional.empty();
        }
    }
}
