package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectOperation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceScope;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceStateContractTest {
    @Test
    void terminalOutcomeCodesAndEffectOrdinalBoundAreFrozenInEvidenceV1() {
        assertThat(Arrays.stream(EvidenceTerminalOutcome.values())
                .map(EvidenceTerminalOutcome::code)
                .toList())
                .containsExactly(1, 2, 3, 4);
        for (EvidenceTerminalOutcome outcome : EvidenceTerminalOutcome.values()) {
            assertThat(EvidenceTerminalOutcome.fromCode(outcome.code())).isEqualTo(outcome);
        }
        assertThatThrownBy(() -> EvidenceTerminalOutcome.fromCode(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EvidenceContract.MAX_EFFECTS_PER_BLOCK).isEqualTo(1_048_576);
    }

    @Test
    void stateKeysScopesAndKafkaKeysAreStableAndReversible() {
        byte[] hash = EvidenceKeys.idHash(EvidenceFixtures.ID);
        assertThat(hash).hasSize(32);
        assertThat(EvidenceKeys.headKey(EvidenceFixtures.ID))
                .startsWith("evidence/head/v1/".getBytes(StandardCharsets.US_ASCII));
        assertThat(EvidenceKeys.recordKey(EvidenceFixtures.ID, 42))
                .isEqualTo(EvidenceKeys.recordKey(hash, 42));
        assertThat(EvidenceKeys.kafkaKey(EvidenceFixtures.ID, 42))
                .containsExactly(concat(hash, longBytes(42)));

        String encoded = EvidenceKeys.effectScope(
                EvidenceFixtures.ID, 42, EvidenceEffectOperation.IPFS);
        EvidenceScope parsed = EvidenceScope.parse(encoded);
        assertThat(parsed.evidenceIdHash()).isEqualTo(hash);
        assertThat(parsed.businessVersion()).isEqualTo(42);
        assertThat(parsed.operation()).isEqualTo(EvidenceEffectOperation.IPFS);
        assertThat(parsed.recordKey()).isEqualTo(EvidenceKeys.recordKey(hash, 42));
        assertThat(parsed.encode()).isEqualTo(encoded);
    }

    @Test
    void scopeParserRejectsAmbiguousOrNonCanonicalText() {
        String canonical = EvidenceKeys.effectScope(
                EvidenceFixtures.ID, 1, EvidenceEffectOperation.OBJECT);
        assertInvalid(() -> EvidenceScope.parse(canonical.replace("/1/", "/01/")));
        assertInvalid(() -> EvidenceScope.parse(canonical.replace("/1/", "/0/")));
        assertInvalid(() -> EvidenceScope.parse(canonical.toUpperCase()));
        assertInvalid(() -> EvidenceScope.parse(canonical + "/extra"));
        assertInvalid(() -> EvidenceScope.parse(canonical.replace("/object", "/unknown")));
        assertInvalid(() -> EvidenceKeys.recordKey(new byte[31], 1));
    }

    @Test
    void headTerminalAndRecordRoundTripWithoutLosingExactTerminalBytes() {
        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1);
        assertThat(EvidenceHeadV1.decode(head.encode())).isEqualTo(head);

        byte[] detailHash = EvidenceFixtures.repeat(0x55);
        EvidenceTerminalResultV1 terminal = new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.FAILED, new byte[]{1, 2, 3}, detailHash, 11);
        assertThat(EvidenceTerminalResultV1.decode(terminal.encode())).isEqualTo(terminal);

        EvidenceRecordV1 record = EvidenceFixtures.storageReadyRecord();
        assertThat(EvidenceRecordV1.decode(record.encode())).isEqualTo(record);
        assertThat(record.encode()).hasSizeLessThanOrEqualTo(EvidenceContract.MAX_RECORD_BYTES);
    }

    @Test
    void storageStatusesCoverBothResultOrdersFailuresExpiryAndBadReceipts() {
        EvidenceTerminalResultV1 object = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 11);
        EvidenceTerminalResultV1 ipfs = EvidenceFixtures.confirmed(
                EvidenceFixtures.ipfsReceipt(), 12);

        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                null, null, null, null, null))).isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, null, null, null, null))).isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                null, ipfs, null, null, null))).isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, null, null, null))).isEqualTo(EvidenceStatus.STORAGE_READY);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, EvidenceFixtures.failed(12), null, null, null)))
                .isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, EvidenceFixtures.expired(12), null, null, null)))
                .isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                EvidenceFixtures.failed(11), EvidenceFixtures.failed(12), null, null, null)))
                .isEqualTo(EvidenceStatus.STORAGE_FAILED);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                EvidenceFixtures.expired(11), EvidenceFixtures.failed(12), null, null, null)))
                .isEqualTo(EvidenceStatus.EXPIRED);

        EvidenceTerminalResultV1 malformedConfirmed = EvidenceFixtures.confirmed(
                new byte[]{1, 2, 3}, 11);
        EvidenceRecordV1 malformed = EvidenceFixtures.record(
                malformedConfirmed, ipfs, null, null, null);
        assertThatCode(() -> EvidenceStatus.derive(malformed)).doesNotThrowAnyException();
        assertThat(EvidenceStatus.derive(malformed)).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void notificationStatusesPreserveStorageReadinessAndBindKafkaDestination() {
        EvidenceTerminalResultV1 object = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 11);
        EvidenceTerminalResultV1 ipfs = EvidenceFixtures.confirmed(
                EvidenceFixtures.ipfsReceipt(), 12);
        EvidenceEffectRef notification = new EvidenceEffectRef(12, 0);

        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, notification, null)))
                .isEqualTo(EvidenceStatus.NOTIFICATION_PENDING);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, notification,
                EvidenceFixtures.confirmed(EvidenceFixtures.kafkaReceipt(), 13))))
                .isEqualTo(EvidenceStatus.READY);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, notification,
                EvidenceFixtures.failed(13))))
                .isEqualTo(EvidenceStatus.READY_NOTIFICATION_FAILED);
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, notification,
                EvidenceFixtures.expired(13))))
                .isEqualTo(EvidenceStatus.READY_NOTIFICATION_EXPIRED);

        byte[] wrongDestinationReceipt = new KafkaPublishReceiptV1(
                EvidenceFixtures.repeat(0x7f), 3, 42).encode();
        assertThat(EvidenceStatus.derive(EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, notification,
                EvidenceFixtures.confirmed(wrongDestinationReceipt, 13))))
                .isEqualTo(EvidenceStatus.READY_NOTIFICATION_FAILED);
    }

    @Test
    void recordFreezesEffectCausalityAndNotificationReadiness() {
        EvidenceTerminalResultV1 object = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 11);
        EvidenceTerminalResultV1 ipfs = EvidenceFixtures.confirmed(
                EvidenceFixtures.ipfsReceipt(), 12);

        assertInvalid(() -> recordWithRefs(
                new EvidenceEffectRef(10, 0), new EvidenceEffectRef(11, 1),
                object, ipfs, null, null, null));
        assertInvalid(() -> recordWithRefs(
                new EvidenceEffectRef(10, 0), new EvidenceEffectRef(10, 0),
                object, ipfs, null, null, null));
        assertInvalid(() -> EvidenceFixtures.record(
                object, EvidenceFixtures.failed(12), EvidenceFixtures.NOTIFY_MESSAGE,
                new EvidenceEffectRef(12, 0), null));
        assertInvalid(() -> EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE,
                new EvidenceEffectRef(11, 0), null));
        assertInvalid(() -> new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.CONFIRMED, new byte[0], null, 0));
        assertInvalid(() -> new EvidenceEffectRef(
                1, EvidenceContract.MAX_EFFECTS_PER_BLOCK));

        EvidenceTerminalResultV1 tooEarly = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 10);
        assertInvalid(() -> EvidenceFixtures.record(tooEarly, ipfs, null, null, null));
    }

    @Test
    void stateByteArraysAreDefensivelyCopied() {
        EvidenceRecordV1 record = EvidenceFixtures.storageReadyRecord();
        byte[] owner = record.ownerPublicKey();
        Arrays.fill(owner, (byte) 0);
        assertThat(record.ownerPublicKey()).isEqualTo(EvidenceFixtures.OWNER);

        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1);
        byte[] key = head.ownerPublicKey();
        key[0] = 0;
        assertThat(head.ownerPublicKey()).isEqualTo(EvidenceFixtures.OWNER);
    }

    @Test
    void republishPolicyAcceptsOnlyGenuinelyTerminalBusinessStatuses() {
        assertThat(EvidenceStatus.STORAGE_PENDING.permitsRepublish()).isFalse();
        assertThat(EvidenceStatus.STORAGE_READY.permitsRepublish()).isFalse();
        assertThat(EvidenceStatus.NOTIFICATION_PENDING.permitsRepublish()).isFalse();

        assertThat(EvidenceStatus.PARTIAL.permitsRepublish()).isTrue();
        assertThat(EvidenceStatus.STORAGE_FAILED.permitsRepublish()).isTrue();
        assertThat(EvidenceStatus.EXPIRED.permitsRepublish()).isTrue();
        assertThat(EvidenceStatus.READY.permitsRepublish()).isTrue();
        assertThat(EvidenceStatus.READY_NOTIFICATION_FAILED.permitsRepublish()).isTrue();
        assertThat(EvidenceStatus.READY_NOTIFICATION_EXPIRED.permitsRepublish()).isTrue();
    }

    private static EvidenceRecordV1 recordWithRefs(EvidenceEffectRef objectEffect,
                                                   EvidenceEffectRef ipfsEffect,
                                                   EvidenceTerminalResultV1 objectTerminal,
                                                   EvidenceTerminalResultV1 ipfsTerminal,
                                                   byte[] notifyMessage,
                                                   EvidenceEffectRef notificationEffect,
                                                   EvidenceTerminalResultV1 notificationTerminal) {
        return new EvidenceRecordV1(
                EvidenceFixtures.ID, 1, EvidenceFixtures.OWNER,
                EvidenceFixtures.SUBMIT_MESSAGE,
                EvidenceFixtures.objectCommand().encode(), EvidenceFixtures.OBJECT_DESTINATION,
                objectEffect, objectTerminal,
                EvidenceFixtures.ipfsCommand().encode(), EvidenceFixtures.IPFS_TARGET,
                ipfsEffect, ipfsTerminal,
                "primary-v1", "evidence-ready", EvidenceFixtures.KAFKA_DESTINATION,
                notifyMessage, notificationEffect, notificationTerminal);
    }

    private static byte[] longBytes(long value) {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void assertInvalid(Runnable runnable) {
        assertThatThrownBy(runnable::run).isInstanceOf(IllegalArgumentException.class);
    }
}
