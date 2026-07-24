package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZkContractsTest {

    @Test
    void releaseManifestIsOrderIndependentAndReadinessFailsClosed() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("validator", "11".repeat(32));
        first.put("circuit", "22".repeat(32));
        Map<String, String> second = new LinkedHashMap<>();
        second.put("circuit", "22".repeat(32));
        second.put("validator", "11".repeat(32));

        var left = new EutxoZkReleaseManifest(
                "phase-c-v1", "0.1.0-pre10", "0.1.0-pre14",
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(), first);
        var right = new EutxoZkReleaseManifest(
                "phase-c-v1", "0.1.0-pre10", "0.1.0-pre14",
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(), second);
        assertThat(left.digestHex()).isEqualTo(right.digestHex());

        EnumMap<EutxoZkReadinessAssessment.Gate,
                EutxoZkReadinessAssessment.Evidence> evidence =
                new EnumMap<>(EutxoZkReadinessAssessment.Gate.class);
        for (var gate : EutxoZkReadinessAssessment.Gate.values()) {
            evidence.put(gate, new EutxoZkReadinessAssessment.Evidence(
                    EutxoZkReadinessAssessment.Status.PASSED,
                    "33".repeat(32), "test-owner", false));
        }
        var selfCertified = new EutxoZkReadinessAssessment(evidence);
        assertThat(selfCertified.productionFundsReady()).isFalse();
        assertThat(selfCertified.missingProductionGates())
                .contains(EutxoZkReadinessAssessment.Gate.CIRCUIT_AUDIT)
                .doesNotContain(
                        EutxoZkReadinessAssessment.Gate.RELEASE_REPRODUCIBILITY);

        for (var gate : EutxoZkReadinessAssessment.Gate.values()) {
            evidence.put(gate, new EutxoZkReadinessAssessment.Evidence(
                    EutxoZkReadinessAssessment.Status.PASSED,
                    "44".repeat(32), "accountable-owner", true));
        }
        var approved = new EutxoZkReadinessAssessment(evidence);
        assertThat(approved.productionFundsReady()).isTrue();
        assertThat(approved.rollupLabelReady()).isTrue();
    }

    @Test
    void budgetAssessmentUsesPinnedSnapshotAndSafetyMargin() {
        var envelope = new EutxoZkBudgetAssessment.Envelope(
                "mainnet", 600, "55".repeat(32),
                10_000_000_000L, 14_000_000L, 16_384, 1_000);
        assertThat(EutxoZkBudgetAssessment.assess(
                envelope,
                new EutxoZkBudgetAssessment.Measurement(
                        4_239_437_341L, 1_219_308L, 12_000))
                .withinEnvelope()).isTrue();
        assertThat(EutxoZkBudgetAssessment.assess(
                envelope,
                new EutxoZkBudgetAssessment.Measurement(
                        9_500_000_000L, 1_219_308L, 12_000))
                .failures()).containsExactly("cpu");
    }

    @Test
    void witnessCodecIsCanonicalAndBounded() {
        EutxoValidityWitness witness = new EutxoValidityWitness(
                "zeroj-poseidon-v1",
                bytes(1),
                bytes(2),
                bytes(3),
                "ab".repeat(32),
                42,
                3);

        assertThat(EutxoValidityWitness.decode(witness.encode())).isEqualTo(witness);
        assertThat(EutxoValidityWitness.decode(witness.encode()).encode())
                .isEqualTo(witness.encode());
        assertThatThrownBy(() -> EutxoValidityWitness.decode(
                java.util.Arrays.copyOf(witness.encode(), witness.encode().length - 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void z0PublicInputOrderIsExplicit() {
        assertThat(EutxoZkProfile.Z0_SINGLE_KEY_PAYMENT.digestHex())
                .isEqualTo("f2478e0573535b9c0de7e66d66a7e671"
                        + "565999c6c36096d1d7d1413fa2b0e406");
        assertThat(EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.digestHex())
                .isEqualTo("d495d0ad6a1d7babd00ba53de5bd9019"
                        + "224ac81fb3c68f33dd902e5e5e9282b3");
        assertThat(EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex())
                .isEqualTo("cfe1767761cbe05c7e2b82f951222fbb"
                        + "9df34afa5eb1f39fb8a5c1cc2af87d45");
        EutxoZkPublicInputs inputs = new EutxoZkPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE);
        assertThat(inputs.ordered()).containsExactly(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE);
        assertThatThrownBy(() -> new EutxoZkPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scalableBatchProfilesHaveDistinctImmutableSecurityIdentities() {
        assertThat(EutxoZkBatchProfile.values())
                .extracting(EutxoZkBatchProfile::maximumTransactions)
                .containsExactly(16, 32, 64);
        assertThat(EutxoZkBatchProfile.values())
                .extracting(EutxoZkBatchProfile::digest)
                .doesNotHaveDuplicates();
        assertThat(EutxoZkBatchProfile.CARDANO_PAYMENT_B16.status())
                .isEqualTo(EutxoZkBatchProfile.Status
                        .MEASURED_DEVELOPMENT_DEFAULT);
        assertThat(EutxoZkBatchProfile.CARDANO_PAYMENT_B32.status())
                .isEqualTo(EutxoZkBatchProfile.Status.UNMEASURED_CANDIDATE);
        assertThatThrownBy(() -> new EutxoZkBatchMeasurement(
                EutxoZkBatchProfile.CARDANO_PAYMENT_B32.digest(),
                "not-run",
                1, 0, 0, 0, 0, 0, 0, 0,
                EutxoZkBatchMeasurement.Gate.NOT_EXERCISED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invented");
    }

    @Test
    void batchContractRejectsInflationAndMoreThanFourPayments() {
        var payment = new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(4));
        assertThat(new EutxoKeyPaymentBatch(
                java.util.List.of(payment), BigInteger.valueOf(9)).payments())
                .containsExactly(payment);
        assertThatThrownBy(() -> new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conserve");
        assertThatThrownBy(() -> new EutxoKeyPaymentBatch(
                java.util.Collections.nCopies(5, payment), BigInteger.valueOf(9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void z2ArtifactsRoundTripCanonicallyAndRejectMutation() {
        var payment = new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(4));
        var inputs = new EutxoZkSettlementPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE,
                BigInteger.valueOf(5), BigInteger.valueOf(6),
                BigInteger.valueOf(7));
        var batch = new EutxoZkBatchData(List.of(payment));
        var statement = new EutxoZkStatement(
                "payments", 12, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, batch.commitment());
        var key = new EutxoZkVerificationKey(
                statement.profile().id(),
                statement.profile().circuitId(),
                new byte[48], new byte[96], new byte[96], new byte[96],
                java.util.Collections.nCopies(9, new byte[48]));
        var proof = new EutxoZkProofArtifact(
                statement.digestHex(), key.digestHex(), "prover-a",
                statement, new byte[48], new byte[96], new byte[48], 9);

        assertThat(EutxoZkBatchData.decode(batch.canonicalBytes())
                .canonicalBytes()).isEqualTo(batch.canonicalBytes());
        assertThat(batch.canonicalBytes())
                .hasSize(EutxoZkBatchData.CANONICAL_BYTES);
        byte[] nonCanonicalPadding = batch.canonicalBytes();
        nonCanonicalPadding[nonCanonicalPadding.length - 1] = 1;
        assertThatThrownBy(() ->
                EutxoZkBatchData.decode(nonCanonicalPadding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("padding");
        assertThat(EutxoZkStatement.decode(statement.canonicalBytes())
                .canonicalBytes()).isEqualTo(statement.canonicalBytes());
        assertThat(EutxoZkVerificationKey.decode(key.canonicalBytes())
                .canonicalBytes()).isEqualTo(key.canonicalBytes());
        assertThat(EutxoZkProofArtifact.decode(proof.canonicalBytes())
                .canonicalBytes()).isEqualTo(proof.canonicalBytes());

        byte[] mutated = proof.canonicalBytes();
        mutated[mutated.length - 1] ^= 1;
        EutxoZkProofArtifact decoded = EutxoZkProofArtifact.decode(mutated);
        assertThat(decoded.digestHex()).isNotEqualTo(proof.digestHex());
        assertThatThrownBy(() -> EutxoZkProofArtifact.decode(
                Arrays.copyOf(mutated, mutated.length - 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) value;
        return bytes;
    }
}
