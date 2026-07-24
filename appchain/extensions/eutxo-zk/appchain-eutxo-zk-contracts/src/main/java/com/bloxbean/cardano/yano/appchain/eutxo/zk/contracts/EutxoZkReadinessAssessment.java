package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fail-closed readiness decision for the restricted EUTxO validity product.
 *
 * <p>Automated tests may populate engineering gates. Gates that require an
 * independent party or accountable production owner require external
 * evidence and cannot be self-certified by the build.</p>
 */
public final class EutxoZkReadinessAssessment {
    private static final Pattern DIGEST =
            Pattern.compile("[0-9a-f]{64}");
    private final Map<Gate, Evidence> evidence;

    public EutxoZkReadinessAssessment(Map<Gate, Evidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        EnumMap<Gate, Evidence> copied = new EnumMap<>(Gate.class);
        copied.putAll(evidence);
        this.evidence = Map.copyOf(copied);
    }

    public boolean productionFundsReady() {
        return missingProductionGates().isEmpty();
    }

    public boolean rollupLabelReady() {
        return productionFundsReady()
                && passed(Gate.EXTERNAL_RECONSTRUCTION)
                && passed(Gate.L1_DATA_RETENTION);
    }

    public List<Gate> missingProductionGates() {
        return Gate.valuesForProduction().stream()
                .filter(gate -> !passed(gate))
                .toList();
    }

    public List<Gate> missingRollupGates() {
        return java.util.Arrays.stream(Gate.values())
                .filter(gate -> !passed(gate))
                .toList();
    }

    public Map<Gate, Evidence> evidence() {
        return evidence;
    }

    private boolean passed(Gate gate) {
        Evidence item = evidence.get(gate);
        return item != null
                && item.status() == Status.PASSED
                && (!gate.externalEvidenceRequired()
                || item.externalEvidence());
    }

    public enum Status {
        PASSED,
        FAILED,
        MISSING
    }

    public enum Gate {
        CIRCUIT_AUDIT(true),
        CRYPTOGRAPHIC_INTEGRATION_AUDIT(true),
        JULC_VALIDATOR_AUDIT(true),
        BRIDGE_CUSTODY_AUDIT(true),
        PRODUCTION_MPC_CEREMONY(true),
        CEREMONY_TRANSCRIPT_VERIFICATION(true),
        RELEASE_REPRODUCIBILITY(false),
        CIRCUIT_FUZZ_AND_DIFFERENTIAL(false),
        REDUNDANT_PROVER_FAILOVER(false),
        MAINNET_PARAMETER_DRY_RUN(false),
        EXTERNAL_RECONSTRUCTION(true),
        L1_DATA_RETENTION(true),
        PRODUCTION_FUNDS_DECISION(true);

        private final boolean externalEvidenceRequired;

        Gate(boolean externalEvidenceRequired) {
            this.externalEvidenceRequired = externalEvidenceRequired;
        }

        public boolean externalEvidenceRequired() {
            return externalEvidenceRequired;
        }

        static List<Gate> valuesForProduction() {
            return java.util.Arrays.stream(values())
                    .filter(gate -> gate != EXTERNAL_RECONSTRUCTION
                            && gate != L1_DATA_RETENTION)
                    .toList();
        }
    }

    public record Evidence(
            Status status,
            String evidenceDigest,
            String owner,
            boolean externalEvidence
    ) {
        public Evidence {
            Objects.requireNonNull(status, "status");
            if (status == Status.PASSED) {
                if (evidenceDigest == null
                        || !DIGEST.matcher(evidenceDigest).matches()) {
                    throw new IllegalArgumentException(
                            "passed gate requires an evidence digest");
                }
                if (owner == null || owner.isBlank()) {
                    throw new IllegalArgumentException(
                            "passed gate requires an accountable owner");
                }
            }
        }
    }
}
