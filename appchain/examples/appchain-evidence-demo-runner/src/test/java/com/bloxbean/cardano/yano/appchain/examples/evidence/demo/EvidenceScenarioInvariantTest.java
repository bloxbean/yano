package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceScenarioInvariantTest {
    private static final byte[] HASH_A = bytes(0x11);
    private static final byte[] HASH_B = bytes(0x22);
    private static final byte[] HASH_C = bytes(0x33);
    private static final byte[] OWNER = bytes(0x44);
    private static final byte[] SUBMIT_ID = bytes(0x55);
    private static final byte[] NOTIFY_ID = bytes(0x66);
    private static final CanonicalCid CID = CanonicalCid.fromText(
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi");

    @Test
    void bindsSubmittedCommandsDestinationsAndImmutableStorageAcrossContinuation() {
        ObjectPutCommandV1 object = objectCommand();
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CID, true, "demo-single");
        SubmitEvidenceCommandV1 submit = new SubmitEvidenceCommandV1(
                "sample-1", 1, object.encode(), HASH_A, ipfs.encode(), HASH_B,
                "primary", "evidence-ready", HASH_C);
        EvidenceRecordV1 before = state(object, ipfs, null, null);
        EvidenceTerminalResultV1 kafkaTerminal = new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.CONFIRMED,
                new KafkaPublishReceiptV1(HASH_C, 0, 7).encode(), null, 15);
        EvidenceRecordV1 after = state(object, ipfs,
                new EvidenceEffectRef(14, 0), kafkaTerminal);

        assertThatCode(() -> EvidenceScenario.requireSubmittedState(
                before, submit, "primary", "evidence-ready"))
                .doesNotThrowAnyException();
        assertThatCode(() -> EvidenceScenario.requireSubmittedState(
                after, submit, "primary", "evidence-ready"))
                .doesNotThrowAnyException();
        assertThatCode(() -> EvidenceScenario.requireStableStorage(before, after))
                .doesNotThrowAnyException();

        SubmitEvidenceCommandV1 wrong = new SubmitEvidenceCommandV1(
                "sample-1", 1, object.encode(), HASH_C, ipfs.encode(), HASH_B,
                "primary", "evidence-ready", HASH_C);
        assertThatThrownBy(() -> EvidenceScenario.requireSubmittedState(
                before, wrong, "primary", "evidence-ready"))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.STATE_PROOF_FAILED);

        SubmitEvidenceCommandV1 changedTarget = new SubmitEvidenceCommandV1(
                "sample-1", 1, object.encode(), HASH_A, ipfs.encode(), HASH_B,
                "secondary", "evidence-ready", HASH_C);
        assertThatThrownBy(() -> EvidenceScenario.requireSubmittedState(
                before, changedTarget, "secondary", "evidence-ready"))
                .isInstanceOf(DemoException.class);
        assertThatThrownBy(() -> EvidenceScenario.requireSubmittedState(
                before, submit, "secondary", "evidence-ready"))
                .isInstanceOf(DemoException.class);

        SubmitEvidenceCommandV1 changedTopic = new SubmitEvidenceCommandV1(
                "sample-1", 1, object.encode(), HASH_A, ipfs.encode(), HASH_B,
                "primary", "evidence-v2", HASH_C);
        assertThatThrownBy(() -> EvidenceScenario.requireSubmittedState(
                before, changedTopic, "primary", "evidence-v2"))
                .isInstanceOf(DemoException.class);

        EvidenceRecordV1 changed = new EvidenceRecordV1(after.evidenceId(), after.businessVersion(),
                after.ownerPublicKey(), after.submitMessageId(), after.objectPutCommand(),
                after.expectedObjectDestinationFingerprint(), after.objectEffect(),
                new EvidenceTerminalResultV1(EvidenceTerminalOutcome.CONFIRMED,
                        new ObjectPutReceiptV1(HASH_A, bytes(0x77), object.digest(),
                                object.size()).encode(), null, 13),
                after.ipfsPinCommand(), after.expectedIpfsTargetFingerprint(),
                after.ipfsEffect(), after.ipfsTerminal(), after.kafkaTarget(), after.kafkaTopic(),
                after.expectedKafkaDestinationFingerprint(), after.notifyMessageId(),
                after.notificationEffect(), after.notificationTerminal());
        assertThatThrownBy(() -> EvidenceScenario.requireStableStorage(before, changed))
                .isInstanceOf(DemoException.class);
    }

    @Test
    void bindsBothPortableBundlesToObservedMembersAndThreshold() {
        Set<String> members = Set.of(hex(1), hex(2), hex(3));
        EvidenceScenario.ClusterAgreement agreement = new EvidenceScenario.ClusterAgreement(
                12, hex(9), members, 2);
        YanoAuditClient.FinalityAudit valid = finality(2, 2, members);

        assertThatCode(() -> EvidenceScenario.requireFinalityMatchesCluster(
                agreement, valid, valid)).doesNotThrowAnyException();
        assertStateProofFailure(agreement, finality(1, 2, members), valid);
        assertStateProofFailure(agreement, valid, finality(1, 2, members));
        assertStateProofFailure(agreement, finality(2, 1, members), valid);
        assertStateProofFailure(agreement, finality(2, 2,
                Set.of(hex(1), hex(2), hex(4))), valid);
        YanoAuditClient.FinalityAudit wrongCertifiedRoot = new YanoAuditClient.FinalityAudit(
                2, 2, members, true, 12, hex(8), 100, hex(7), hex(9),
                10, hex(9), Map.of(12L, hex(4)));
        assertStateProofFailure(agreement, wrongCertifiedRoot, valid);
    }

    @Test
    void extractsEveryDistinctPortableAnchorAndRejectsInconsistentClaims() {
        Set<String> members = Set.of(hex(1), hex(2), hex(3));
        YanoAuditClient.FinalityAudit submit = finality(2, 2, members, hex(7));
        YanoAuditClient.FinalityAudit notify = finality(2, 2, members, hex(8));

        assertThat(EvidenceScenario.portableTransactionHashes(submit, notify))
                .containsExactly(hex(7), hex(8));
        assertThat(EvidenceScenario.portableTransactionHashes(submit, submit))
                .containsExactly(hex(7));
        EvidenceScenario.ClusterAgreement agreement = new EvidenceScenario.ClusterAgreement(
                12, hex(9), members, 2);
        assertThat(EvidenceScenario.portableAnchorExpectations(false,
                "evidence-chain", agreement, null, submit, notify)).isEmpty();

        YanoAuditClient.FinalityAudit inconsistent = new YanoAuditClient.FinalityAudit(
                2, 2, members, false, 0, hex(7), 0, null, null,
                10, hex(9), Map.of(12L, hex(9)));
        assertThatThrownBy(() -> EvidenceScenario.portableTransactionHashes(
                submit, inconsistent)).isInstanceOf(DemoException.class);
    }

    private static void assertStateProofFailure(
            EvidenceScenario.ClusterAgreement agreement,
            YanoAuditClient.FinalityAudit submit,
            YanoAuditClient.FinalityAudit notify) {
        assertThatThrownBy(() -> EvidenceScenario.requireFinalityMatchesCluster(
                agreement, submit, notify))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.STATE_PROOF_FAILED);
    }

    private static YanoAuditClient.FinalityAudit finality(
            int signatures, int threshold, Set<String> members) {
        return finality(signatures, threshold, members, hex(8));
    }

    private static YanoAuditClient.FinalityAudit finality(
            int signatures, int threshold, Set<String> members, String anchorTx) {
        return new YanoAuditClient.FinalityAudit(signatures, threshold, members,
                true, 12, anchorTx, 100, hex(7), hex(9),
                10, hex(9), Map.of(12L, hex(9)));
    }

    private static EvidenceRecordV1 state(ObjectPutCommandV1 object, IpfsPinCommandV1 ipfs,
                                          EvidenceEffectRef notification,
                                          EvidenceTerminalResultV1 notificationTerminal) {
        byte[] objectReceipt = new ObjectPutReceiptV1(
                HASH_A, bytes(0x70), object.digest(), object.size()).encode();
        byte[] ipfsReceipt = new IpfsPinReceiptV1(
                HASH_B, IpfsCidFingerprint.compute(CID).bytes()).encode();
        return new EvidenceRecordV1("sample-1", 1, OWNER, SUBMIT_ID,
                object.encode(), HASH_A, new EvidenceEffectRef(10, 0),
                new EvidenceTerminalResultV1(EvidenceTerminalOutcome.CONFIRMED,
                        objectReceipt, null, 12),
                ipfs.encode(), HASH_B, new EvidenceEffectRef(10, 1),
                new EvidenceTerminalResultV1(EvidenceTerminalOutcome.CONFIRMED,
                        ipfsReceipt, null, 12),
                "primary", "evidence-ready", HASH_C,
                notification == null ? null : NOTIFY_ID, notification, notificationTerminal);
    }

    private static ObjectPutCommandV1 objectCommand() {
        return new ObjectPutCommandV1("archive", "sample-1/certificate.bin",
                "sample-1/certificate.bin", DigestAlgorithm.SHA_256, bytes(0x09),
                32, "application/octet-stream", null);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static String hex(int value) {
        return "%02x".formatted(value).repeat(32);
    }
}
