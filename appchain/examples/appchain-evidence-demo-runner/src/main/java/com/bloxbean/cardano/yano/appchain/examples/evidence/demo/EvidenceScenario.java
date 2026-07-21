package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleasePrerequisiteCommandsV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.client.VerifiedEvidence;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** One reusable Emit → Execute → Result → continuation → Emit scenario. */
final class EvidenceScenario {
    static final int MAX_SAMPLE_BYTES = 16_777_216;

    private final DemoEnvironment environment;
    private final ReportStore reports;
    private final Clock clock;

    EvidenceScenario(DemoEnvironment environment, ReportStore reports) {
        this(environment, reports, Clock.systemUTC());
    }

    EvidenceScenario(DemoEnvironment environment, ReportStore reports, Clock clock) {
        this.environment = environment;
        this.reports = reports;
        this.clock = clock;
    }

    ScenarioReport run() {
        Instant started = clock.instant();
        String scenarioId = scenarioId(environment.config.evidenceId(), started);
        List<ScenarioReport.Check> checks = new ArrayList<>();
        try {
            ScenarioReport report = execute(scenarioId, started, checks);
            reports.write(report);
            return report;
        } catch (DemoException failure) {
            checks.add(new ScenarioReport.Check("SCENARIO", "FAIL"));
            checks.add(new ScenarioReport.Check(
                    "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));
            ScenarioReport report = new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                    scenarioId, environment.config.evidenceId(), "FAIL",
                    started.toString(), clock.instant().toString(), null, null, null, null,
                    checks, failure.error().name());
            reports.write(report);
            throw failure;
        } catch (RuntimeException failure) {
            checks.add(new ScenarioReport.Check("SCENARIO", "FAIL"));
            checks.add(new ScenarioReport.Check(
                    "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));
            ScenarioReport report = new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                    scenarioId, environment.config.evidenceId(), "FAIL",
                    started.toString(), clock.instant().toString(), null, null, null, null,
                    checks, DemoError.INTERNAL_ERROR.name());
            reports.write(report);
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    private ScenarioReport execute(String scenarioId, Instant started,
                                   List<ScenarioReport.Check> checks) {
        DemoConfig config = environment.config;
        new DemoInitializer(environment).initialize();
        checks.add(pass("SERVICES_INITIALIZED"));

        byte[] document = readSample(config.sampleFile());
        byte[] digest = Digests.sha256(document);
        String relativeKey = config.evidenceId() + "/inspection-certificate.bin";
        environment.s3.stage(relativeKey, document, digest);
        CanonicalCid cid = environment.kubo.addUnpinned(document);
        checks.add(pass("DOCUMENT_STAGED_AND_CID_COMPUTED"));

        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                config.s3().target(), relativeKey, relativeKey, DigestAlgorithm.SHA_256,
                digest, document.length, "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1(
                config.ipfs().target(), cid, true, config.ipfs().replicationPolicy());
        byte[] objectDestination = ObjectDestinationFingerprint.compute(
                config.s3().targetId(), config.s3().destinationBucket(),
                config.s3().destinationPrefix(), relativeKey,
                config.s3().encryptionPolicyId(), config.s3().retentionPolicyId()).bytes();
        byte[] ipfsTarget = IpfsTargetFingerprint.compute(config.ipfs().targetId()).bytes();
        byte[] kafkaDestination = KafkaDestinationFingerprint.compute(
                config.kafka().targetId(), config.kafka().physicalTopic()).bytes();
        SubmitEvidenceCommandV1 submit = new SubmitEvidenceCommandV1(
                config.evidenceId(), 1, object.encode(), objectDestination,
                ipfs.encode(), ipfsTarget, config.kafka().target(),
                config.kafka().topicAlias(), kafkaDestination);

        YanoAuditClient primary = environment.yano.getFirst();
        // Open before submission: an activated result continuation may publish
        // Kafka immediately after the second storage result is incorporated.
        // Opening only after STORAGE_READY can miss that acknowledged record.
        try (KafkaDemoClient.EventWindow window = environment.kafka.openEventWindow()) {
            String acceptedMessageId = submitInitialCommand(
                    primary, config, submit, digest, relativeKey);
            checks.add(pass("composite".equals(config.stateMachine())
                    ? "COMPOSITE_EVIDENCE_RELEASE_WORKFLOW"
                    : "DIRECT_EVIDENCE_SUBMISSION"));
            VerifiedEvidence storageReady = waitEvidence(primary,
                    status -> status == EvidenceStatus.STORAGE_READY
                            || status == EvidenceStatus.NOTIFICATION_PENDING
                            || status == EvidenceStatus.READY,
                    true);
            requireSubmittedState(storageReady.record(), submit,
                    config.kafka().target(), config.kafka().topicAlias());
            if (!acceptedMessageId.equals(Digests.hex(storageReady.record().submitMessageId()))) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            checks.add(pass("AUTHENTICATED_STORAGE_RESULTS"));

            ObjectPutReceiptV1 objectReceipt = ObjectPutReceiptV1.decode(
                    storageReady.record().objectTerminal().externalRef());
            IpfsPinReceiptV1 ipfsReceipt = IpfsPinReceiptV1.decode(
                    storageReady.record().ipfsTerminal().externalRef());
            if (!Arrays.equals(objectReceipt.destinationFingerprint(), objectDestination)
                    || objectReceipt.size() != document.length
                    || !Arrays.equals(objectReceipt.verifiedSha256(), digest)
                    || !Arrays.equals(ipfsReceipt.targetFingerprint(), ipfsTarget)
                    || !Arrays.equals(ipfsReceipt.cidFingerprint(),
                    IpfsCidFingerprint.compute(cid).bytes())) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            S3DemoStore.DestinationAudit objectAudit = environment.s3.verifyDestination(
                    relativeKey, objectReceipt);
            if (!environment.kubo.requiredPinPresent(cid, true)) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            environment.kubo.requireContent(cid, document);
            checks.add(pass("EXTERNAL_STORAGE_STATE"));

            if (storageReady.record().notificationEffect() == null) {
                try {
                    primary.evidence().notify(config.evidenceId(), 1);
                } catch (RuntimeException failure) {
                    throw new DemoException(DemoError.NOTIFICATION_FAILED);
                }
            }
            VerifiedEvidence ready = waitEvidence(primary,
                    status -> status == EvidenceStatus.READY, false);
            requireStableStorage(storageReady.record(), ready.record());

            // Freeze the terminal READY state and wait until all three L1
            // observers agree on an anchor covering it. Evidence fetched only
            // after this point is extended to the current, still-unspent
            // script-anchor head instead of relying on historical spent UTxOs.
            ClusterAgreement agreement = waitForAgreement(ready.record());
            YanoAuditClient.Status anchoredStatus = waitForAnchor(
                    agreement.committedHeight(), config.requireAnchor());

            byte[] submitMessageId = ready.record().submitMessageId();
            byte[] notifyMessageId = ready.record().notifyMessageId();
            boolean directContinuation = notifyMessageId == null;
            YanoAuditClient.FinalityAudit submitFinality = waitFinality(
                    primary, Digests.hex(submitMessageId), config.requireAnchor());
            YanoAuditClient.FinalityAudit continuationFinality = directContinuation
                    ? submitFinality
                    : waitFinality(primary, Digests.hex(notifyMessageId), config.requireAnchor());
            if (config.requireAnchor()
                    && (!submitFinality.anchored() || !continuationFinality.anchored())) {
                throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
            }
            boolean portableAnchorLinkage = submitFinality.anchored()
                    && continuationFinality.anchored();
            checks.add(portableAnchorLinkage
                    ? pass("PORTABLE_ANCHOR_LINKAGE")
                    : new ScenarioReport.Check("PORTABLE_ANCHOR_LINKAGE", "NOT_EVALUATED"));
            checks.add(pass(directContinuation
                    ? "DIRECT_RESULT_CONTINUATION" : "EXPLICIT_NOTIFY_CONTINUATION"));

            requireFinalityMatchesCluster(agreement, submitFinality, continuationFinality);
            checks.add(pass("CERTIFIED_READY_STATE_BINDING"));
            requireFinalityMatchesObservedAnchor(
                    anchoredStatus, submitFinality, continuationFinality, config.requireAnchor());
            checks.add(pass("THRESHOLD_FINALITY_BUNDLES"));
            checks.add(pass("THREE_NODE_TOPOLOGY"));
            checks.add(pass("THREE_NODE_STATE_AGREEMENT"));
            List<String> portableAnchorTransactions = config.requireAnchor()
                    ? portableTransactionHashes(submitFinality, continuationFinality) : List.of();
            boolean portableTransactionsVisible = !portableAnchorTransactions.isEmpty()
                    && waitForL1Visibility(portableAnchorTransactions);
            checks.add(portableTransactionsVisible
                    ? pass("PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS")
                    : new ScenarioReport.Check(
                    "PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS", "NOT_EVALUATED"));
            Map<String, YanoAuditClient.AnchorExpectation> portableExpectations =
                    portableAnchorExpectations(config.requireAnchor(), config.chainId(),
                            agreement, anchoredStatus,
                            submitFinality, continuationFinality);
            boolean portableDatumCommitmentsVerified = !portableExpectations.isEmpty()
                    && waitForAnchorCommitments(portableExpectations);
            checks.add(portableDatumCommitmentsVerified
                    ? pass("PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED")
                    : new ScenarioReport.Check(
                    "PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED", "NOT_EVALUATED"));

            if (ready.record().objectEffect().height() != submitFinality.messageHeight()
                    || ready.record().ipfsEffect().height() != submitFinality.messageHeight()
                    || !directContinuation && ready.record().notificationEffect().height()
                    != continuationFinality.messageHeight()
                    || directContinuation && !continuationFinality.certifiedStateRoots()
                    .containsKey(ready.record().notificationEffect().height())) {
                throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
            }
            EffectRecord objectEffect = primary.verifyEffect(
                    ready.record().objectEffect().height(),
                    ready.record().objectEffect().ordinal(), submitFinality.messageStateRoot(),
                    ConnectorTypes.OBJECT_PUT, ready.record().objectPutCommand(), submitMessageId);
            EffectRecord ipfsEffect = primary.verifyEffect(
                    ready.record().ipfsEffect().height(),
                    ready.record().ipfsEffect().ordinal(), submitFinality.messageStateRoot(),
                    ConnectorTypes.IPFS_PIN, ready.record().ipfsPinCommand(), submitMessageId);

            EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(ready.record());
            KafkaPublishCommandV1 publish = new KafkaPublishCommandV1(
                    ready.record().kafkaTarget(), ready.record().kafkaTopic(), event.kafkaKey(),
                    event.contentType(), event.encode(), List.of());
            EffectRecord kafkaEffect = primary.verifyEffect(
                    ready.record().notificationEffect().height(),
                    ready.record().notificationEffect().ordinal(),
                    continuationFinality.certifiedStateRoots().get(
                            ready.record().notificationEffect().height()),
                    ConnectorTypes.KAFKA_PUBLISH,
                    publish.encode(), notifyMessageId);
            checks.add(pass("COMPOSED_EFFECT_PROOFS"));

            KafkaPublishReceiptV1 kafkaReceipt = KafkaPublishReceiptV1.decode(
                    ready.record().notificationTerminal().externalRef());
            if (!Arrays.equals(kafkaReceipt.destinationFingerprint(), kafkaDestination)) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            KafkaDemoClient.VerifiedKafkaEvent kafkaAudit = environment.kafka.verify(
                    window, kafkaReceipt, ready.record(), kafkaEffect, config.timeout());
            checks.add(pass("KAFKA_ACKNOWLEDGEMENT_AND_EVENT"));

            boolean memberAnchorCovered = config.requireAnchor()
                    && anchoredStatus.anchoredHeight()
                    >= agreement.committedHeight() && anchoredStatus.anchorTx() != null;
            checks.add(memberAnchorCovered
                    ? pass("APP_CHAIN_MEMBER_ANCHOR_OBSERVATION")
                    : new ScenarioReport.Check(
                    "APP_CHAIN_MEMBER_ANCHOR_OBSERVATION", "NOT_EVALUATED"));
            String anchorTx = config.requireAnchor()
                    ? anchoredStatus.anchorTx() : null;
            boolean memberObservedTransactionVisible = memberAnchorCovered
                    && waitForL1Visibility(List.of(anchorTx));
            checks.add(memberObservedTransactionVisible
                    ? pass("MEMBER_OBSERVED_ANCHOR_TX_VISIBLE_ON_ALL_MEMBERS")
                    : new ScenarioReport.Check(
                    "MEMBER_OBSERVED_ANCHOR_TX_VISIBLE_ON_ALL_MEMBERS", "NOT_EVALUATED"));
            YanoAuditClient.AnchorExpectation memberExpectation = config.requireAnchor()
                    ? expectation(config.chainId(), agreement, anchoredStatus,
                    submitFinality) : null;
            boolean memberObservedDatumCommitment = config.requireAnchor()
                    && memberAnchorCovered
                    && waitForAnchorCommitments(Map.of(anchorTx, memberExpectation));
            checks.add(memberObservedDatumCommitment
                    ? pass("MEMBER_OBSERVED_ANCHOR_DATUM_COMMITMENT_VERIFIED")
                    : new ScenarioReport.Check(
                    "MEMBER_OBSERVED_ANCHOR_DATUM_COMMITMENT_VERIFIED", "NOT_EVALUATED"));
            checks.add(new ScenarioReport.Check(
                    "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));

            int effectProofsVerified = List.of(objectEffect, ipfsEffect, kafkaEffect).size();
            int finalityBundlesVerified = directContinuation ? 1 : 2;
            ScenarioReport.ChainSummary chain = new ScenarioReport.ChainSummary(
                    config.chainId(), agreement.committedHeight(), agreement.stateRoot(),
                    EvidenceStatus.READY.name(), agreement.memberKeys().size(),
                    agreement.threshold(), agreement.memberKeys().size() * 2,
                    effectProofsVerified, finalityBundlesVerified);
            ScenarioReport.StorageSummary storage = new ScenarioReport.StorageSummary(
                    Digests.hex(digest), document.length, objectAudit.versionFingerprint(),
                    cid.canonicalText(), true, true);
            ScenarioReport.KafkaSummary kafka = new ScenarioReport.KafkaSummary(
                    config.kafka().physicalTopic(), kafkaAudit.partition(), kafkaAudit.offset(), true);
            ScenarioReport.AnchorSummary anchor = new ScenarioReport.AnchorSummary(
                    config.requireAnchor(), portableAnchorLinkage,
                    portableAnchorTransactions, portableTransactionsVisible,
                    portableDatumCommitmentsVerified, memberAnchorCovered,
                    anchoredStatus.anchoredHeight(), anchorTx,
                    memberObservedTransactionVisible, memberObservedDatumCommitment);
            return new ScenarioReport(ScenarioReport.SCHEMA_VERSION, scenarioId,
                    config.evidenceId(), "PASS", started.toString(), clock.instant().toString(),
                    chain, storage, kafka, anchor, checks, null);
        }
    }

    private String submitInitialCommand(
            YanoAuditClient primary,
            DemoConfig config,
            SubmitEvidenceCommandV1 submit,
            byte[] documentHash,
            String documentRef
    ) {
        try {
            if (!"composite".equals(config.stateMachine())) {
                return primary.evidence().submit(submit).messageId();
            }

            String token = Digests.hex(Digests.sha256(
                    ("evidence-release-v1\0" + config.evidenceId())
                            .getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            byte[] registryKey = ("evidence/" + config.evidenceId())
                    .getBytes(StandardCharsets.UTF_8);
            String approvalId = "approval-" + token;
            byte[] evidenceCommand = submit.encode();

            AppChainClient.SubmitResult registry = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.REGISTRY_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.registryPut(
                            registryKey, documentHash));
            waitFinality(primary, registry.messageId(), false);

            AppChainClient.SubmitResult proposal = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.APPROVALS_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.approvalPropose(
                            approvalId, evidenceCommand, 1, 0));
            waitFinality(primary, proposal.messageId(), false);

            AppChainClient.SubmitResult approval = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.APPROVALS_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.approvalApprove(approvalId));
            waitFinality(primary, approval.messageId(), false);

            // Probe only after the profile and evidence component are active,
            // but before this release can create the record.
            Optional<VerifiedEvidence> retained = primary.evidence().queryVerified(
                    config.evidenceId(), 1);

            EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                    "release-" + token,
                    registryKey,
                    approvalId,
                    "document-" + token,
                    documentHash,
                    "object:" + documentRef,
                    evidenceCommand);
            String releaseMessageId = primary.appChain()
                    .submit(EvidenceReleaseCommandV1.TOPIC, release.encode()).messageId();
            // On replay, the release command is deliberately a deterministic
            // no-op and the authenticated evidence record retains the message
            // id that originally created it. Still wait for the new release
            // envelope to finalize so the demo proves idempotent replay rather
            // than returning immediately from already-ready state.
            waitFinality(primary, releaseMessageId, false);
            return retained
                    .map(existing -> Digests.hex(existing.record().submitMessageId()))
                    .orElse(releaseMessageId);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SUBMISSION_FAILED);
        }
    }

    private VerifiedEvidence waitEvidence(YanoAuditClient node,
                                          Predicate<EvidenceStatus> wanted,
                                          boolean storagePhase) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Optional<VerifiedEvidence> result = node.evidence().queryVerified(
                        environment.config.evidenceId(), 1);
                if (result.isPresent()) {
                    EvidenceStatus status = result.get().status();
                    if (wanted.test(status)) {
                        return result.get();
                    }
                    if (storagePhase && switch (status) {
                        case PARTIAL, STORAGE_FAILED, EXPIRED -> true;
                        default -> false;
                    }) {
                        throw new DemoException(DemoError.STORAGE_FAILED);
                    }
                    if (!storagePhase && switch (status) {
                        case READY_NOTIFICATION_FAILED, READY_NOTIFICATION_EXPIRED -> true;
                        default -> false;
                    }) {
                        throw new DemoException(DemoError.NOTIFICATION_FAILED);
                    }
                }
            } catch (DemoException failure) {
                throw failure;
            } catch (RuntimeException transientFailure) {
                // Query/proof races and service startup are retried only until
                // the scenario's fixed deadline.
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(storagePhase
                ? DemoError.STORAGE_FAILED : DemoError.NOTIFICATION_FAILED);
    }

    private YanoAuditClient.FinalityAudit waitFinality(YanoAuditClient node,
                                                       String messageId,
                                                       boolean requireAnchor) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                return node.verifyMessageEvidence(messageId, requireAnchor);
            } catch (DemoException pending) {
                if (pending.error() != DemoError.ANCHOR_UNAVAILABLE
                        && pending.error() != DemoError.STATE_PROOF_FAILED) {
                    throw pending;
                }
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(requireAnchor
                ? DemoError.ANCHOR_UNAVAILABLE : DemoError.STATE_PROOF_FAILED);
    }

    private ClusterAgreement waitForAgreement(EvidenceRecordV1 expected) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                long height = Long.MAX_VALUE;
                String root = null;
                boolean agreed = true;
                List<YanoAuditClient.Status> statuses = new ArrayList<>();
                for (YanoAuditClient node : environment.yano) {
                    statuses.add(node.status());
                    VerifiedEvidence verified = node.evidence().queryVerified(
                            environment.config.evidenceId(), 1).orElseThrow();
                    if (!verified.record().equals(expected)
                            || verified.status() != EvidenceStatus.READY) {
                        agreed = false;
                        break;
                    }
                    if (root == null) {
                        root = Digests.hex(verified.stateRoot());
                    } else if (!root.equals(Digests.hex(verified.stateRoot()))) {
                        agreed = false;
                        break;
                    }
                    height = Math.min(height, verified.committedHeight());
                }
                if (agreed && height != Long.MAX_VALUE) {
                    DemoClusterTopology topology = DemoClusterTopology.verify(statuses,
                            environment.config.yanoMemberKeys(),
                            environment.config.yanoThreshold(),
                            environment.config.stateMachine());
                    return new ClusterAgreement(height, root, topology.memberKeys(),
                            topology.threshold());
                }
            } catch (DemoException failure) {
                if (failure.error() == DemoError.CLUSTER_DIVERGED) {
                    throw failure;
                }
            } catch (RuntimeException transientFailure) {
                // Followers may be one finalized block behind; retry as one snapshot.
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(DemoError.CLUSTER_DIVERGED);
    }

    private YanoAuditClient.Status waitForAnchor(long height, boolean required) {
        if (!required) {
            return environment.yano.getFirst().status();
        }
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                YanoAuditClient.Status first = null;
                boolean covered = true;
                List<YanoAuditClient.Status> statuses = new ArrayList<>();
                for (YanoAuditClient node : environment.yano) {
                    YanoAuditClient.Status status = node.status();
                    statuses.add(status);
                    if (first == null) {
                        first = status;
                    } else if (status.anchoredHeight() != first.anchoredHeight()
                            || !java.util.Objects.equals(status.anchorTx(), first.anchorTx())) {
                        covered = false;
                    }
                    if (first != null && (status.anchorSlot() != first.anchorSlot()
                            || !java.util.Objects.equals(status.anchorScriptAddress(),
                            first.anchorScriptAddress())
                            || !java.util.Objects.equals(status.anchorThreadPolicyId(),
                            first.anchorThreadPolicyId()))) {
                        covered = false;
                    }
                    if (status.anchoredHeight() < height || status.anchorTx() == null) {
                        covered = false;
                    }
                }
                if (covered) {
                    DemoClusterTopology.verifyAnchored(statuses,
                            environment.config.yanoMemberKeys(),
                            environment.config.yanoThreshold(),
                            environment.config.stateMachine());
                    return first;
                }
            } catch (RuntimeException transientFailure) {
                // L1 observers converge independently.
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
    }

    private boolean waitForL1Visibility(List<String> transactionHashes) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (allTransactionsVisibleOnce(environment.yano, transactionHashes)) {
                    return true;
                }
            } catch (DemoException pending) {
                if (pending.error() != DemoError.SERVICE_TIMEOUT
                        && pending.error() != DemoError.ANCHOR_UNAVAILABLE) {
                    throw pending;
                }
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
    }

    private boolean waitForAnchorCommitments(
            Map<String, YanoAuditClient.AnchorExpectation> expectations) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (allAnchorCommitmentsOnce(environment.yano, expectations)) {
                    return true;
                }
            } catch (DemoException pending) {
                if (pending.error() != DemoError.SERVICE_TIMEOUT
                        && pending.error() != DemoError.ANCHOR_UNAVAILABLE) {
                    throw pending;
                }
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
    }

    static boolean allAnchorCommitmentsOnce(
            List<YanoAuditClient> nodes,
            Map<String, YanoAuditClient.AnchorExpectation> expectations) {
        if (nodes == null || nodes.size() != DemoClusterTopology.EXPECTED_MEMBERS
                || expectations == null || expectations.isEmpty()) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        for (Map.Entry<String, YanoAuditClient.AnchorExpectation> entry
                : expectations.entrySet()) {
            YanoAuditClient.AnchorCommitment first = null;
            for (YanoAuditClient node : nodes) {
                YanoAuditClient.AnchorCommitment observed = node.verifyAnchorCommitment(
                        entry.getKey(), entry.getValue());
                if (first == null) {
                    first = observed;
                } else if (!first.equals(observed)) {
                    throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
                }
            }
        }
        return true;
    }

    static boolean allTransactionsVisibleOnce(List<YanoAuditClient> nodes,
                                              List<String> transactionHashes) {
        if (nodes == null || nodes.size() != DemoClusterTopology.EXPECTED_MEMBERS
                || transactionHashes == null || transactionHashes.isEmpty()) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        Set<String> distinct = new LinkedHashSet<>(transactionHashes);
        if (distinct.size() != transactionHashes.size()
                || distinct.stream().anyMatch(hash -> hash == null
                || !hash.matches("[0-9a-f]{64}"))) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
        for (String transactionHash : distinct) {
            for (YanoAuditClient node : nodes) {
                if (!node.anchorTransactionVisible(transactionHash)) {
                    return false;
                }
            }
        }
        return true;
    }

    static List<String> portableTransactionHashes(
            YanoAuditClient.FinalityAudit submit,
            YanoAuditClient.FinalityAudit notify) {
        Set<String> hashes = new LinkedHashSet<>();
        for (YanoAuditClient.FinalityAudit audit : List.of(submit, notify)) {
            if (audit.anchored()) {
                if (audit.anchorTx() == null || !audit.anchorTx().matches("[0-9a-f]{64}")) {
                    throw new DemoException(DemoError.STATE_PROOF_FAILED);
                }
                hashes.add(audit.anchorTx());
            } else if (audit.anchorTx() != null) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
        }
        return List.copyOf(hashes);
    }

    static Map<String, YanoAuditClient.AnchorExpectation> portableAnchorExpectations(
            boolean required,
            String chainId,
            ClusterAgreement agreement,
            YanoAuditClient.Status anchoredStatus,
            YanoAuditClient.FinalityAudit submit,
            YanoAuditClient.FinalityAudit notify) {
        if (!required) {
            return Map.of();
        }
        Map<String, YanoAuditClient.AnchorExpectation> expectations = new LinkedHashMap<>();
        for (YanoAuditClient.FinalityAudit audit : List.of(submit, notify)) {
            if (!audit.anchored()) {
                continue;
            }
            YanoAuditClient.AnchorExpectation expected = expectation(
                    chainId, agreement, anchoredStatus, audit);
            YanoAuditClient.AnchorExpectation previous = expectations.putIfAbsent(
                    audit.anchorTx(), expected);
            if (previous != null && !sameExpectation(previous, expected)) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
        }
        return Map.copyOf(expectations);
    }

    private static YanoAuditClient.AnchorExpectation expectation(
            String chainId,
            ClusterAgreement agreement,
            YanoAuditClient.Status anchoredStatus,
            YanoAuditClient.FinalityAudit audit) {
        try {
            if (audit == null || !audit.anchored()
                    || audit.anchorBlockHash() == null || audit.anchorStateRoot() == null) {
                throw new DemoException(DemoError.STATE_PROOF_FAILED);
            }
            return new YanoAuditClient.AnchorExpectation(chainId, audit.anchorSlot(),
                    audit.anchoredHeight(), Digests.fromHex(audit.anchorBlockHash()),
                    Digests.fromHex(audit.anchorStateRoot()), agreement.memberKeys(),
                    agreement.threshold(), anchoredStatus.anchorScriptAddress(),
                    anchoredStatus.anchorThreadPolicyId());
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    private static boolean sameExpectation(YanoAuditClient.AnchorExpectation first,
                                           YanoAuditClient.AnchorExpectation second) {
        return first.chainId().equals(second.chainId())
                && first.l1Slot() == second.l1Slot()
                && first.anchoredHeight() == second.anchoredHeight()
                && Arrays.equals(first.blockHash(), second.blockHash())
                && Arrays.equals(first.stateRoot(), second.stateRoot())
                && first.memberKeys().equals(second.memberKeys())
                && first.threshold() == second.threshold()
                && first.scriptAddress().equals(second.scriptAddress())
                && first.threadPolicyId().equals(second.threadPolicyId());
    }

    static void requireSubmittedState(EvidenceRecordV1 record,
                                      SubmitEvidenceCommandV1 command,
                                      String expectedKafkaTarget,
                                      String expectedKafkaTopic) {
        if (!record.evidenceId().equals(command.evidenceId())
                || record.businessVersion() != command.businessVersion()
                || !Arrays.equals(record.objectPutCommand(), command.objectPutCommand())
                || !Arrays.equals(record.ipfsPinCommand(), command.ipfsPinCommand())
                || !Arrays.equals(record.expectedObjectDestinationFingerprint(),
                command.expectedObjectDestinationFingerprint())
                || !Arrays.equals(record.expectedIpfsTargetFingerprint(),
                command.expectedIpfsTargetFingerprint())
                || !record.kafkaTarget().equals(command.kafkaTarget())
                || !record.kafkaTopic().equals(command.kafkaTopic())
                || !command.kafkaTarget().equals(expectedKafkaTarget)
                || !command.kafkaTopic().equals(expectedKafkaTopic)
                || !Arrays.equals(record.expectedKafkaDestinationFingerprint(),
                command.expectedKafkaDestinationFingerprint())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    static void requireStableStorage(EvidenceRecordV1 before, EvidenceRecordV1 after) {
        if (!Arrays.equals(before.objectPutCommand(), after.objectPutCommand())
                || !before.objectEffect().equals(after.objectEffect())
                || !before.objectTerminal().equals(after.objectTerminal())
                || !Arrays.equals(before.ipfsPinCommand(), after.ipfsPinCommand())
                || !before.ipfsEffect().equals(after.ipfsEffect())
                || !before.ipfsTerminal().equals(after.ipfsTerminal())
                || !before.kafkaTarget().equals(after.kafkaTarget())
                || !before.kafkaTopic().equals(after.kafkaTopic())
                || !Arrays.equals(before.expectedKafkaDestinationFingerprint(),
                after.expectedKafkaDestinationFingerprint())) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    static void requireFinalityMatchesCluster(
            ClusterAgreement agreement,
            YanoAuditClient.FinalityAudit submit,
            YanoAuditClient.FinalityAudit notify) {
        if (agreement == null || submit == null || notify == null
                || agreement.threshold() < 1
                || agreement.memberKeys().size() < agreement.threshold()
                || submit.threshold() != agreement.threshold()
                || notify.threshold() != agreement.threshold()
                || !submit.memberKeys().equals(agreement.memberKeys())
                || !notify.memberKeys().equals(agreement.memberKeys())
                || submit.certificateSignatures() < agreement.threshold()
                || notify.certificateSignatures() < agreement.threshold()
                || !agreement.stateRoot().equals(
                submit.certifiedStateRoots().get(agreement.committedHeight()))
                || !agreement.stateRoot().equals(
                notify.certifiedStateRoots().get(agreement.committedHeight()))
                || submit.anchored() != (submit.anchorTx() != null)
                || notify.anchored() != (notify.anchorTx() != null)) {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
    }

    static void requireFinalityMatchesObservedAnchor(
            YanoAuditClient.Status status,
            YanoAuditClient.FinalityAudit submit,
            YanoAuditClient.FinalityAudit notify,
            boolean required) {
        if (!required) {
            return;
        }
        if (status == null || status.anchorTx() == null
                || !submit.anchored() || !notify.anchored()
                || submit.anchoredHeight() != status.anchoredHeight()
                || notify.anchoredHeight() != status.anchoredHeight()
                || submit.anchorSlot() != status.anchorSlot()
                || notify.anchorSlot() != status.anchorSlot()
                || !status.anchorTx().equals(submit.anchorTx())
                || !status.anchorTx().equals(notify.anchorTx())
                || !java.util.Objects.equals(
                submit.anchorBlockHash(), notify.anchorBlockHash())
                || !java.util.Objects.equals(
                submit.anchorStateRoot(), notify.anchorStateRoot())) {
            throw new DemoException(DemoError.ANCHOR_UNAVAILABLE);
        }
    }

    static byte[] readSample(java.nio.file.Path path) {
        try {
            return BoundedFiles.read(path, MAX_SAMPLE_BYTES, true, false);
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.SAMPLE_INVALID);
        }
    }

    private static ScenarioReport.Check pass(String name) {
        return new ScenarioReport.Check(name, "PASS");
    }

    private static String scenarioId(String evidenceId, Instant instant) {
        String prefix = evidenceId.substring(0, Math.min(evidenceId.length(), 40));
        return prefix + "-" + instant.toEpochMilli();
    }

    record ClusterAgreement(long committedHeight,
                            String stateRoot,
                            Set<String> memberKeys,
                            int threshold) {
        ClusterAgreement {
            memberKeys = Set.copyOf(memberKeys);
        }
    }
}
