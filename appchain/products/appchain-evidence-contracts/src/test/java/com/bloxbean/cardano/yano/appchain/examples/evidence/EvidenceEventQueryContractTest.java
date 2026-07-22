package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceEventQueryContractTest {
    @Test
    void storageReadyRecordProducesAReceiptBoundCanonicalEvent() {
        EvidenceRecordV1 record = EvidenceFixtures.storageReadyRecord();
        EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(record);

        assertThat(event.evidenceId()).isEqualTo(EvidenceFixtures.ID);
        assertThat(event.businessVersion()).isEqualTo(1);
        assertThat(event.digest()).isEqualTo(EvidenceFixtures.DIGEST);
        assertThat(event.cid()).isEqualTo(EvidenceFixtures.cid());
        assertThat(event.objectReceipt()).isEqualTo(EvidenceFixtures.objectReceipt());
        assertThat(event.ipfsReceipt()).isEqualTo(EvidenceFixtures.ipfsReceipt());
        assertThat(event.contentType()).isEqualTo("application/cbor");
        assertThat(event.kafkaKey()).isEqualTo(EvidenceKeys.kafkaKey(EvidenceFixtures.ID, 1));
        assertThat(EvidenceAvailableEventV1.decode(event.encode())).isEqualTo(event);
    }

    @Test
    void eventRejectsReceiptOrDocumentCommitmentMismatch() {
        EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(
                EvidenceFixtures.storageReadyRecord());
        byte[] differentDigest = EvidenceFixtures.repeat(0x66);
        assertInvalid(() -> new EvidenceAvailableEventV1(
                event.evidenceId(), event.businessVersion(), DigestAlgorithm.SHA_256,
                differentDigest, event.size(), event.objectTarget(), event.destinationKey(),
                event.cid(), event.objectReceipt(), event.ipfsReceipt()));

        byte[] damagedReceipt = event.ipfsReceipt();
        damagedReceipt[damagedReceipt.length - 1] ^= 1;
        assertInvalid(() -> new EvidenceAvailableEventV1(
                event.evidenceId(), event.businessVersion(), event.digestAlgorithm(),
                event.digest(), event.size(), event.objectTarget(), event.destinationKey(),
                event.cid(), event.objectReceipt(), damagedReceipt));

        assertInvalid(() -> EvidenceAvailableEventV1.fromRecord(EvidenceFixtures.record(
                null, null, null, null, null)));
    }

    @Test
    void queryRequestSupportsLatestSentinelAndExplicitVersion() {
        EvidenceGetRequestV1 latest = new EvidenceGetRequestV1(EvidenceFixtures.ID, 0);
        EvidenceGetRequestV1 explicit = new EvidenceGetRequestV1(EvidenceFixtures.ID, 42);
        assertThat(latest.latest()).isTrue();
        assertThat(explicit.latest()).isFalse();
        assertThat(EvidenceGetRequestV1.decode(latest.encode())).isEqualTo(latest);
        assertThat(EvidenceGetRequestV1.decode(explicit.encode())).isEqualTo(explicit);
        assertInvalid(() -> new EvidenceGetRequestV1(EvidenceFixtures.ID, -1));
    }

    @Test
    void foundQueryResponseCarriesAndBindsExactTrieKeysAndValues() {
        EvidenceRecordV1 record = EvidenceFixtures.storageReadyRecord();
        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 2);
        EvidenceGetResponseV1 response = EvidenceGetResponseV1.found(head, record);

        assertThat(response.found()).isTrue();
        assertThat(response.headKey()).isEqualTo(EvidenceKeys.headKey(EvidenceFixtures.ID));
        assertThat(response.headValue()).isEqualTo(head.encode());
        assertThat(response.recordKey()).isEqualTo(EvidenceKeys.recordKey(EvidenceFixtures.ID, 1));
        assertThat(response.recordValue()).isEqualTo(record.encode());
        assertThat(response.head()).isEqualTo(head);
        assertThat(response.record()).isEqualTo(record);
        assertThat(EvidenceGetResponseV1.decode(response.encode())).isEqualTo(response);
    }

    @Test
    void responseRejectsWrongKeysCrossOwnerRecordsAndAmbiguousAbsence() {
        EvidenceRecordV1 record = EvidenceFixtures.storageReadyRecord();
        EvidenceHeadV1 head = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.OWNER, 1);
        EvidenceGetResponseV1 valid = EvidenceGetResponseV1.found(head, record);

        byte[] wrongHeadKey = valid.headKey();
        wrongHeadKey[wrongHeadKey.length - 1] ^= 1;
        assertInvalid(() -> new EvidenceGetResponseV1(
                true, wrongHeadKey, valid.headValue(), valid.recordKey(), valid.recordValue()));

        EvidenceHeadV1 wrongOwner = new EvidenceHeadV1(
                EvidenceFixtures.ID, EvidenceFixtures.repeat(0x77), 1);
        assertInvalid(() -> new EvidenceGetResponseV1(
                true, valid.headKey(), wrongOwner.encode(),
                valid.recordKey(), valid.recordValue()));

        assertInvalid(() -> new EvidenceGetResponseV1(
                false, new byte[]{1}, new byte[0], new byte[0], new byte[0]));
        assertInvalid(() -> new EvidenceGetResponseV1(
                true, new byte[0], new byte[0], new byte[0], new byte[0]));
    }

    @Test
    void notFoundResponseHasOneCanonicalEmptyShape() {
        EvidenceGetResponseV1 missing = EvidenceGetResponseV1.notFound();
        assertThat(missing.found()).isFalse();
        assertThat(missing.headKey()).isEmpty();
        assertThat(missing.recordValue()).isEmpty();
        assertThat(EvidenceGetResponseV1.decode(missing.encode())).isEqualTo(missing);
        assertInvalid(missing::head);
        assertInvalid(missing::record);
    }

    @Test
    void eventAndQueryByteFieldsAreDefensivelyCopied() {
        EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(
                EvidenceFixtures.storageReadyRecord());
        byte[] digest = event.digest();
        Arrays.fill(digest, (byte) 0);
        assertThat(event.digest()).isEqualTo(EvidenceFixtures.DIGEST);

        EvidenceGetResponseV1 response = EvidenceGetResponseV1.notFound();
        assertThat(response.headKey()).isNotSameAs(response.headKey());
    }

    private static void assertInvalid(Runnable runnable) {
        assertThatThrownBy(runnable::run).isInstanceOf(IllegalArgumentException.class);
    }
}
