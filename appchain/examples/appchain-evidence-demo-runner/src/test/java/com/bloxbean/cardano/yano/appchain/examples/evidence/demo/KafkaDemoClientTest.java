package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaDemoClientTest {
    private static final String EFFECT_ID = "ab".repeat(32);

    @Test
    void acceptsOnlyTheFreshSinglePartitionTopology() {
        KafkaDemoClient.requireExactPartitionZero(List.of(0));

        assertMismatch(() -> KafkaDemoClient.requireExactPartitionZero(List.of()));
        assertMismatch(() -> KafkaDemoClient.requireExactPartitionZero(List.of(1)));
        assertMismatch(() -> KafkaDemoClient.requireExactPartitionZero(List.of(0, 1)));
    }

    @Test
    void auditsOnlyTheExpectedPhysicalRecordAndExactReservedHeaders() {
        KafkaDemoClient.AuditRecord audited = KafkaDemoClient.auditRecord(
                record(0, 0, new byte[]{1}, new byte[]{2}), 0, EFFECT_ID);
        assertThat(audited.offset()).isZero();
        assertThat(audited.effectId()).isEqualTo(EFFECT_ID);
        assertThat(audited.recordDigest()).matches("[0-9a-f]{64}");

        ConsumerRecord<byte[], byte[]> wrongPartition = record(
                1, 0, new byte[]{1}, new byte[]{2});
        assertMismatch(() -> KafkaDemoClient.auditRecord(
                wrongPartition, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> missing = record(
                0, 0, new byte[]{1}, new byte[]{2});
        missing.headers().remove("yano-chain-id");
        assertMismatch(() -> KafkaDemoClient.auditRecord(missing, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> extra = record(
                0, 0, new byte[]{1}, new byte[]{2});
        extra.headers().add("untrusted", new byte[]{1});
        assertMismatch(() -> KafkaDemoClient.auditRecord(extra, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> duplicate = record(
                0, 0, new byte[]{1}, new byte[]{2});
        duplicate.headers().add("yano-effect-id",
                EFFECT_ID.getBytes(StandardCharsets.US_ASCII));
        assertMismatch(() -> KafkaDemoClient.auditRecord(duplicate, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> wrongFixedValue = record(
                0, 0, new byte[]{1}, new byte[]{2});
        wrongFixedValue.headers().remove("yano-effect-type");
        wrongFixedValue.headers().add("yano-effect-type", ascii("object.put"));
        assertMismatch(() -> KafkaDemoClient.auditRecord(
                wrongFixedValue, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> wrongOrigin = record(
                0, 0, new byte[]{1}, new byte[]{2});
        wrongOrigin.headers().remove("yano-origin-height");
        wrongOrigin.headers().add("yano-origin-height", ascii("01"));
        assertMismatch(() -> KafkaDemoClient.auditRecord(wrongOrigin, 0, EFFECT_ID));

        ConsumerRecord<byte[], byte[]> oversized = record(
                0, 0, new byte[]{1}, new byte[]{2});
        oversized.headers().remove("yano-content-type");
        oversized.headers().add("yano-content-type", new byte[1_025]);
        assertMismatch(() -> KafkaDemoClient.auditRecord(oversized, 0, EFFECT_ID));
    }

    @Test
    void requiresEveryPhysicalRetryToHaveByteIdenticalContentAndHeaderOrder() {
        KafkaDemoClient.AuditRecord first = KafkaDemoClient.auditRecord(
                record(0, 0, new byte[]{1}, new byte[]{2}), 0, EFFECT_ID);
        KafkaDemoClient.AuditRecord identicalRetry = KafkaDemoClient.auditRecord(
                record(0, 1, new byte[]{1}, new byte[]{2}), 1, EFFECT_ID);
        KafkaDemoClient.AuditRecord payloadDrift = KafkaDemoClient.auditRecord(
                record(0, 1, new byte[]{1}, new byte[]{3}), 1, EFFECT_ID);
        KafkaDemoClient.AuditRecord headerOrderDrift = KafkaDemoClient.auditRecord(
                reorderedRecord(1), 1, EFFECT_ID);

        assertThatCode(() -> KafkaDemoClient.requireSameRetryContent(
                first, identicalRetry)).doesNotThrowAnyException();
        assertThat(identicalRetry.recordDigest()).isEqualTo(first.recordDigest());
        assertMismatch(() -> KafkaDemoClient.requireSameRetryContent(first, payloadDrift));
        assertMismatch(() -> KafkaDemoClient.requireSameRetryContent(
                first, headerOrderDrift));
    }

    @Test
    void rendersOneStableMinimalJsonDocument() {
        KafkaDemoClient.KafkaAudit audit = new KafkaDemoClient.KafkaAudit(
                "evidence-ready-v1", 0, 0, 2, List.of(
                new KafkaDemoClient.AuditRecord(0, EFFECT_ID, "cd".repeat(32)),
                new KafkaDemoClient.AuditRecord(1, EFFECT_ID, "cd".repeat(32))));

        assertThat(audit.toJson()).isEqualTo("{\"schemaVersion\":1,"
                + "\"topic\":\"evidence-ready-v1\",\"partition\":0,"
                + "\"beginningOffset\":0,\"endOffset\":2,\"recordCount\":2,"
                + "\"records\":[{\"offset\":0,\"effectId\":\"" + EFFECT_ID
                + "\",\"recordDigest\":\"" + "cd".repeat(32)
                + "\"},{\"offset\":1,\"effectId\":\"" + EFFECT_ID
                + "\",\"recordDigest\":\"" + "cd".repeat(32) + "\"}]}");
    }

    private static ConsumerRecord<byte[], byte[]> record(int partition, long offset,
                                                          byte[] key, byte[] value) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "evidence-ready-v1", partition, offset, key, value);
        record.headers().add("yano-effect-id", ascii(EFFECT_ID));
        record.headers().add("yano-chain-id", ascii("evidence-chain"));
        record.headers().add("yano-effect-type", ascii("kafka.publish"));
        record.headers().add("yano-payload-version", ascii("1"));
        record.headers().add("yano-origin-height", ascii("7"));
        record.headers().add("yano-origin-ordinal", ascii("1"));
        record.headers().add("yano-content-type", ascii("application/cbor"));
        return record;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static ConsumerRecord<byte[], byte[]> reorderedRecord(long offset) {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "evidence-ready-v1", 0, offset, new byte[]{1}, new byte[]{2});
        record.headers().add("yano-content-type", ascii("application/cbor"));
        record.headers().add("yano-origin-ordinal", ascii("1"));
        record.headers().add("yano-origin-height", ascii("7"));
        record.headers().add("yano-payload-version", ascii("1"));
        record.headers().add("yano-effect-type", ascii("kafka.publish"));
        record.headers().add("yano-chain-id", ascii("evidence-chain"));
        record.headers().add("yano-effect-id", ascii(EFFECT_ID));
        return record;
    }

    private static void assertMismatch(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }
}
