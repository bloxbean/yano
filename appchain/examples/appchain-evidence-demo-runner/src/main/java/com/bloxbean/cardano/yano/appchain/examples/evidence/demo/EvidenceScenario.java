package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleasePrerequisiteCommandsV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.client.VerifiedEvidence;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
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
    private final LoadWorkflowGates loadWorkflows;
    private final LoadAnchorAdvancer loadAnchors;

    EvidenceScenario(DemoEnvironment environment, ReportStore reports) {
        this(environment, reports, Clock.systemUTC(), null, null);
    }

    EvidenceScenario(DemoEnvironment environment, ReportStore reports, Clock clock) {
        this(environment, reports, clock, null, null);
    }

    EvidenceScenario(DemoEnvironment environment,
                     ReportStore reports,
                     Clock clock,
                     LoadWorkflowGates loadWorkflows,
                     LoadAnchorAdvancer loadAnchors) {
        this.environment = environment;
        this.reports = reports;
        this.clock = clock;
        this.loadWorkflows = loadWorkflows;
        this.loadAnchors = loadAnchors;
    }

    ScenarioReport run() {
        return run(ScenarioRequest.runDefaults(environment.config));
    }

    ScenarioReport run(ScenarioRequest request) {
        Instant started = clock.instant();
        String scenarioId = scenarioId(request.evidenceId(), started);
        List<ScenarioReport.Check> checks = new ArrayList<>();
        try {
            ScenarioReport report = execute(request, scenarioId, started, checks);
            reports.write(report);
            return report;
        } catch (DemoException failure) {
            checks.add(new ScenarioReport.Check("SCENARIO", "FAIL"));
            checks.add(new ScenarioReport.Check(
                    "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));
            ScenarioReport report = new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                    scenarioId, request.evidenceId(), request.operation().name(),
                    request.businessVersion(), false, 0, "FAIL",
                    started.toString(), clock.instant().toString(), null, null, null, null,
                    checks, failure.error().name());
            reports.write(report);
            throw failure;
        } catch (RuntimeException failure) {
            checks.add(new ScenarioReport.Check("SCENARIO", "FAIL"));
            checks.add(new ScenarioReport.Check(
                    "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));
            ScenarioReport report = new ScenarioReport(ScenarioReport.SCHEMA_VERSION,
                    scenarioId, request.evidenceId(), request.operation().name(),
                    request.businessVersion(), false, 0, "FAIL",
                    started.toString(), clock.instant().toString(), null, null, null, null,
                    checks, DemoError.INTERNAL_ERROR.name());
            reports.write(report);
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }

    void probePipeline() {
        if (!"composite".equals(environment.config.stateMachine())) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        new DemoInitializer(environment).probe();
    }

    PipelineItem preparePipeline(ScenarioRequest requested) {
        if (requested.operation() != ScenarioRequest.Operation.PUBLISH
                || !"composite".equals(environment.config.stateMachine())) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        byte[] document = readSample(requested.sampleFile());
        byte[] digest = Digests.sha256(document);
        DemoConfig config = environment.config;
        ScenarioRequest request = preflight(requested, environment.yano.getFirst(),
                digest, document.length);
        if (request.operation() != ScenarioRequest.Operation.PUBLISH) {
            throw new DemoException(DemoError.EVIDENCE_ALREADY_EXISTS);
        }
        long version = request.businessVersion();
        String relativeKey = request.evidenceId() + "/v" + version
                + "/inspection-certificate.bin";
        environment.s3.stage(relativeKey, document, digest);
        CanonicalCid cid = environment.kubo.addUnpinned(document);
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
        EvidenceCommandV1 command = new SubmitEvidenceCommandV1(
                request.evidenceId(), version, object.encode(), objectDestination,
                ipfs.encode(), ipfsTarget, config.kafka().target(),
                config.kafka().topicAlias(), kafkaDestination);
        return PipelineItem.prepared(request, command, digest, relativeKey);
    }

    PipelineItem submitPipelinePrerequisites(PipelineItem item) {
        try {
            YanoAuditClient primary = environment.yano.getFirst();
            AppChainClient.SubmitResult registry = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.REGISTRY_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.registryPut(
                            item.registryKey(), item.documentHash()));
            AppChainClient.SubmitResult proposal = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.APPROVALS_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.approvalPropose(
                            item.approvalId(), item.evidenceCommand(), 1, 0));
            waitFinality(primary, registry.messageId(), false);
            waitFinality(primary, proposal.messageId(), false);
            return item.submitted(2);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SUBMISSION_FAILED);
        }
    }

    PipelineItem submitPipelineApproval(PipelineItem item) {
        try {
            YanoAuditClient primary = environment.yano.getFirst();
            AppChainClient.SubmitResult approval = primary.appChain().submit(
                    EvidenceReleasePrerequisiteCommandsV1.APPROVALS_TOPIC,
                    EvidenceReleasePrerequisiteCommandsV1.approvalApprove(item.approvalId()));
            waitFinality(primary, approval.messageId(), false);
            return item.submitted(1);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SUBMISSION_FAILED);
        }
    }

    PipelineItem submitPipelineRelease(PipelineItem item) {
        if (loadWorkflows == null || item.acceptedMessageId() != null) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        return loadWorkflows.release(() -> {
            try {
                YanoAuditClient primary = environment.yano.getFirst();
                if (primary.evidence().queryVerified(
                        item.request().evidenceId(), item.request().businessVersion()).isPresent()) {
                    throw new DemoException(DemoError.EVIDENCE_ALREADY_EXISTS);
                }
                EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                        item.releaseId(), item.registryKey(), item.approvalId(),
                        item.documentEntityId(), item.documentHash(),
                        "object:" + item.documentRef(), item.evidenceCommand());
                String messageId = primary.appChain().submit(
                        EvidenceReleaseCommandV1.TOPIC, release.encode()).messageId();
                waitFinality(primary, messageId, false);
                return item.released(messageId).submitted(1);
            } catch (DemoException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new DemoException(DemoError.SUBMISSION_FAILED);
            }
        });
    }

    PipelineItem awaitPipelineEffects(PipelineItem item) {
        if (item.acceptedMessageId() == null) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        DemoConfig config = environment.config;
        YanoAuditClient primary = environment.yano.getFirst();
        VerifiedEvidence storageReady = waitEvidence(primary,
                item.request().evidenceId(), item.request().businessVersion(),
                status -> status == EvidenceStatus.STORAGE_READY
                        || status == EvidenceStatus.NOTIFICATION_PENDING
                        || status == EvidenceStatus.READY,
                true);
        requireSubmittedState(storageReady.record(), item.storageCommand(),
                config.kafka().target(), config.kafka().topicAlias());
        if (!item.acceptedMessageId().equals(
                Digests.hex(storageReady.record().submitMessageId()))) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        boolean notificationSubmitted = storageReady.record().notificationEffect() == null;
        if (notificationSubmitted) {
            submitNotificationForLoad(primary, config,
                    item.request().evidenceId(), item.request().businessVersion());
        }
        VerifiedEvidence ready = waitEvidence(primary,
                item.request().evidenceId(), item.request().businessVersion(),
                status -> status == EvidenceStatus.READY, false);
        requireStableStorage(storageReady.record(), ready.record());
        return notificationSubmitted ? item.submitted(1) : item;
    }

    ScenarioReport verifyPipeline(PipelineItem item) {
        if (item.acceptedMessageId() == null) {
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
        return run(new ScenarioRequest(ScenarioRequest.Operation.VERIFY,
                item.request().evidenceId(), item.request().businessVersion(), null));
    }

    record PipelineItem(ScenarioRequest request,
                        EvidenceCommandV1 storageCommand,
                        byte[] documentHash,
                        String documentRef,
                        byte[] registryKey,
                        String approvalId,
                        String releaseId,
                        String documentEntityId,
                        String acceptedMessageId,
                        int submittedMessages) {
        PipelineItem {
            request = java.util.Objects.requireNonNull(request, "request");
            storageCommand = java.util.Objects.requireNonNull(storageCommand, "storageCommand");
            documentHash = java.util.Objects.requireNonNull(documentHash, "documentHash").clone();
            registryKey = java.util.Objects.requireNonNull(registryKey, "registryKey").clone();
            documentRef = java.util.Objects.requireNonNull(documentRef, "documentRef");
            approvalId = java.util.Objects.requireNonNull(approvalId, "approvalId");
            releaseId = java.util.Objects.requireNonNull(releaseId, "releaseId");
            documentEntityId = java.util.Objects.requireNonNull(
                    documentEntityId, "documentEntityId");
            String evidenceToken = releaseToken(request.evidenceId(), null, null);
            String versionToken = releaseToken(request.evidenceId(),
                    request.businessVersion(), storageCommand.encode());
            byte[] expectedRegistry = ("evidence/" + request.evidenceId())
                    .getBytes(StandardCharsets.UTF_8);
            if (request.operation() != ScenarioRequest.Operation.PUBLISH
                    || !(storageCommand instanceof SubmitEvidenceCommandV1 submit)
                    || !request.evidenceId().equals(storageCommand.evidenceId())
                    || request.businessVersion() != storageCommand.businessVersion()
                    || documentHash.length != 32
                    || !Arrays.equals(documentHash,
                    ObjectPutCommandV1.decode(submit.objectPutCommand()).digest())
                    || documentRef.isBlank()
                    || !Arrays.equals(registryKey, expectedRegistry)
                    || !approvalId.equals("approval-" + versionToken)
                    || !releaseId.equals("release-" + versionToken)
                    || !documentEntityId.equals("document-" + evidenceToken)
                    || acceptedMessageId != null
                    && !acceptedMessageId.matches("[0-9a-f]{64}")
                    || submittedMessages < 0 || submittedMessages > 5) {
                throw new IllegalArgumentException("invalid pipeline item");
            }
        }

        static PipelineItem prepared(ScenarioRequest request,
                                     EvidenceCommandV1 storageCommand,
                                     byte[] documentHash,
                                     String documentRef) {
            String evidenceToken = releaseToken(request.evidenceId(), null, null);
            String versionToken = releaseToken(request.evidenceId(),
                    request.businessVersion(), storageCommand.encode());
            return new PipelineItem(request, storageCommand, documentHash, documentRef,
                    ("evidence/" + request.evidenceId()).getBytes(StandardCharsets.UTF_8),
                    "approval-" + versionToken, "release-" + versionToken,
                    "document-" + evidenceToken, null, 0);
        }

        PipelineItem released(String messageId) {
            return new PipelineItem(request, storageCommand, documentHash, documentRef,
                    registryKey, approvalId, releaseId, documentEntityId, messageId,
                    submittedMessages);
        }

        PipelineItem submitted(int count) {
            return new PipelineItem(request, storageCommand, documentHash, documentRef,
                    registryKey, approvalId, releaseId, documentEntityId,
                    acceptedMessageId, Math.addExact(submittedMessages, count));
        }

        byte[] evidenceCommand() {
            return storageCommand.encode();
        }

        @Override
        public byte[] documentHash() {
            return documentHash.clone();
        }

        @Override
        public byte[] registryKey() {
            return registryKey.clone();
        }
    }

    private ScenarioRequest preflight(
            ScenarioRequest request,
            YanoAuditClient primary,
            byte[] digest,
            long size
    ) {
        Optional<VerifiedEvidence> latest = agreedEvidence(request.evidenceId(), 0);
        checksProfileOwner(primary, latest);
        return switch (request.operation()) {
            case RUN -> {
                if (latest.isEmpty()) {
                    yield new ScenarioRequest(ScenarioRequest.Operation.PUBLISH,
                            request.evidenceId(), 1, request.sampleFile());
                }
                if (!matchesInput(latest.orElseThrow().record(), digest, size)) {
                    throw new DemoException(DemoError.REPUBLISH_REQUIRED);
                }
                yield new ScenarioRequest(ScenarioRequest.Operation.VERIFY,
                        request.evidenceId(), latest.orElseThrow().record().businessVersion(), null);
            }
            case PUBLISH -> {
                if (latest.isPresent()) {
                    throw new DemoException(DemoError.EVIDENCE_ALREADY_EXISTS);
                }
                yield request;
            }
            case REPUBLISH -> {
                VerifiedEvidence existing = latest.orElseThrow(
                        () -> new DemoException(DemoError.EVIDENCE_NOT_FOUND));
                final long expectedVersion;
                try {
                    expectedVersion = Math.addExact(existing.head().latestVersion(), 1);
                } catch (ArithmeticException exhausted) {
                    throw new DemoException(DemoError.VERSION_CONFLICT);
                }
                if (request.businessVersion() != expectedVersion) {
                    throw new DemoException(DemoError.VERSION_CONFLICT);
                }
                if (!existing.status().permitsRepublish()) {
                    throw new DemoException(DemoError.PRIOR_VERSION_NOT_TERMINAL);
                }
                String owner = Digests.hex(existing.head().ownerPublicKey());
                if (!owner.equals(primary.status().memberKey())) {
                    throw new DemoException(DemoError.OWNER_MISMATCH);
                }
                yield request;
            }
            case REPLAY -> {
                VerifiedEvidence existing = agreedEvidence(
                        request.evidenceId(), request.businessVersion()).orElseThrow(
                        () -> new DemoException(DemoError.EVIDENCE_NOT_FOUND));
                if (!matchesInput(existing.record(), digest, size)) {
                    throw new DemoException(DemoError.REPLAY_INPUT_MISMATCH);
                }
                yield request;
            }
            case VERIFY -> throw new DemoException(DemoError.INTERNAL_ERROR);
        };
    }

    private void checksProfileOwner(
            YanoAuditClient primary,
            Optional<VerifiedEvidence> latest
    ) {
        if (latest.isPresent()
                && !latest.orElseThrow().chainId().equals(environment.config.chainId())) {
            throw new DemoException(DemoError.MEMBER_STATE_DISAGREEMENT);
        }
        if (!primary.status().stateMachine().equals(environment.config.stateMachine())) {
            throw new DemoException(DemoError.MEMBER_STATE_DISAGREEMENT);
        }
    }

    private Optional<VerifiedEvidence> agreedEvidence(String evidenceId, long version) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                List<YanoAuditClient.Status> statuses = new ArrayList<>();
                List<Optional<VerifiedEvidence>> evidence = new ArrayList<>();
                boolean stable = true;
                for (YanoAuditClient node : environment.yano) {
                    YanoAuditClient.Status before = node.status();
                    Optional<VerifiedEvidence> verified;
                    if (before.height() == 0 && before.stateRoot().equals("0".repeat(64))) {
                        node.pristineGenesisReady(before);
                        verified = Optional.empty();
                    } else {
                        verified = node.evidence().queryVerified(evidenceId, version);
                    }
                    YanoAuditClient.Status after = node.status();
                    if (before.height() != after.height()
                            || !before.stateRoot().equals(after.stateRoot())
                            || verified.isPresent() && (!after.stateRoot().equals(
                            Digests.hex(verified.orElseThrow().stateRoot()))
                            || after.height() != verified.orElseThrow().committedHeight())) {
                        stable = false;
                        break;
                    }
                    statuses.add(after);
                    evidence.add(verified);
                }
                if (!stable || statuses.size() != DemoClusterTopology.EXPECTED_MEMBERS) {
                    DemoInitializer.sleep(environment.config.pollInterval());
                    continue;
                }
                DemoClusterTopology.verify(statuses, environment.config.yanoMemberKeys(),
                        environment.config.yanoThreshold(), environment.config.stateMachine());
                String root = statuses.getFirst().stateRoot();
                long height = statuses.getFirst().height();
                boolean sameSnapshot = statuses.stream().allMatch(status ->
                        status.height() == height && status.stateRoot().equals(root));
                boolean presence = evidence.getFirst().isPresent();
                boolean sameEvidence = evidence.stream().allMatch(candidate ->
                        candidate.isPresent() == presence
                                && (!presence || sameBusinessState(
                                evidence.getFirst().orElseThrow(), candidate.orElseThrow())));
                if (sameSnapshot && sameEvidence) {
                    return evidence.getFirst();
                }
            } catch (DemoException failure) {
                if (failure.error() == DemoError.CLUSTER_DIVERGED) {
                    throw new DemoException(DemoError.MEMBER_STATE_DISAGREEMENT);
                }
            } catch (RuntimeException transientFailure) {
                // Query and proof snapshots can race one newly finalized block.
            }
            DemoInitializer.sleep(environment.config.pollInterval());
        }
        throw new DemoException(DemoError.MEMBER_STATE_DISAGREEMENT);
    }

    private static boolean sameBusinessState(VerifiedEvidence first, VerifiedEvidence second) {
        return first.head().equals(second.head())
                && first.record().equals(second.record())
                && first.status() == second.status()
                && Arrays.equals(first.stateRoot(), second.stateRoot())
                && Arrays.equals(first.compositeProfileDigest(), second.compositeProfileDigest());
    }

    private boolean matchesInput(EvidenceRecordV1 record, byte[] digest, long size) {
        DemoConfig config = environment.config;
        try {
            ObjectPutCommandV1 object = ObjectPutCommandV1.decode(record.objectPutCommand());
            IpfsPinCommandV1 ipfs = IpfsPinCommandV1.decode(record.ipfsPinCommand());
            byte[] objectDestination = ObjectDestinationFingerprint.compute(
                    config.s3().targetId(), config.s3().destinationBucket(),
                    config.s3().destinationPrefix(), object.destinationKey(),
                    config.s3().encryptionPolicyId(), config.s3().retentionPolicyId()).bytes();
            byte[] ipfsTarget = IpfsTargetFingerprint.compute(config.ipfs().targetId()).bytes();
            byte[] kafkaDestination = KafkaDestinationFingerprint.compute(
                    config.kafka().targetId(), config.kafka().physicalTopic()).bytes();
            return object.target().equals(config.s3().target())
                    && object.digestAlgorithm() == DigestAlgorithm.SHA_256
                    && object.size() == size
                    && Arrays.equals(object.digest(), digest)
                    && ipfs.target().equals(config.ipfs().target())
                    && record.kafkaTarget().equals(config.kafka().target())
                    && record.kafkaTopic().equals(config.kafka().topicAlias())
                    && Arrays.equals(record.expectedObjectDestinationFingerprint(),
                    objectDestination)
                    && Arrays.equals(record.expectedIpfsTargetFingerprint(), ipfsTarget)
                    && Arrays.equals(record.expectedKafkaDestinationFingerprint(),
                    kafkaDestination);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private ScenarioReport replayExisting(
            ScenarioRequest request,
            String scenarioId,
            Instant started,
            List<ScenarioReport.Check> checks,
            byte[] document,
            byte[] digest
    ) {
        YanoAuditClient primary = environment.yano.getFirst();
        VerifiedEvidence existing = agreedEvidence(
                request.evidenceId(), request.businessVersion()).orElseThrow(
                () -> new DemoException(DemoError.EVIDENCE_NOT_FOUND));
        EvidenceRecordV1 record = existing.record();
        if (!matchesInput(record, digest, document.length)) {
            throw new DemoException(DemoError.REPLAY_INPUT_MISMATCH);
        }
        YanoAuditClient.Status before = primary.status();
        long kafkaEnd = environment.kafka.endOffset();
        EvidenceCommandV1 command = storageCommand(record);
        String releaseMessageId = submitReleaseOnly(primary, command, digest,
                ObjectPutCommandV1.decode(record.objectPutCommand()).destinationKey());
        waitFinality(primary, releaseMessageId, false);
        YanoAuditClient.Status after = primary.status();
        VerifiedEvidence retained = agreedEvidence(
                request.evidenceId(), request.businessVersion()).orElseThrow(
                () -> new DemoException(DemoError.EVIDENCE_NOT_FOUND));
        if (!retained.record().equals(record)
                || !after.stateRoot().equals(before.stateRoot())
                || after.height() <= before.height()
                || environment.kafka.endOffset() != kafkaEnd) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        checks.add(pass("REPLAY_FINALIZED_AS_DETERMINISTIC_NOOP"));
        return verifyExisting(request, scenarioId, started, checks, document);
    }

    private String submitReleaseOnly(
            YanoAuditClient primary,
            EvidenceCommandV1 storageCommand,
            byte[] documentHash,
            String documentRef
    ) {
        try {
            if (!"composite".equals(environment.config.stateMachine())) {
                return primary.evidence().submit(storageCommand).messageId();
            }
            String evidenceToken = releaseToken(storageCommand.evidenceId(), null, null);
            String versionToken = releaseToken(
                    storageCommand.evidenceId(), storageCommand.businessVersion(),
                    storageCommand.encode());
            byte[] registryKey = ("evidence/" + storageCommand.evidenceId())
                    .getBytes(StandardCharsets.UTF_8);
            EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                    "release-" + versionToken, registryKey, "approval-" + versionToken,
                    "document-" + evidenceToken, documentHash,
                    "object:" + documentRef, storageCommand.encode());
            return primary.appChain().submit(
                    EvidenceReleaseCommandV1.TOPIC, release.encode()).messageId();
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SUBMISSION_FAILED);
        }
    }

    private static EvidenceCommandV1 storageCommand(EvidenceRecordV1 record) {
        if (record.businessVersion() == 1) {
            return new SubmitEvidenceCommandV1(record.evidenceId(), 1,
                    record.objectPutCommand(), record.expectedObjectDestinationFingerprint(),
                    record.ipfsPinCommand(), record.expectedIpfsTargetFingerprint(),
                    record.kafkaTarget(), record.kafkaTopic(),
                    record.expectedKafkaDestinationFingerprint());
        }
        return new RepublishEvidenceCommandV1(record.evidenceId(), record.businessVersion(),
                record.objectPutCommand(), record.expectedObjectDestinationFingerprint(),
                record.ipfsPinCommand(), record.expectedIpfsTargetFingerprint(),
                record.kafkaTarget(), record.kafkaTopic(),
                record.expectedKafkaDestinationFingerprint());
    }

    private static String releaseToken(
            String evidenceId,
            Long businessVersion,
            byte[] command
    ) {
        String material = businessVersion == null
                ? "evidence-release-v1\0" + evidenceId
                : "evidence-release-v1\0" + evidenceId + "\0" + businessVersion
                + "\0" + Digests.hex(Digests.sha256(command));
        return Digests.hex(Digests.sha256(
                material.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    }

    private ScenarioReport verifyExisting(
            ScenarioRequest request,
            String scenarioId,
            Instant started,
            List<ScenarioReport.Check> checks,
            byte[] expectedDocument
    ) {
        DemoConfig config = environment.config;
        YanoAuditClient primary = environment.yano.getFirst();
        VerifiedEvidence verified = agreedEvidence(
                request.evidenceId(), request.businessVersion()).orElseThrow(
                () -> new DemoException(DemoError.EVIDENCE_NOT_FOUND));
        EvidenceRecordV1 record = verified.record();
        if (verified.status() != EvidenceStatus.READY) {
            throw new DemoException(verified.status().permitsRepublish()
                    ? DemoError.NOTIFICATION_FAILED : DemoError.STORAGE_FAILED);
        }
        long version = record.businessVersion();
        ObjectPutCommandV1 object = ObjectPutCommandV1.decode(record.objectPutCommand());
        IpfsPinCommandV1 ipfs = IpfsPinCommandV1.decode(record.ipfsPinCommand());
        ObjectPutReceiptV1 objectReceipt = ObjectPutReceiptV1.decode(
                record.objectTerminal().externalRef());
        IpfsPinReceiptV1 ipfsReceipt = IpfsPinReceiptV1.decode(
                record.ipfsTerminal().externalRef());
        KafkaPublishReceiptV1 kafkaReceipt = KafkaPublishReceiptV1.decode(
                record.notificationTerminal().externalRef());

        byte[] objectDestination = ObjectDestinationFingerprint.compute(
                config.s3().targetId(), config.s3().destinationBucket(),
                config.s3().destinationPrefix(), object.destinationKey(),
                config.s3().encryptionPolicyId(), config.s3().retentionPolicyId()).bytes();
        byte[] ipfsTarget = IpfsTargetFingerprint.compute(config.ipfs().targetId()).bytes();
        byte[] kafkaDestination = KafkaDestinationFingerprint.compute(
                config.kafka().targetId(), config.kafka().physicalTopic()).bytes();
        CanonicalCid cid = ipfs.cid();
        if (!Arrays.equals(objectReceipt.destinationFingerprint(), objectDestination)
                || !Arrays.equals(objectReceipt.verifiedSha256(), object.digest())
                || objectReceipt.size() != object.size()
                || !Arrays.equals(ipfsReceipt.targetFingerprint(), ipfsTarget)
                || !Arrays.equals(ipfsReceipt.cidFingerprint(),
                IpfsCidFingerprint.compute(cid).bytes())
                || !Arrays.equals(kafkaReceipt.destinationFingerprint(), kafkaDestination)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        S3DemoStore.DestinationAudit objectAudit = environment.s3.verifyDestination(
                object.destinationKey(), objectReceipt);
        if (expectedDocument != null && !Arrays.equals(expectedDocument, objectAudit.bytes())) {
            throw new DemoException(request.operation() == ScenarioRequest.Operation.REPLAY
                    ? DemoError.REPLAY_INPUT_MISMATCH : DemoError.REPUBLISH_REQUIRED);
        }
        if (!environment.kubo.requiredPinPresent(cid, ipfs.recursive())) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        environment.kubo.requireContent(cid, objectAudit.bytes());
        reports.writeVerifiedContent(request.evidenceId(), version,
                objectAudit.bytes(), object.digest());
        checks.add(pass("READ_ONLY_EXTERNAL_STORAGE_VERIFIED"));

        ClusterAgreement agreement = waitForAgreement(
                request.evidenceId(), version, record);
        YanoAuditClient.Status anchoredStatus = waitForAnchor(
                agreement.committedHeight(), config.requireAnchor());
        byte[] submitMessageId = record.submitMessageId();
        byte[] notifyMessageId = record.notifyMessageId();
        boolean directContinuation = notifyMessageId == null;
        YanoAuditClient.FinalityAudit submitFinality = waitFinality(
                primary, Digests.hex(submitMessageId), config.requireAnchor());
        YanoAuditClient.FinalityAudit continuationFinality = directContinuation
                ? submitFinality
                : waitFinality(primary, Digests.hex(notifyMessageId), config.requireAnchor());
        requireFinalityMatchesCluster(agreement, submitFinality, continuationFinality);
        requireFinalityMatchesObservedAnchor(
                anchoredStatus, submitFinality, continuationFinality, config.requireAnchor());
        checks.add(pass("READ_ONLY_FINALITY_AND_STATE_PROOFS"));

        if (record.objectEffect().height() != submitFinality.messageHeight()
                || record.ipfsEffect().height() != submitFinality.messageHeight()
                || !directContinuation && record.notificationEffect().height()
                != continuationFinality.messageHeight()
                || directContinuation && !continuationFinality.certifiedStateRoots()
                .containsKey(record.notificationEffect().height())) {
            throw new DemoException(DemoError.EFFECT_PROOF_FAILED);
        }
        EffectRecord objectEffect = primary.verifyEffect(
                record.objectEffect().height(), record.objectEffect().ordinal(),
                submitFinality.messageStateRoot(), ConnectorTypes.OBJECT_PUT,
                record.objectPutCommand(), submitMessageId);
        EffectRecord ipfsEffect = primary.verifyEffect(
                record.ipfsEffect().height(), record.ipfsEffect().ordinal(),
                submitFinality.messageStateRoot(), ConnectorTypes.IPFS_PIN,
                record.ipfsPinCommand(), submitMessageId);
        EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(record);
        KafkaPublishCommandV1 publish = new KafkaPublishCommandV1(
                record.kafkaTarget(), record.kafkaTopic(), event.kafkaKey(),
                event.contentType(), event.encode(), List.of());
        EffectRecord kafkaEffect = primary.verifyEffect(
                record.notificationEffect().height(), record.notificationEffect().ordinal(),
                continuationFinality.certifiedStateRoots().get(
                        record.notificationEffect().height()),
                ConnectorTypes.KAFKA_PUBLISH, publish.encode(), notifyMessageId);
        KafkaDemoClient.VerifiedKafkaEvent kafkaAudit = environment.kafka.verifyExisting(
                kafkaReceipt, record, kafkaEffect, config.timeout());
        checks.add(pass("READ_ONLY_EFFECT_AND_KAFKA_PROOFS"));

        boolean portableAnchorLinkage = submitFinality.anchored()
                && continuationFinality.anchored();
        List<String> portableAnchorTransactions = config.requireAnchor()
                ? portableTransactionHashes(submitFinality, continuationFinality) : List.of();
        boolean portableTransactionsVisible = !portableAnchorTransactions.isEmpty()
                && waitForL1Visibility(portableAnchorTransactions);
        Map<String, YanoAuditClient.AnchorExpectation> portableExpectations =
                portableAnchorExpectations(config.requireAnchor(), config.chainId(),
                        agreement, anchoredStatus, submitFinality, continuationFinality);
        boolean portableDatumCommitmentsVerified = !portableExpectations.isEmpty()
                && waitForAnchorCommitments(portableExpectations);
        boolean memberAnchorCovered = config.requireAnchor()
                && anchoredStatus.anchoredHeight() >= agreement.committedHeight()
                && anchoredStatus.anchorTx() != null;
        String anchorTx = config.requireAnchor() ? anchoredStatus.anchorTx() : null;
        boolean memberObservedTransactionVisible = memberAnchorCovered
                && waitForL1Visibility(List.of(anchorTx));
        boolean memberObservedDatumCommitment = memberAnchorCovered
                && waitForAnchorCommitments(Map.of(anchorTx, expectation(
                config.chainId(), agreement, anchoredStatus, submitFinality)));
        checks.add(new ScenarioReport.Check(
                "BUSINESS_CLAIM_NOT_EVALUATED", "NOT_EVALUATED"));

        ScenarioReport.ChainSummary chain = new ScenarioReport.ChainSummary(
                config.chainId(), agreement.committedHeight(), agreement.stateRoot(),
                EvidenceStatus.READY.name(), agreement.memberKeys().size(),
                agreement.threshold(), agreement.memberKeys().size() * 2, 3,
                directContinuation ? 1 : 2);
        ScenarioReport.StorageSummary storage = new ScenarioReport.StorageSummary(
                Digests.hex(object.digest()), object.size(), objectAudit.versionFingerprint(),
                cid.canonicalText(), true, true);
        ScenarioReport.KafkaSummary kafka = new ScenarioReport.KafkaSummary(
                config.kafka().physicalTopic(), kafkaAudit.partition(), kafkaAudit.offset(), true);
        ScenarioReport.AnchorSummary anchor = new ScenarioReport.AnchorSummary(
                config.requireAnchor(), portableAnchorLinkage,
                portableAnchorTransactions, portableTransactionsVisible,
                portableDatumCommitmentsVerified, memberAnchorCovered,
                anchoredStatus.anchoredHeight(), anchorTx,
                memberObservedTransactionVisible, memberObservedDatumCommitment);
        checks.add(pass(request.operation() == ScenarioRequest.Operation.REPLAY
                ? "REPLAY_RESULT_VERIFIED" : "READ_ONLY_VERIFICATION_COMPLETE"));
        return new ScenarioReport(ScenarioReport.SCHEMA_VERSION, scenarioId,
                request.evidenceId(), reportOperation(request), version, false,
                request.operation() == ScenarioRequest.Operation.REPLAY ? 1 : 0,
                "PASS", started.toString(), clock.instant().toString(),
                chain, storage, kafka, anchor, checks, null);
    }

    private ScenarioReport execute(ScenarioRequest requested,
                                   String scenarioId, Instant started,
                                   List<ScenarioReport.Check> checks) {
        DemoConfig config = environment.config;
        new DemoInitializer(environment).probe();
        checks.add(pass("SERVICES_PROBED"));

        if (requested.operation() == ScenarioRequest.Operation.VERIFY) {
            return verifyExisting(requested, scenarioId, started, checks, null);
        }

        byte[] document = readSample(requested.sampleFile());
        byte[] digest = Digests.sha256(document);
        YanoAuditClient primary = environment.yano.getFirst();
        ScenarioRequest request = preflight(requested, primary, digest, document.length);
        if (request.operation() == ScenarioRequest.Operation.VERIFY) {
            return verifyExisting(request, scenarioId, started, checks, document);
        }
        if (request.operation() == ScenarioRequest.Operation.REPLAY) {
            return replayExisting(request, scenarioId, started, checks, document, digest);
        }

        long version = request.businessVersion();
        String relativeKey = request.evidenceId() + "/v" + version
                + "/inspection-certificate.bin";
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
        EvidenceCommandV1 storageCommand = request.operation() == ScenarioRequest.Operation.PUBLISH
                ? new SubmitEvidenceCommandV1(
                request.evidenceId(), version, object.encode(), objectDestination,
                ipfs.encode(), ipfsTarget, config.kafka().target(),
                config.kafka().topicAlias(), kafkaDestination)
                : new RepublishEvidenceCommandV1(
                request.evidenceId(), version, object.encode(), objectDestination,
                ipfs.encode(), ipfsTarget, config.kafka().target(),
                config.kafka().topicAlias(), kafkaDestination);

        // Open before submission: an activated result continuation may publish
        // Kafka immediately after the second storage result is incorporated.
        // Opening only after STORAGE_READY can miss that acknowledged record.
        try (KafkaDemoClient.EventWindow window = environment.kafka.openEventWindow()) {
            String acceptedMessageId = submitStorageCommandForLoad(
                    primary, config, storageCommand, digest, relativeKey);
            checks.add(pass("composite".equals(config.stateMachine())
                    ? "COMPOSITE_EVIDENCE_RELEASE_WORKFLOW"
                    : "DIRECT_EVIDENCE_SUBMISSION"));
            VerifiedEvidence storageReady = waitEvidence(primary,
                    request.evidenceId(), version,
                    status -> status == EvidenceStatus.STORAGE_READY
                            || status == EvidenceStatus.NOTIFICATION_PENDING
                            || status == EvidenceStatus.READY,
                    true);
            requireSubmittedState(storageReady.record(), storageCommand,
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
            environment.kubo.requireContent(cid, objectAudit.bytes());
            reports.writeVerifiedContent(request.evidenceId(), version,
                    objectAudit.bytes(), digest);
            checks.add(pass("EXTERNAL_STORAGE_STATE"));

            if (storageReady.record().notificationEffect() == null) {
                submitNotificationForLoad(primary, config, request.evidenceId(), version);
            }
            VerifiedEvidence ready = waitEvidence(primary,
                    request.evidenceId(), version,
                    status -> status == EvidenceStatus.READY, false);
            requireStableStorage(storageReady.record(), ready.record());

            // Freeze the terminal READY state and wait until all three L1
            // observers agree on an anchor covering it. Evidence fetched only
            // after this point is extended to the current, still-unspent
            // script-anchor head instead of relying on historical spent UTxOs.
            ClusterAgreement agreement = waitForAgreement(
                    request.evidenceId(), version, ready.record());
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
                    request.evidenceId(), request.operation().name(), version, true,
                    "composite".equals(config.stateMachine())
                            ? request.operation() == ScenarioRequest.Operation.PUBLISH ? 4 : 3
                            : 1,
                    "PASS", started.toString(), clock.instant().toString(),
                    chain, storage, kafka, anchor, checks, null);
        }
    }

    private String submitStorageCommand(
            YanoAuditClient primary,
            DemoConfig config,
            EvidenceCommandV1 storageCommand,
            byte[] documentHash,
            String documentRef
    ) {
        try {
            if (!"composite".equals(config.stateMachine())) {
                // A repeated direct submission is a deterministic no-op: the
                // authenticated record keeps the message id that originally
                // created it. Still wait for the new envelope to finalize,
                // then read that proof-bound id from state. Reading after the
                // envelope also avoids asking an empty chain to prove absence
                // for a version that has not existed yet.
                String replayMessageId = primary.evidence().submit(storageCommand).messageId();
                waitFinality(primary, replayMessageId, false);
                return primary.evidence().queryVerified(
                                storageCommand.evidenceId(), storageCommand.businessVersion())
                        .map(existing -> Digests.hex(existing.record().submitMessageId()))
                        .orElseThrow(() -> new DemoException(DemoError.STATE_PROOF_FAILED));
            }

            String evidenceToken = releaseToken(storageCommand.evidenceId(), null, null);
            String versionToken = releaseToken(
                    storageCommand.evidenceId(), storageCommand.businessVersion(),
                    storageCommand.encode());
            byte[] registryKey = ("evidence/" + storageCommand.evidenceId())
                    .getBytes(StandardCharsets.UTF_8);
            String approvalId = "approval-" + versionToken;
            byte[] evidenceCommand = storageCommand.encode();

            if (storageCommand instanceof SubmitEvidenceCommandV1) {
                AppChainClient.SubmitResult registry = primary.appChain().submit(
                        EvidenceReleasePrerequisiteCommandsV1.REGISTRY_TOPIC,
                        EvidenceReleasePrerequisiteCommandsV1.registryPut(
                                registryKey, documentHash));
                waitFinality(primary, registry.messageId(), false);
            }

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
                    storageCommand.evidenceId(), storageCommand.businessVersion());

            EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                    "release-" + versionToken,
                    registryKey,
                    approvalId,
                    "document-" + evidenceToken,
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

    private String submitStorageCommandForLoad(
            YanoAuditClient primary,
            DemoConfig config,
            EvidenceCommandV1 storageCommand,
            byte[] documentHash,
            String documentRef
    ) {
        if (loadWorkflows == null || !"composite".equals(config.stateMachine())) {
            return submitStorageCommand(
                    primary, config, storageCommand, documentHash, documentRef);
        }
        return loadWorkflows.release(() -> submitStorageCommand(
                primary, config, storageCommand, documentHash, documentRef));
    }

    private void submitNotificationForLoad(YanoAuditClient primary,
                                           DemoConfig config,
                                           String evidenceId,
                                           long businessVersion) {
        Runnable submit = () -> {
            try {
                var result = primary.evidence().notify(evidenceId, businessVersion);
                if (loadWorkflows != null && "composite".equals(config.stateMachine())) {
                    waitFinality(primary, result.messageId(), false);
                }
            } catch (DemoException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new DemoException(DemoError.NOTIFICATION_FAILED);
            }
        };
        if (loadWorkflows == null || !"composite".equals(config.stateMachine())) {
            submit.run();
        } else {
            loadWorkflows.notification(submit);
        }
    }

    private VerifiedEvidence waitEvidence(YanoAuditClient node,
                                          String evidenceId,
                                          long businessVersion,
                                          Predicate<EvidenceStatus> wanted,
                                          boolean storagePhase) {
        long deadline = System.nanoTime() + environment.config.timeout().toNanos();
        while (System.nanoTime() < deadline) {
            advanceLoadAnchor(environment.yano.getFirst());
            try {
                Optional<VerifiedEvidence> result = node.evidence().queryVerified(
                        evidenceId, businessVersion);
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

    private ClusterAgreement waitForAgreement(
            String evidenceId,
            long businessVersion,
            EvidenceRecordV1 expected
    ) {
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
                            evidenceId, businessVersion).orElseThrow();
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
            advanceLoadAnchor(environment.yano.getFirst());
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

    private void advanceLoadAnchor(YanoAuditClient leader) {
        if (loadAnchors != null && environment.config.requireAnchor()) {
            loadAnchors.advance(leader);
        }
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
                                      EvidenceCommandV1 command,
                                      String expectedKafkaTarget,
                                      String expectedKafkaTopic) {
        byte[] objectCommand;
        byte[] objectDestination;
        byte[] ipfsCommand;
        byte[] ipfsTarget;
        String kafkaTarget;
        String kafkaTopic;
        byte[] kafkaDestination;
        if (command instanceof SubmitEvidenceCommandV1 submit) {
            objectCommand = submit.objectPutCommand();
            objectDestination = submit.expectedObjectDestinationFingerprint();
            ipfsCommand = submit.ipfsPinCommand();
            ipfsTarget = submit.expectedIpfsTargetFingerprint();
            kafkaTarget = submit.kafkaTarget();
            kafkaTopic = submit.kafkaTopic();
            kafkaDestination = submit.expectedKafkaDestinationFingerprint();
        } else if (command instanceof RepublishEvidenceCommandV1 republish) {
            objectCommand = republish.objectPutCommand();
            objectDestination = republish.expectedObjectDestinationFingerprint();
            ipfsCommand = republish.ipfsPinCommand();
            ipfsTarget = republish.expectedIpfsTargetFingerprint();
            kafkaTarget = republish.kafkaTarget();
            kafkaTopic = republish.kafkaTopic();
            kafkaDestination = republish.expectedKafkaDestinationFingerprint();
        } else {
            throw new DemoException(DemoError.STATE_PROOF_FAILED);
        }
        if (!record.evidenceId().equals(command.evidenceId())
                || record.businessVersion() != command.businessVersion()
                || !Arrays.equals(record.objectPutCommand(), objectCommand)
                || !Arrays.equals(record.ipfsPinCommand(), ipfsCommand)
                || !Arrays.equals(record.expectedObjectDestinationFingerprint(),
                objectDestination)
                || !Arrays.equals(record.expectedIpfsTargetFingerprint(),
                ipfsTarget)
                || !record.kafkaTarget().equals(kafkaTarget)
                || !record.kafkaTopic().equals(kafkaTopic)
                || !kafkaTarget.equals(expectedKafkaTarget)
                || !kafkaTopic.equals(expectedKafkaTopic)
                || !Arrays.equals(record.expectedKafkaDestinationFingerprint(),
                kafkaDestination)) {
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

    private static String reportOperation(ScenarioRequest request) {
        return request.operation() == ScenarioRequest.Operation.REPLAY
                ? "REPLAY_NOOP" : request.operation().name();
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
