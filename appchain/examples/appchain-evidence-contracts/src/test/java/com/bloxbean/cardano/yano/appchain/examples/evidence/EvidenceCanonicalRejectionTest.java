package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceCanonicalRejectionTest {
    @Test
    void stateDecodersRejectTrailingTaggedIndefiniteAndNonPreferredForms() {
        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1);
        byte[] canonical = head.encode();
        assertInvalid(() -> EvidenceHeadV1.decode(append(canonical, (byte) 0)));
        assertInvalid(() -> EvidenceHeadV1.decode(prepend((byte) 0xc0, canonical)));

        byte[] indefinite = append(canonical.clone(), (byte) 0xff);
        indefinite[0] = (byte) 0x9f;
        assertInvalid(() -> EvidenceHeadV1.decode(indefinite));

        byte[] nonPreferredVersion = new byte[canonical.length + 1];
        nonPreferredVersion[0] = canonical[0];
        nonPreferredVersion[1] = 0x18;
        nonPreferredVersion[2] = 0x01;
        System.arraycopy(canonical, 2, nonPreferredVersion, 3, canonical.length - 2);
        assertInvalid(() -> EvidenceHeadV1.decode(nonPreferredVersion));

        byte[] record = EvidenceFixtures.storageReadyRecord().encode();
        assertInvalid(() -> EvidenceRecordV1.decode(append(record, (byte) 0)));
        assertInvalid(() -> EvidenceRecordV1.decode(prepend((byte) 0xc0, record)));
    }

    @Test
    void unknownSchemaVersionsRemainDistinctFromBusinessVersions() {
        byte[] request = new EvidenceGetRequestV1(EvidenceFixtures.ID, 42).encode();
        request[1] = 2;
        assertThatThrownBy(() -> EvidenceGetRequestV1.decode(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNSUPPORTED_VERSION");

        assertThat(new EvidenceGetRequestV1(EvidenceFixtures.ID, Long.MAX_VALUE)
                .businessVersion()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void terminalAndEffectReferencesEnforceFrozenContractBounds() {
        assertInvalid(() -> new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.CONFIRMED, new byte[129], null, 1));
        assertInvalid(() -> new EvidenceTerminalResultV1(
                EvidenceTerminalOutcome.CONFIRMED, new byte[0], new byte[31], 1));
        assertInvalid(() -> new EvidenceEffectRef(0, 0));
        assertInvalid(() -> new EvidenceEffectRef(1, -1));

        byte[] terminal = EvidenceFixtures.failed(11).encode();
        terminal[0] = (byte) 0x85;
        assertInvalid(() -> EvidenceTerminalResultV1.decode(terminal));
    }

    @Test
    void responseAndEventRejectOversizeBeforeDecoding() {
        assertInvalid(() -> EvidenceGetResponseV1.decode(
                new byte[EvidenceContract.MAX_QUERY_RESPONSE_BYTES + 1]));
        assertInvalid(() -> EvidenceAvailableEventV1.decode(
                new byte[EvidenceAvailableEventV1.MAX_ENCODED_BYTES + 1]));
    }

    @Test
    void wrongExpectedDestinationCommitmentCannotBecomeReady() {
        EvidenceTerminalResultV1 object = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 11);
        EvidenceTerminalResultV1 ipfs = EvidenceFixtures.confirmed(
                EvidenceFixtures.ipfsReceipt(), 12);
        EvidenceRecordV1 record = new EvidenceRecordV1(
                EvidenceFixtures.ID, 1, EvidenceFixtures.OWNER,
                EvidenceFixtures.SUBMIT_MESSAGE,
                EvidenceFixtures.objectCommand().encode(), EvidenceFixtures.repeat(0x70),
                new EvidenceEffectRef(10, 0), object,
                EvidenceFixtures.ipfsCommand().encode(), EvidenceFixtures.IPFS_TARGET,
                new EvidenceEffectRef(10, 1), ipfs,
                "primary-v1", "evidence-ready", EvidenceFixtures.KAFKA_DESTINATION,
                null, null, null);

        assertThat(EvidenceStatus.derive(record)).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void recordRejectsStructurallyImpossibleNotificationFields() {
        EvidenceTerminalResultV1 object = EvidenceFixtures.confirmed(
                EvidenceFixtures.objectReceipt(), 11);
        EvidenceTerminalResultV1 ipfs = EvidenceFixtures.confirmed(
                EvidenceFixtures.ipfsReceipt(), 12);
        EvidenceRecordV1 directContinuation = EvidenceFixtures.record(
                object, ipfs, null, new EvidenceEffectRef(12, 0), null);
        assertThat(EvidenceRecordV1.decode(directContinuation.encode()))
                .isEqualTo(directContinuation);
        assertThat(directContinuation.notifyMessageId()).isNull();
        assertThat(directContinuation.notificationEffect())
                .isEqualTo(new EvidenceEffectRef(12, 0));
        assertInvalid(() -> EvidenceFixtures.record(
                object, ipfs, EvidenceFixtures.NOTIFY_MESSAGE, null, null));
        assertInvalid(() -> EvidenceFixtures.record(
                object, ipfs, null, null, EvidenceFixtures.failed(13)));
    }

    @Test
    void mutableCallerInputsCannotAlterEncodedRecord() {
        byte[] owner = EvidenceFixtures.OWNER.clone();
        byte[] message = EvidenceFixtures.SUBMIT_MESSAGE.clone();
        EvidenceRecordV1 record = new EvidenceRecordV1(
                EvidenceFixtures.ID, 1, owner, message,
                EvidenceFixtures.objectCommand().encode(), EvidenceFixtures.OBJECT_DESTINATION,
                new EvidenceEffectRef(10, 0), null,
                EvidenceFixtures.ipfsCommand().encode(), EvidenceFixtures.IPFS_TARGET,
                new EvidenceEffectRef(10, 1), null,
                "primary-v1", "evidence-ready", EvidenceFixtures.KAFKA_DESTINATION,
                null, null, null);
        byte[] original = record.encode();
        Arrays.fill(owner, (byte) 0);
        Arrays.fill(message, (byte) 0);
        assertThat(record.encode()).isEqualTo(original);
    }

    private static void assertInvalid(Runnable runnable) {
        assertThatThrownBy(runnable::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] append(byte[] source, byte value) {
        byte[] result = Arrays.copyOf(source, source.length + 1);
        result[result.length - 1] = value;
        return result;
    }

    private static byte[] prepend(byte value, byte[] source) {
        byte[] result = new byte[source.length + 1];
        result[0] = value;
        System.arraycopy(source, 0, result, 1, source.length);
        return result;
    }
}
