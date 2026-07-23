package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoFederatedRoot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Selects the single current root-thread output and rejects proofs made
 * against a superseded root. A relay can then fetch a fresh proof and retry.
 */
public final class FederatedRootSelector {
    private FederatedRootSelector() {
    }

    public static Candidate selectCurrent(
            List<Candidate> candidates,
            EutxoProofWithdrawal withdrawal
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(withdrawal, "withdrawal");
        List<Candidate> chainCandidates = candidates.stream()
                .filter(candidate -> Arrays.equals(
                        candidate.root().chainId().getBytes(
                                StandardCharsets.UTF_8),
                        withdrawal.commitment().chainId()))
                .toList();
        Candidate current = chainCandidates.stream()
                .max(Comparator
                        .comparingLong((Candidate value) ->
                                value.root().bridgeEpoch())
                        .thenComparingLong(value ->
                                value.root().generation())
                        .thenComparingLong(value -> value.root().height()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no accepted federated root is available for the claim chain"));
        long currentCount = chainCandidates.stream()
                .filter(candidate -> sameIdentity(
                        candidate.root(), current.root()))
                .count();
        if (currentCount != 1) {
            throw new IllegalArgumentException(
                    "federated root state is ambiguous");
        }
        if (!current.root().accepts(withdrawal)) {
            throw new IllegalArgumentException(
                    "withdrawal proof is not fixed to the current accepted root");
        }
        return current;
    }

    private static boolean sameIdentity(
            EutxoFederatedRoot left,
            EutxoFederatedRoot right
    ) {
        return left.bridgeEpoch() == right.bridgeEpoch()
                && left.generation() == right.generation()
                && left.height() == right.height();
    }

    public record Candidate(
            EutxoOutpoint outpoint,
            EutxoFederatedRoot root
    ) {
        public Candidate {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(root, "root");
        }
    }
}
