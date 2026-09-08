package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure Phase-0 result-input classifier. Structural decoding always precedes
 * state lookup; canonical unknown/terminal rounds are stale no-ops; active
 * rounds must pass intrinsic verification. One instance represents one block.
 */
public final class ObservationResultAdmission {
    private final RoundLookup rounds;
    private final CertificateValidation validation;
    private final Map<RoundKey, byte[]> accepted = new HashMap<>();

    public ObservationResultAdmission(RoundLookup rounds, CertificateValidation validation) {
        this.rounds = Objects.requireNonNull(rounds, "rounds");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    public Verdict classify(byte[] body) {
        final ObservationCertificate certificate;
        try {
            certificate = ObservationCertificate.decode(body);
        } catch (IllegalArgumentException malformed) {
            throw new StructuralRejection("malformed or oversized ~obs/result/v1", malformed);
        }
        RoundKey key = new RoundKey(certificate.subscriptionId(), certificate.roundNumber());
        RoundState state = Objects.requireNonNull(rounds.state(
                certificate.subscriptionId(), certificate.roundNumber()), "round state");
        if (state != RoundState.ACTIVE) {
            return Verdict.STALE_NOOP;
        }
        if (!validation.verify(certificate)) {
            throw new StructuralRejection("invalid active-round observation certificate");
        }
        byte[] previous = accepted.putIfAbsent(key, certificate.resultId());
        if (previous == null) {
            return Verdict.ACCEPTED;
        }
        if (Arrays.equals(previous, certificate.resultId())) {
            return Verdict.DUPLICATE_NOOP;
        }
        throw new StructuralRejection("conflicting valid results for one active observation round");
    }

    public enum Verdict {
        ACCEPTED,
        STALE_NOOP,
        DUPLICATE_NOOP
    }

    public enum RoundState {
        ACTIVE,
        TERMINAL,
        UNKNOWN
    }

    @FunctionalInterface
    public interface RoundLookup {
        RoundState state(byte[] subscriptionId, long roundNumber);
    }

    @FunctionalInterface
    public interface CertificateValidation {
        boolean verify(ObservationCertificate certificate);
    }

    public static final class StructuralRejection extends IllegalArgumentException {
        public StructuralRejection(String message) {
            super(message);
        }

        public StructuralRejection(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record RoundKey(byte[] subscriptionId, long roundNumber) {
        private RoundKey {
            subscriptionId = subscriptionId.clone();
        }

        @Override public boolean equals(Object other) {
            return other instanceof RoundKey key && roundNumber == key.roundNumber
                    && Arrays.equals(subscriptionId, key.subscriptionId);
        }

        @Override public int hashCode() {
            return 31 * Arrays.hashCode(subscriptionId) + Long.hashCode(roundNumber);
        }
    }
}
