package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Deterministic node-local failover across interchangeable proof backends.
 *
 * <p>A candidate is eligible only when it is healthy, below its configured
 * capacity, and serves the pinned verification key. Failover never mutates
 * consensus state.</p>
 */
public final class EutxoProverFailoverCoordinator {
    private final String verificationKeyDigest;
    private final List<Candidate> candidates;

    public EutxoProverFailoverCoordinator(
            String verificationKeyDigest,
            List<Candidate> candidates
    ) {
        if (verificationKeyDigest == null
                || !verificationKeyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "invalid verification-key digest");
        }
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one prover candidate is required");
        }
        this.verificationKeyDigest = verificationKeyDigest;
        this.candidates = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::priority)
                        .thenComparing(Candidate::id))
                .toList();
    }

    public Result prove(
            EutxoZkStatement statement,
            EutxoKeyPaymentBatch witness
    ) {
        StringBuilder failures = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (!candidate.healthy().getAsBoolean()
                    || candidate.inFlight().getAsInt()
                    >= candidate.capacity()
                    || !verificationKeyDigest.equals(candidate.backend()
                    .verificationKey().digestHex())) {
                continue;
            }
            try {
                EutxoZkProofArtifact proof = candidate.backend().prove(
                        statement, witness, candidate.id());
                if (!verificationKeyDigest.equals(
                        proof.verificationKeyDigest())
                        || !candidate.backend().verify(proof)) {
                    throw new IllegalStateException(
                            "candidate produced an invalid proof");
                }
                return new Result(candidate.id(), proof);
            } catch (RuntimeException failure) {
                if (!failures.isEmpty()) {
                    failures.append("; ");
                }
                failures.append(candidate.id()).append(": ")
                        .append(failure.getMessage());
            }
        }
        throw new IllegalStateException(
                failures.isEmpty()
                        ? "no healthy prover with matching key and capacity"
                        : "all eligible provers failed: " + failures);
    }

    public record Candidate(
            String id,
            int priority,
            int capacity,
            IntSupplier inFlight,
            BooleanSupplier healthy,
            EutxoProofBackend backend
    ) {
        public Candidate {
            if (id == null || id.isBlank() || id.length() > 128
                    || priority < 0 || capacity < 1) {
                throw new IllegalArgumentException(
                        "invalid prover candidate");
            }
            Objects.requireNonNull(inFlight, "inFlight");
            Objects.requireNonNull(healthy, "healthy");
            Objects.requireNonNull(backend, "backend");
        }
    }

    public record Result(
            String proverId,
            EutxoZkProofArtifact proof
    ) {
    }
}
