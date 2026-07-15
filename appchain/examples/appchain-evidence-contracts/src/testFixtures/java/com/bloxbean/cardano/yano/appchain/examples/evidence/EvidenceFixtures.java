package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;

import java.util.Arrays;

final class EvidenceFixtures {
    static final String ID = "batch-001";
    static final byte[] DIGEST = repeat(0x11);
    static final byte[] OBJECT_DESTINATION = repeat(0x21);
    static final byte[] IPFS_TARGET = repeat(0x22);
    static final byte[] KAFKA_DESTINATION = repeat(0x23);
    static final byte[] OWNER = repeat(0x41);
    static final byte[] SUBMIT_MESSAGE = repeat(0x42);
    static final byte[] NOTIFY_MESSAGE = repeat(0x43);

    private EvidenceFixtures() {
    }

    static ObjectPutCommandV1 objectCommand() {
        return new ObjectPutCommandV1(
                "archive-v1",
                "incoming/batch-001.pdf",
                "evidence/batch-001.pdf",
                DigestAlgorithm.SHA_256,
                DIGEST,
                15,
                "application/pdf",
                null);
    }

    static CanonicalCid cid() {
        byte[] bytes = new byte[36];
        bytes[0] = 1;
        bytes[1] = 0x55;
        bytes[2] = 0x12;
        bytes[3] = 0x20;
        System.arraycopy(DIGEST, 0, bytes, 4, DIGEST.length);
        return CanonicalCid.fromBytes(bytes);
    }

    static IpfsPinCommandV1 ipfsCommand() {
        return new IpfsPinCommandV1("kubo-v1", cid(), true, "single-v1");
    }

    static SubmitEvidenceCommandV1 submit() {
        return new SubmitEvidenceCommandV1(
                ID, 1,
                objectCommand().encode(), OBJECT_DESTINATION,
                ipfsCommand().encode(), IPFS_TARGET,
                "primary-v1", "evidence-ready", KAFKA_DESTINATION);
    }

    static RepublishEvidenceCommandV1 republish() {
        return new RepublishEvidenceCommandV1(
                ID, 2,
                objectCommand().encode(), OBJECT_DESTINATION,
                ipfsCommand().encode(), IPFS_TARGET,
                "primary-v1", "evidence-ready", KAFKA_DESTINATION);
    }

    static byte[] objectReceipt() {
        return new ObjectPutReceiptV1(
                OBJECT_DESTINATION, repeat(0x31), DIGEST, 15).encode();
    }

    static byte[] ipfsReceipt() {
        return new IpfsPinReceiptV1(
                IPFS_TARGET, IpfsCidFingerprint.compute(cid()).bytes()).encode();
    }

    static byte[] kafkaReceipt() {
        return new KafkaPublishReceiptV1(KAFKA_DESTINATION, 3, 42).encode();
    }

    static EvidenceTerminalResultV1 confirmed(byte[] receipt, long height) {
        return new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.CONFIRMED, receipt, null, height);
    }

    static EvidenceTerminalResultV1 failed(long height) {
        return new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.FAILED,
                "PROVIDER_REJECTED".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                null,
                height);
    }

    static EvidenceTerminalResultV1 expired(long height) {
        return new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.EXPIRED, new byte[0], null, height);
    }

    static EvidenceRecordV1 storageReadyRecord() {
        return record(confirmed(objectReceipt(), 11), confirmed(ipfsReceipt(), 12),
                null, null, null);
    }

    static EvidenceRecordV1 record(EvidenceTerminalResultV1 objectTerminal,
                                   EvidenceTerminalResultV1 ipfsTerminal,
                                   byte[] notifyMessage,
                                   EvidenceEffectRef notificationEffect,
                                   EvidenceTerminalResultV1 notificationTerminal) {
        return new EvidenceRecordV1(
                ID,
                1,
                OWNER,
                SUBMIT_MESSAGE,
                objectCommand().encode(),
                OBJECT_DESTINATION,
                new EvidenceEffectRef(10, 0),
                objectTerminal,
                ipfsCommand().encode(),
                IPFS_TARGET,
                new EvidenceEffectRef(10, 1),
                ipfsTerminal,
                "primary-v1",
                "evidence-ready",
                KAFKA_DESTINATION,
                notifyMessage,
                notificationEffect,
                notificationTerminal);
    }

    static byte[] repeat(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
