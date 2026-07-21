package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectPutDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.ObjectStoreEffectTestSupport.CONTENT;
import static com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.ObjectStoreEffectTestSupport.NOW;
import static com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.ObjectStoreEffectTestSupport.command;
import static com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.ObjectStoreEffectTestSupport.context;
import static com.bloxbean.cardano.yano.appchain.objectstore.s3.effects.ObjectStoreEffectTestSupport.effect;
import static org.assertj.core.api.Assertions.assertThat;

class S3ObjectPutExecutorTest {

    @Test
    void conditionallyCreatesVerifiedVersionAndArchivesBoundedDetail() throws Exception {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        ObjectStoreEffectTestSupport.MemoryDetailArchive archive =
                new ObjectStoreEffectTestSupport.MemoryDetailArchive();
        PendingEffect effect = effect(command().encode());
        try (S3ObjectPutExecutor executor = executor(client, archive)) {
            EffectExecution result = executor.execute(context(1), effect);

            assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
            EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) result;
            ObjectPutReceiptV1 receipt = ObjectPutReceiptV1.decode(confirmed.externalRef());
            assertThat(receipt.verifiedSha256()).isEqualTo(ObjectStoreEffectTestSupport.sha256(CONTENT));
            assertThat(receipt.size()).isEqualTo(CONTENT.length);
            assertThat(receipt.objectVersionFingerprint()).isEqualTo(
                    ObjectVersionFingerprint.compute("version-1").bytes());
            assertThat(client.calls()).containsExactly(
                    "head", "versions", "get", "versioning",
                    "head", "versions", "put", "head", "get");
            assertThat(client.mutations()).isOne();
            assertThat(client.lastPutBytes()).isEqualTo(CONTENT);
            assertThat(client.sourceWipedBeforeReconciliation()).isTrue();
            assertThat(client.lastPutRequest().userMetadata())
                    .isEqualTo(ObjectStoreEffectTestSupport.expectedMetadata(
                            effect, CONTENT.length, ObjectStoreEffectTestSupport.sha256(CONTENT), "worm"));
            assertThat(client.lastPutRequest().retainUntilEpochMillis())
                    .isEqualTo(NOW.plus(Duration.ofDays(7)).toEpochMilli());

            byte[] detailBytes = archive.bytes(confirmed.detailHash()).orElseThrow();
            ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.decode(detailBytes);
            assertThat(document.effectIdHash()).isEqualTo(effect.idHash());
            ObjectPutDetailV1 detail = (ObjectPutDetailV1) document.data();
            assertThat(detail.providerVersionId()).isEqualTo("version-1");
            assertThat(detail.etag()).isEqualTo("etag-created");
            assertThat(detail.retentionMode()).isEqualTo(ObjectRetentionMode.GOVERNANCE);
            assertThat(detail.retainUntilEpochMillis())
                    .isEqualTo(NOW.plus(Duration.ofDays(7)).toEpochMilli());
        }
        assertThat(client.closeCalls()).isOne();
    }

    @Test
    void confirmsExistingExactVersionWithoutReadingSourceOrMutating() {
        ObjectStoreEffectTestSupport.FakeClient client = exactExistingClient();
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            EffectExecution result = executor.execute(context(1), effect(command().encode()));

            assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(client.calls()).containsExactly("head", "get");
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void existingRetentionDeadlineIsAuthoritativeAcrossRetryTime() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        PendingEffect effect = effect(command().encode());
        long originalDeadline = NOW.plus(Duration.ofDays(3)).toEpochMilli();
        client.destination(ObjectStoreEffectTestSupport.existing(
                effect, "original-version", CONTENT, originalDeadline));
        Clock muchLater = Clock.fixed(NOW.plus(Duration.ofDays(100)), ZoneOffset.UTC);
        try (S3ObjectPutExecutor executor = new S3ObjectPutExecutor(
                ObjectStoreEffectTestSupport.config(), ignored -> client, null, muchLater)) {
            EffectExecution.Confirmed result = (EffectExecution.Confirmed) executor.execute(
                    context(9), effect);
            ObjectPutReceiptV1 receipt = ObjectPutReceiptV1.decode(result.externalRef());
            assertThat(receipt.objectVersionFingerprint()).isEqualTo(
                    ObjectVersionFingerprint.compute("original-version").bytes());
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void everyMetadataOrProviderPolicyMismatchFailsClosedWithoutMutation() {
        PendingEffect effect = effect(command().encode());
        StoredObject exact = ObjectStoreEffectTestSupport.existing(
                effect, "version-existing", CONTENT, NOW.plus(Duration.ofDays(7)).toEpochMilli());
        Map<String, StoredObjectMetadata> mismatches = new LinkedHashMap<>();
        Map<String, String> extra = new LinkedHashMap<>(exact.metadata().userMetadata());
        extra.put("unapproved", "value");
        mismatches.put("extra user metadata", metadata(exact, extra, EncryptionMode.NONE,
                null,
                ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis(), "application/json", CONTENT.length));
        Map<String, String> wrongEffect = new LinkedHashMap<>(exact.metadata().userMetadata());
        wrongEffect.put("yano-effect-id", "00".repeat(32));
        mismatches.put("wrong effect", metadata(exact, wrongEffect, EncryptionMode.NONE,
                null,
                ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis(), "application/json", CONTENT.length));
        mismatches.put("wrong content type", metadata(exact, exact.metadata().userMetadata(),
                EncryptionMode.NONE, null, ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis(), "text/plain", CONTENT.length));
        mismatches.put("wrong encryption", metadata(exact, exact.metadata().userMetadata(),
                EncryptionMode.SSE_S3, null, ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis(), "application/json", CONTENT.length));
        mismatches.put("wrong retention", metadata(exact, exact.metadata().userMetadata(),
                EncryptionMode.NONE, null, ObjectRetentionMode.COMPLIANCE,
                exact.metadata().retainUntilEpochMillis(), "application/json", CONTENT.length));
        mismatches.put("missing version", new StoredObjectMetadata(null, "etag", CONTENT.length,
                "application/json", exact.metadata().userMetadata(), EncryptionMode.NONE,
                null, ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis()));
        mismatches.put("unversioned S3 sentinel", new StoredObjectMetadata("null", "etag",
                CONTENT.length, "application/json", exact.metadata().userMetadata(),
                EncryptionMode.NONE, null, ObjectRetentionMode.GOVERNANCE,
                exact.metadata().retainUntilEpochMillis()));

        mismatches.forEach((name, mismatch) -> {
            ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
            client.destination(new StoredObject(mismatch, CONTENT));
            try (S3ObjectPutExecutor executor = executor(client, null)) {
                assertFailure(executor.execute(context(1), effect),
                        ConnectorErrorCode.DESTINATION_CONFLICT, false);
                assertThat(client.mutations()).as(name).isZero();
                assertThat(client.calls()).as(name).containsExactly("head");
            }
        });
    }

    @Test
    void existingBodyHashMismatchFailsClosedEvenWhenMetadataMatches() {
        PendingEffect effect = effect(command().encode());
        byte[] altered = CONTENT.clone();
        altered[0] ^= 1;
        StoredObject exact = ObjectStoreEffectTestSupport.existing(
                effect, "version-existing", CONTENT, NOW.plus(Duration.ofDays(7)).toEpochMilli());
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.destination(new StoredObject(exact.metadata(), altered));
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect),
                    ConnectorErrorCode.DESTINATION_CONFLICT, false);
            assertThat(client.calls()).containsExactly("head", "get");
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void providerRetentionDeadlineMustEqualItsImmutableMetadata() {
        PendingEffect effect = effect(command().encode());
        StoredObject exact = ObjectStoreEffectTestSupport.existing(
                effect, "version-existing", CONTENT, NOW.plus(Duration.ofDays(7)).toEpochMilli());
        StoredObjectMetadata shortened = new StoredObjectMetadata(
                exact.metadata().versionId(), exact.metadata().etag(), CONTENT.length,
                exact.metadata().contentType(), exact.metadata().userMetadata(),
                exact.metadata().encryptionMode(), exact.metadata().kmsKeyId(),
                exact.metadata().retentionMode(), NOW.plus(Duration.ofDays(1)).toEpochMilli());
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.destination(new StoredObject(shortened, CONTENT.clone()));

        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect),
                    ConnectorErrorCode.DESTINATION_CONFLICT, false);
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void noRetentionUsesExplicitNoneMetadataAndProviderMode() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        ObjectPutCommandV1 withoutRetention = ObjectStoreEffectTestSupport.command(
                null, ObjectStoreEffectTestSupport.sha256(CONTENT), CONTENT.length);
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertThat(executor.execute(context(1), effect(withoutRetention.encode())))
                    .isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(client.lastPutRequest().retentionMode())
                    .isEqualTo(ObjectRetentionMode.NONE);
            assertThat(client.lastPutRequest().retainUntilEpochMillis()).isNull();
            assertThat(client.lastPutRequest().userMetadata())
                    .containsEntry("yano-retention-class", "none")
                    .containsEntry("yano-retain-until", "none");
        }
    }

    @Test
    void priorVersionOrDeleteMarkerPreventsResurrection() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.history(true);
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect(command().encode())),
                    ConnectorErrorCode.DESTINATION_CONFLICT, false);
            assertThat(client.calls()).containsExactly("head", "versions");
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void sourceDigestMismatchIsDefinitiveAndNeverWrites() {
        byte[] other = CONTENT.clone();
        other[1] ^= 1;
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.source(ObjectStoreEffectTestSupport.source(other));
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect(command().encode())),
                    ConnectorErrorCode.SOURCE_MISMATCH, false);
            assertThat(client.calls()).containsExactly("head", "versions", "get");
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void sourceSizeMismatchIsDefinitiveAndNeverWrites() {
        ObjectPutCommandV1 command = ObjectStoreEffectTestSupport.command(
                "worm", ObjectStoreEffectTestSupport.sha256(CONTENT), CONTENT.length + 1L);
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect(command.encode())),
                    ConnectorErrorCode.SOURCE_MISMATCH, false);
            assertThat(client.mutations()).isZero();
        }
    }

    @Test
    void versioningDriftFailsBeforeMutation() {
        for (BucketVersioning state : List.of(BucketVersioning.DISABLED, BucketVersioning.UNKNOWN)) {
            ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
            client.versioning(state);
            try (S3ObjectPutExecutor executor = executor(client, null)) {
                assertFailure(executor.execute(context(1), effect(command().encode())),
                        ConnectorErrorCode.TARGET_CHANGED, true);
                assertThat(client.calls()).containsExactly("head", "versions", "get", "versioning");
                assertThat(client.mutations()).isZero();
            }
        }
    }

    @Test
    void conditionalConflictRaceReconcilesMatchingVersionWithoutSecondWrite() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.putBehavior(ObjectStoreEffectTestSupport.PutBehavior.CONFLICT_COMMIT);
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertThat(executor.execute(context(1), effect(command().encode())))
                    .isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(client.mutations()).isOne();
            assertThat(client.calls().stream().filter("put"::equals)).hasSize(1);
            assertThat(client.sourceWipedBeforeReconciliation()).isTrue();
        }
    }

    @Test
    void unknownAcknowledgementReconcilesCommittedVersionWithoutDuplicate() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.putBehavior(ObjectStoreEffectTestSupport.PutBehavior.UNKNOWN_COMMIT);
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertThat(executor.execute(context(1), effect(command().encode())))
                    .isInstanceOf(EffectExecution.Confirmed.class);
            assertThat(client.mutations()).isOne();
            assertThat(client.calls().stream().filter("put"::equals)).hasSize(1);
        }
    }

    @Test
    void unknownAcknowledgementWithoutVisibleStateIsRetryable() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.putBehavior(ObjectStoreEffectTestSupport.PutBehavior.UNKNOWN_NO_COMMIT);
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect(command().encode())),
                    ConnectorErrorCode.ACK_UNKNOWN, true);
            assertThat(client.mutations()).isZero();
            assertThat(client.calls().stream().filter("put"::equals)).hasSize(1);
        }
    }

    @Test
    void archiveFailurePersistsReceiptThenRefetchesAndArchivesWithoutMutation() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        ObjectStoreEffectTestSupport.MemoryDetailArchive archive =
                new ObjectStoreEffectTestSupport.MemoryDetailArchive();
        archive.fail(new IOException("archive unavailable"));
        PendingEffect effect = effect(command().encode());
        try (S3ObjectPutExecutor executor = executor(client, archive)) {
            EffectExecution.Submitted first = (EffectExecution.Submitted) executor.execute(
                    context(1), effect);
            int putCalls = (int) client.calls().stream().filter("put"::equals).count();

            assertFailure(executor.execute(context(2, first.externalRef()), effect),
                    ConnectorErrorCode.DETAIL_ARCHIVE_FAILED, true);
            assertThat(client.calls().stream().filter("put"::equals)).hasSize(putCalls);
            assertThat(client.mutations()).isOne();

            archive.recover();
            EffectExecution.Confirmed third = (EffectExecution.Confirmed) executor.execute(
                    context(3, first.externalRef()), effect);
            assertThat(third.detailHash()).hasSize(32);
            assertThat(client.calls().stream().filter("put"::equals)).hasSize(putCalls);
            assertThat(client.mutations()).isOne();
        }
    }

    @Test
    void malformedOrMismatchedSubmittedReferenceNeverContactsProvider() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        PendingEffect effect = effect(command().encode());
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(2, new byte[]{1, 2, 3}), effect),
                    ConnectorErrorCode.INTERNAL_ERROR, true);
            assertThat(client.calls()).isEmpty();

            ObjectPutReceiptV1 changed = new ObjectPutReceiptV1(new byte[32], new byte[32],
                    ObjectStoreEffectTestSupport.sha256(CONTENT), CONTENT.length);
            assertFailure(executor.execute(context(3, changed.encode()), effect),
                    ConnectorErrorCode.TARGET_CHANGED, true);
            assertThat(client.calls()).isEmpty();
        }
    }

    @Test
    void invalidPayloadTypeIdentityAndContextFailBeforeProviderIo() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        try (S3ObjectPutExecutor executor = executor(client, null)) {
            assertFailure(executor.execute(context(1), effect(new byte[]{0})),
                    ConnectorErrorCode.INVALID_PAYLOAD, false);
            assertFailure(executor.execute(context(1), effect("other.type", command().encode(), null)),
                    ConnectorErrorCode.INVALID_PAYLOAD, false);
            assertFailure(executor.execute(context(1), effect(
                            S3ObjectPutExecutor.TYPE, command().encode(), new byte[32])),
                    ConnectorErrorCode.INVALID_PAYLOAD, false);
            assertFailure(executor.execute(ObjectStoreEffectTestSupport.context(
                            "other-chain", 1, new byte[0]), effect(command().encode())),
                    ConnectorErrorCode.INTERNAL_ERROR, true);
            assertThat(client.calls()).isEmpty();
        }
    }

    @Test
    void unknownTargetRetentionClassAndSizePolicyFailWithoutOpeningClient() {
        AtomicInteger opens = new AtomicInteger();
        try (S3ObjectPutExecutor executor = new S3ObjectPutExecutor(
                ObjectStoreEffectTestSupport.config(), target -> {
                    opens.incrementAndGet();
                    return new ObjectStoreEffectTestSupport.FakeClient();
                }, null, Clock.fixed(NOW, ZoneOffset.UTC))) {
            ObjectPutCommandV1 unknownTarget = new ObjectPutCommandV1("missing",
                    ObjectStoreEffectTestSupport.SOURCE_KEY,
                    ObjectStoreEffectTestSupport.DESTINATION_KEY,
                    command().digestAlgorithm(), command().digest(),
                    command().size(), command().contentType(), "worm");
            assertFailure(executor.execute(context(1), effect(unknownTarget.encode())),
                    ConnectorErrorCode.UNKNOWN_TARGET, false);
            ObjectPutCommandV1 unknownRetention = ObjectStoreEffectTestSupport.command(
                    "missing", command().digest(), command().size());
            assertFailure(executor.execute(context(1), effect(unknownRetention.encode())),
                    ConnectorErrorCode.POLICY_DENIED, false);
            assertThat(opens).hasValue(0);
        }
    }

    @Test
    void normalizedProviderErrorsPreserveFrozenDisposition() {
        for (ConnectorErrorCode code : Arrays.asList(
                ConnectorErrorCode.AUTH_UNAVAILABLE,
                ConnectorErrorCode.RATE_LIMITED,
                ConnectorErrorCode.SERVICE_UNAVAILABLE,
                ConnectorErrorCode.PROVIDER_REJECTED)) {
            ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
            client.putBehavior(ObjectStoreEffectTestSupport.PutBehavior.FAIL);
            client.failureCode(code);
            try (S3ObjectPutExecutor executor = executor(client, null)) {
                assertFailure(executor.execute(context(1), effect(command().encode())),
                        code, code.disposition().retryable());
            }
        }
    }

    @Test
    void closeIsIdempotentAndDoesNotWaitForBlockedCall() throws Exception {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        client.blockHead();
        S3ObjectPutExecutor executor = executor(client, null);
        CompletableFuture<EffectExecution> execution = CompletableFuture.supplyAsync(
                () -> executor.execute(context(1), effect(command().encode())));
        assertThat(client.awaitBlocked()).isTrue();
        EffectExecution contention = executor.execute(context(2), effect(command().encode()));
        assertThat(contention).isInstanceOf(EffectExecution.Retry.class);
        assertThat(((EffectExecution.Retry) contention).notBefore())
                .isEqualTo(Duration.ofMillis(100));
        assertThat(client.calls()).containsExactly("head");

        assertThat(CompletableFuture.runAsync(executor::close).get(2, TimeUnit.SECONDS)).isNull();
        executor.close();
        assertThat(client.closeCalls()).isOne();
        assertThat(execution.get(2, TimeUnit.SECONDS)).isInstanceOf(EffectExecution.Failed.class);
    }

    @Test
    void concurrentLazyOpenClosesLoserAndRetainsOneOwnedClient() throws Exception {
        ObjectStoreEffectTestSupport.FakeClient first = new ObjectStoreEffectTestSupport.FakeClient();
        ObjectStoreEffectTestSupport.FakeClient second = new ObjectStoreEffectTestSupport.FakeClient();
        AtomicInteger opens = new AtomicInteger();
        try (S3ObjectPutExecutor executor = new S3ObjectPutExecutor(
                ObjectStoreEffectTestSupport.config(), ignored ->
                opens.getAndIncrement() == 0 ? first : second,
                null, Clock.fixed(NOW, ZoneOffset.UTC))) {
            CompletableFuture<EffectExecution> one = CompletableFuture.supplyAsync(
                    () -> executor.execute(context(1), effect(command().encode())));
            CompletableFuture<EffectExecution> two = CompletableFuture.supplyAsync(
                    () -> executor.execute(context(1), effect(command().encode())));
            CompletableFuture.allOf(one, two).get(3, TimeUnit.SECONDS);
            assertThat(one.get()).isInstanceOfAny(EffectExecution.Confirmed.class,
                    EffectExecution.Failed.class, EffectExecution.Retry.class);
            assertThat(two.get()).isInstanceOfAny(EffectExecution.Confirmed.class,
                    EffectExecution.Failed.class, EffectExecution.Retry.class);
        }
        assertThat(first.closeCalls() + second.closeCalls()).isEqualTo(opens.get());
    }

    private static ObjectStoreEffectTestSupport.FakeClient exactExistingClient() {
        ObjectStoreEffectTestSupport.FakeClient client = new ObjectStoreEffectTestSupport.FakeClient();
        PendingEffect effect = effect(command().encode());
        client.destination(ObjectStoreEffectTestSupport.existing(effect, "version-existing",
                CONTENT, NOW.plus(Duration.ofDays(7)).toEpochMilli()));
        return client;
    }

    private static StoredObjectMetadata metadata(StoredObject base,
                                                 Map<String, String> userMetadata,
                                                 EncryptionMode encryption,
                                                 String kmsKeyId,
                                                 ObjectRetentionMode retention,
                                                 Long retainUntil,
                                                 String contentType,
                                                 long length) {
        return new StoredObjectMetadata(base.metadata().versionId(), base.metadata().etag(), length,
                contentType, userMetadata, encryption, kmsKeyId, retention, retainUntil);
    }

    private static S3ObjectPutExecutor executor(ObjectStoreEffectTestSupport.FakeClient client,
                                                ObjectStoreEffectTestSupport.MemoryDetailArchive archive) {
        return new S3ObjectPutExecutor(ObjectStoreEffectTestSupport.config(), ignored -> client,
                archive, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertFailure(EffectExecution result,
                                      ConnectorErrorCode code,
                                      boolean retryable) {
        assertThat(result).isInstanceOf(EffectExecution.Failed.class);
        EffectExecution.Failed failure = (EffectExecution.Failed) result;
        assertThat(failure.reason()).isEqualTo(code.wireCode());
        assertThat(failure.retryable()).isEqualTo(retryable);
    }
}
