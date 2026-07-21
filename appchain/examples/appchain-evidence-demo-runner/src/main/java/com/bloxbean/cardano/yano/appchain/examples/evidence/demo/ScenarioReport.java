package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Stable, credential-free machine report emitted by every scenario attempt. */
public record ScenarioReport(int schemaVersion,
                             String scenarioId,
                             String evidenceId,
                             String outcome,
                             String startedAt,
                             String finishedAt,
                             ChainSummary chain,
                             StorageSummary storage,
                             KafkaSummary kafka,
                             AnchorSummary anchor,
                             List<Check> checks,
                             String failureCode) {
    public static final int SCHEMA_VERSION = 1;

    public ScenarioReport {
        if (schemaVersion != SCHEMA_VERSION || scenarioId == null || scenarioId.isBlank()
                || evidenceId == null || evidenceId.isBlank()
                || !("PASS".equals(outcome) || "FAIL".equals(outcome))
                || startedAt == null || finishedAt == null
                || Instant.parse(finishedAt).isBefore(Instant.parse(startedAt))) {
            throw new IllegalArgumentException("invalid report envelope");
        }
        checks = List.copyOf(checks);
        if ("PASS".equals(outcome) != (failureCode == null)) {
            throw new IllegalArgumentException("invalid report result");
        }
    }

    public record ChainSummary(String chainId,
                               long committedHeight,
                               String stateRoot,
                               String businessStatus,
                               int membersVerified,
                               int finalityThreshold,
                               int stateProofsVerified,
                               int effectProofsVerified,
                               int finalityBundlesVerified) {
    }

    public record StorageSummary(String sha256,
                                 long size,
                                 String objectVersionFingerprint,
                                 String cid,
                                 boolean objectStateVerified,
                                 boolean ipfsPinVerified) {
    }

    public record KafkaSummary(String topic,
                               int partition,
                               long offset,
                               boolean eventVerified) {
    }

    public record AnchorSummary(boolean required,
                                boolean portableLinkageVerified,
                                List<String> portableTransactionHashes,
                                boolean portableTransactionsVisibleOnAllMembers,
                                boolean portableDatumCommitmentsVerified,
                                boolean memberObservationCovered,
                                long memberObservedAnchoredHeight,
                                String memberObservedTransactionHash,
                                boolean memberObservedTransactionVisibleOnAllMembers,
                                boolean memberObservedDatumCommitmentVerified) {
        public AnchorSummary {
            portableTransactionHashes = List.copyOf(portableTransactionHashes);
        }
    }

    public record Check(String name, String status) {
        public Check {
            Objects.requireNonNull(name, "name");
            if (!("PASS".equals(status) || "FAIL".equals(status)
                    || "NOT_EVALUATED".equals(status))) {
                throw new IllegalArgumentException("invalid check status");
            }
        }
    }
}
