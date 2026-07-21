package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;

import java.util.Arrays;

/** Business status derived only from immutable record fields and validated receipts. */
public enum EvidenceStatus {
    /** One or both independent storage results have not arrived. */
    STORAGE_PENDING,
    /** Both storage operations have matching confirmed receipts. */
    STORAGE_READY,
    /** Exactly one storage operation has a matching confirmed receipt. */
    PARTIAL,
    /** Both storage operations are terminal and neither is validly confirmed. */
    STORAGE_FAILED,
    /** A storage-stage effect expired. */
    EXPIRED,
    /** The storage-ready version has emitted its Kafka effect. */
    NOTIFICATION_PENDING,
    /** Storage and notification have matching confirmed receipts. */
    READY,
    /** Storage is ready but notification failed, was cancelled, or had a bad receipt. */
    READY_NOTIFICATION_FAILED,
    /** Storage is ready but the notification effect expired. */
    READY_NOTIFICATION_EXPIRED;

    /**
     * Derives status without throwing, including when committed external references
     * are malformed or semantically mismatched.
     */
    public static EvidenceStatus derive(EvidenceRecordV1 record) {
        if (record == null) {
            return STORAGE_FAILED;
        }
        EvidenceTerminalResultV1 object = record.objectTerminal();
        EvidenceTerminalResultV1 ipfs = record.ipfsTerminal();
        if (object == null || ipfs == null) {
            return STORAGE_PENDING;
        }

        boolean objectConfirmed = validObjectConfirmation(record, object);
        boolean ipfsConfirmed = validIpfsConfirmation(record, ipfs);
        if (!objectConfirmed || !ipfsConfirmed) {
            if (objectConfirmed != ipfsConfirmed) {
                return PARTIAL;
            }
            if (object.outcome() == EvidenceTerminalOutcome.EXPIRED
                    || ipfs.outcome() == EvidenceTerminalOutcome.EXPIRED) {
                return EXPIRED;
            }
            return STORAGE_FAILED;
        }

        if (record.notificationEffect() == null) {
            return STORAGE_READY;
        }
        EvidenceTerminalResultV1 notification = record.notificationTerminal();
        if (notification == null) {
            return NOTIFICATION_PENDING;
        }
        if (notification.outcome() == EvidenceTerminalOutcome.EXPIRED) {
            return READY_NOTIFICATION_EXPIRED;
        }
        return validKafkaConfirmation(record, notification)
                ? READY : READY_NOTIFICATION_FAILED;
    }

    /** Returns whether both storage confirmations are exact and mutually bound. */
    public static boolean storageReady(EvidenceRecordV1 record) {
        return record != null
                && validObjectConfirmation(record, record.objectTerminal())
                && validIpfsConfirmation(record, record.ipfsTerminal());
    }

    /** Returns whether an owner may create the exact next business version. */
    public boolean permitsRepublish() {
        return switch (this) {
            case STORAGE_PENDING, STORAGE_READY, NOTIFICATION_PENDING -> false;
            case PARTIAL, STORAGE_FAILED, EXPIRED, READY,
                    READY_NOTIFICATION_FAILED, READY_NOTIFICATION_EXPIRED -> true;
        };
    }

    private static boolean validObjectConfirmation(EvidenceRecordV1 record,
                                                   EvidenceTerminalResultV1 terminal) {
        return validObjectConfirmation(record.objectPutCommand(),
                record.expectedObjectDestinationFingerprint(), terminal);
    }

    static boolean validObjectConfirmation(byte[] objectCommand,
                                           byte[] expectedDestination,
                                           EvidenceTerminalResultV1 terminal) {
        if (terminal == null
                || terminal.outcome() != EvidenceTerminalOutcome.CONFIRMED) {
            return false;
        }
        try {
            ObjectPutReceiptV1 receipt = ObjectPutReceiptV1.decode(terminal.externalRef());
            ObjectPutCommandV1 command = ObjectPutCommandV1.decode(objectCommand);
            return receipt.size() == command.size()
                    && Arrays.equals(receipt.destinationFingerprint(),
                    expectedDestination)
                    && Arrays.equals(receipt.verifiedSha256(), command.digest());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean validIpfsConfirmation(EvidenceRecordV1 record,
                                                 EvidenceTerminalResultV1 terminal) {
        return validIpfsConfirmation(record.ipfsPinCommand(),
                record.expectedIpfsTargetFingerprint(), terminal);
    }

    static boolean validIpfsConfirmation(byte[] ipfsCommand,
                                         byte[] expectedTarget,
                                         EvidenceTerminalResultV1 terminal) {
        if (terminal == null
                || terminal.outcome() != EvidenceTerminalOutcome.CONFIRMED) {
            return false;
        }
        try {
            IpfsPinReceiptV1 receipt = IpfsPinReceiptV1.decode(terminal.externalRef());
            IpfsPinCommandV1 command = IpfsPinCommandV1.decode(ipfsCommand);
            byte[] expected = IpfsCidFingerprint.compute(command.cid()).bytes();
            return Arrays.equals(receipt.targetFingerprint(),
                    expectedTarget)
                    && Arrays.equals(receipt.cidFingerprint(), expected);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean validKafkaConfirmation(EvidenceRecordV1 record,
                                                  EvidenceTerminalResultV1 terminal) {
        if (terminal.outcome() != EvidenceTerminalOutcome.CONFIRMED) {
            return false;
        }
        try {
            KafkaPublishReceiptV1 receipt = KafkaPublishReceiptV1.decode(
                    terminal.externalRef());
            return Arrays.equals(receipt.destinationFingerprint(),
                    record.expectedKafkaDestinationFingerprint());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
