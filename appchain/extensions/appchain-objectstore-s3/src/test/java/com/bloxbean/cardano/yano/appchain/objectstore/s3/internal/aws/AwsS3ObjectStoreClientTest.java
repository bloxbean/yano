package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutAcknowledgement;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.VersionInventory;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsS3ObjectStoreClientTest {
    private static final String BUCKET = "archive-bucket";
    private static final String KEY = "verified/evidence.cbor";
    private static final String EXPECTED_OWNER = "123456789012";

    @Test
    void reportsBucketVersioningWithoutGuessing() {
        FakeS3 fake = new FakeS3();
        fake.respond("getBucketVersioning", GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED).build(),
                        GetBucketVersioningResponse.builder()
                                .status(BucketVersioningStatus.SUSPENDED).build(),
                        GetBucketVersioningResponse.builder().build());
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThat(client.bucketVersioning(BUCKET)).isEqualTo(BucketVersioning.ENABLED);
        assertThat(client.bucketVersioning(BUCKET)).isEqualTo(BucketVersioning.DISABLED);
        assertThat(client.bucketVersioning(BUCKET)).isEqualTo(BucketVersioning.DISABLED);
        assertThat(fake.calls("getBucketVersioning"))
                .allSatisfy(call -> assertThat(
                        ((GetBucketVersioningRequest) call.argument(0)).expectedBucketOwner())
                        .isEqualTo(EXPECTED_OWNER));
    }

    @Test
    void exactDeleteMarkerProvesHistoryWithoutScanningPrefixSiblings() {
        FakeS3 fake = new FakeS3();
        fake.respond("listObjectVersions", ListObjectVersionsResponse.builder()
                        .isTruncated(true)
                        .nextKeyMarker(KEY)
                        .nextVersionIdMarker("v2")
                        .versions(ObjectVersion.builder()
                                .key(KEY + "/not-the-object").versionId("other").build())
                        .deleteMarkers(DeleteMarkerEntry.builder()
                                .key(KEY).versionId("v2").build())
                        .build());
        AwsS3ObjectStoreClient client = client(fake.client());

        VersionInventory result = client.listVersions(BUCKET, KEY, 4);

        assertThat(result.entriesExamined()).isEqualTo(1);
        assertThat(result.anyVersionOrDeleteMarker()).isTrue();
        List<Call> calls = fake.calls("listObjectVersions");
        assertThat(calls).hasSize(1);
        ListObjectVersionsRequest first = calls.get(0).argument(0);
        assertThat(first.prefix()).isEqualTo(KEY);
        assertThat(first.maxKeys()).isEqualTo(4);
        assertThat(first.expectedBucketOwner()).isEqualTo(EXPECTED_OWNER);
    }

    @Test
    void prefixSharingSiblingDoesNotConsumeExactKeyHistoryBudget() {
        FakeS3 fake = new FakeS3();
        fake.respond("listObjectVersions", ListObjectVersionsResponse.builder()
                        .isTruncated(true)
                        .nextKeyMarker(KEY + "/sibling")
                        .nextVersionIdMarker("other")
                        .versions(ObjectVersion.builder()
                                .key(KEY + "/sibling").versionId("other").build())
                        .build());
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThat(client.listVersions(BUCKET, KEY, 1))
                .isEqualTo(new VersionInventory(0, false));
        assertThat(fake.calls("listObjectVersions")).hasSize(1);
    }

    @Test
    void inconclusiveTruncatedInventoryIsAcknowledgementUnknown() {
        FakeS3 fake = new FakeS3();
        fake.respond("listObjectVersions", ListObjectVersionsResponse.builder()
                .isTruncated(true)
                .nextKeyMarker(KEY)
                .nextVersionIdMarker("v1")
                .build());
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThatThrownBy(() -> client.listVersions(BUCKET, KEY, 1))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.ACK_UNKNOWN));
        assertThat(fake.calls("listObjectVersions")).hasSize(1);
    }

    @Test
    void inaccessibleVersionInventoryIsAcknowledgementUnknown() {
        FakeS3 fake = new FakeS3();
        fake.respond("listObjectVersions",
                SdkClientException.create("simulated unavailable object history"));
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThatThrownBy(() -> client.listVersions(BUCKET, KEY, 4))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.ACK_UNKNOWN));
    }

    @Test
    void headsAnExactVersionWithChecksumModeAndMapsStableMetadata() {
        FakeS3 fake = new FakeS3();
        Instant retainUntil = Instant.parse("2030-01-01T00:00:00Z");
        fake.respond("headObject", HeadObjectResponse.builder()
                        .versionId("version-1")
                        .eTag("etag-1")
                        .contentLength(3L)
                        .contentType("application/cbor")
                        .metadata(Map.of("yano-effect-id", "abc"))
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId("arn:aws:kms:us-east-1:123456789012:key/abc")
                        .objectLockMode(ObjectLockMode.GOVERNANCE)
                        .objectLockRetainUntilDate(retainUntil)
                        .checksumSHA256(base64(sha256(new byte[]{1, 2, 3})))
                        .checksumType(ChecksumType.FULL_OBJECT)
                        .build());
        AwsS3ObjectStoreClient client = client(fake.client());

        Optional<StoredObjectMetadata> result = client.head(BUCKET, KEY, "version-1");

        assertThat(result).contains(new StoredObjectMetadata("version-1", "etag-1", 3,
                "application/cbor", Map.of("yano-effect-id", "abc"),
                EncryptionMode.SSE_KMS, "arn:aws:kms:us-east-1:123456789012:key/abc",
                ObjectRetentionMode.GOVERNANCE,
                retainUntil.toEpochMilli()));
        HeadObjectRequest request = fake.calls("headObject").getFirst().argument(0);
        assertThat(request.versionId()).isEqualTo("version-1");
        assertThat(request.checksumModeAsString()).isEqualTo("ENABLED");
        assertThat(request.expectedBucketOwner()).isEqualTo(EXPECTED_OWNER);
    }

    @Test
    void acceptsCompositeHeadChecksumShapeAndRejectsUnknownType() {
        FakeS3 accepted = new FakeS3();
        accepted.respond("headObject", HeadObjectResponse.builder()
                .versionId("version-1")
                .contentLength(3L)
                .contentType("application/cbor")
                .metadata(Map.of())
                .checksumSHA256(base64(new byte[32]) + "-2")
                .checksumType(ChecksumType.COMPOSITE)
                .build());

        assertThat(client(accepted.client()).head(BUCKET, KEY, "version-1")).isPresent();

        FakeS3 unknown = new FakeS3();
        unknown.respond("headObject", HeadObjectResponse.builder()
                .versionId("version-1")
                .contentLength(3L)
                .contentType("application/cbor")
                .metadata(Map.of())
                .checksumSHA256(base64(new byte[32]))
                .checksumType(ChecksumType.UNKNOWN_TO_SDK_VERSION)
                .build());
        assertThatThrownBy(() -> client(unknown.client()).head(BUCKET, KEY, "version-1"))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.PROVIDER_REJECTED));
    }

    @Test
    void returnsEmptyOnlyForHeadNotFound() {
        FakeS3 fake = new FakeS3();
        fake.respond("headObject",
                S3Exception.builder().statusCode(404).message("hidden-key").build());
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThat(client.head(BUCKET, KEY, null)).isEmpty();
    }

    @Test
    void rejectsNonPrintableProviderIdentifiersBeforeReceiptOrDetailCreation() {
        for (HeadObjectResponse response : List.of(
                HeadObjectResponse.builder().versionId("version\nsecret").contentLength(1L)
                        .contentType("application/octet-stream").metadata(Map.of()).build(),
                HeadObjectResponse.builder().versionId("version-1").eTag("etag\u007fsecret")
                        .contentLength(1L).contentType("application/octet-stream")
                        .metadata(Map.of()).build())) {
            FakeS3 fake = new FakeS3();
            fake.respond("headObject", response);
            assertThatThrownBy(() -> client(fake.client()).head(BUCKET, KEY, null))
                    .isInstanceOfSatisfying(ObjectStoreException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(ConnectorErrorCode.PROVIDER_REJECTED));
        }

        byte[] bytes = {1, 2, 3};
        assertPutAcknowledgementUnknown(bytes, PutObjectResponse.builder()
                .versionId("version\nsecret")
                .checksumSHA256(base64(sha256(bytes)))
                .checksumType(ChecksumType.FULL_OBJECT)
                .build());
    }

    @Test
    void rejectsUnmodeledDsseEncryptionInsteadOfCollapsingPolicyIdentity() {
        FakeS3 fake = new FakeS3();
        fake.respond("headObject", HeadObjectResponse.builder()
                .versionId("version-1")
                .contentLength(3L)
                .contentType("application/cbor")
                .metadata(Map.of())
                .serverSideEncryption(ServerSideEncryption.AWS_KMS_DSSE)
                .ssekmsKeyId("arn:aws:kms:us-east-1:123456789012:key/abc")
                .build());

        assertThatThrownBy(() -> client(fake.client()).head(BUCKET, KEY, null))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.PROVIDER_REJECTED));
    }

    @Test
    void readsExactBoundedBytesAndVerifiesProviderChecksum() {
        byte[] bytes = {1, 2, 3, 4};
        GetObjectResponse response = GetObjectResponse.builder()
                .versionId("v1")
                .eTag("etag")
                .contentLength((long) bytes.length)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(base64(sha256(bytes)))
                .checksumType(ChecksumType.FULL_OBJECT)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject",
                new ResponseInputStream<>(response, new ByteArrayInputStream(bytes)));
        AwsS3ObjectStoreClient client = client(fake.client());

        StoredObject result = client.get(BUCKET, KEY, "v1", bytes.length);

        byte[] transferred = result.takeBytes();
        assertThat(transferred).containsExactly(bytes);
        Arrays.fill(transferred, (byte) 0);
        assertThat(result.metadata().versionId()).isEqualTo("v1");
        GetObjectRequest request = fake.calls("getObject").getFirst().argument(0);
        assertThat(request.versionId()).isEqualTo("v1");
        assertThat(request.checksumModeAsString()).isEqualTo("ENABLED");
        assertThat(request.expectedBucketOwner()).isEqualTo(EXPECTED_OWNER);
    }

    @Test
    void acceptsCompositeGetChecksumWithoutComparingItToTheFullObjectDigest() {
        byte[] bytes = {1, 2, 3, 4};
        GetObjectResponse response = GetObjectResponse.builder()
                .versionId("v1")
                .contentLength((long) bytes.length)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(base64(new byte[32]) + "-3")
                .checksumType(ChecksumType.COMPOSITE)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject",
                new ResponseInputStream<>(response, new ByteArrayInputStream(bytes)));

        StoredObject result = client(fake.client()).get(BUCKET, KEY, "v1", bytes.length);

        byte[] transferred = result.takeBytes();
        assertThat(transferred).containsExactly(bytes);
        Arrays.fill(transferred, (byte) 0);
    }

    @Test
    void rejectsMalformedCompositeAndUnknownGetChecksumTypes() {
        assertGetChecksumFailure(base64(new byte[32]), ChecksumType.COMPOSITE,
                ConnectorErrorCode.PROVIDER_REJECTED);
        assertGetChecksumFailure("not-base64-3", ChecksumType.COMPOSITE,
                ConnectorErrorCode.PROVIDER_REJECTED);
        assertGetChecksumFailure(base64(new byte[32]) + "-0", ChecksumType.COMPOSITE,
                ConnectorErrorCode.PROVIDER_REJECTED);
        assertGetChecksumFailure(base64(new byte[32]) + "-01", ChecksumType.COMPOSITE,
                ConnectorErrorCode.PROVIDER_REJECTED);
        assertGetChecksumFailure(base64(new byte[32]) + "-10001", ChecksumType.COMPOSITE,
                ConnectorErrorCode.PROVIDER_REJECTED);
        assertGetChecksumFailure(base64(new byte[32]) + "-3",
                ChecksumType.UNKNOWN_TO_SDK_VERSION,
                ConnectorErrorCode.PROVIDER_REJECTED);
    }

    @Test
    void wipesOwnedGetBufferWhenPostReadValidationFails() {
        byte[] bytes = {1, 2, 3, 4};
        CapturingInputStream source = new CapturingInputStream(bytes);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(base64(new byte[32]))
                .checksumType(ChecksumType.FULL_OBJECT)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", new ResponseInputStream<>(response, source));

        assertThatThrownBy(() -> client(fake.client()).get(BUCKET, KEY, null, bytes.length))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.SOURCE_MISMATCH));
        assertThat(source.capturedBuffer()).isNotNull().containsOnly(0);
        assertThat(source.capturedBuffer()).isNotSameAs(bytes);
    }

    @Test
    void wipesOwnedGetBufferWhenExactReadFails() {
        byte[] bytes = {1, 2};
        CapturingInputStream source = new CapturingInputStream(bytes);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(3L)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", new ResponseInputStream<>(response, source));

        assertThatThrownBy(() -> client(fake.client()).get(BUCKET, KEY, null, 3))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.SOURCE_MISMATCH));
        assertThat(source.capturedBuffer()).isNotNull().containsOnly(0);
        assertThat(source.capturedBuffer()).isNotSameAs(bytes);
    }

    @Test
    void wipesCompletedGetBufferWhenResponseCloseFails() {
        byte[] bytes = {1, 2, 3};
        CloseFailingCapturingInputStream source =
                new CloseFailingCapturingInputStream(bytes);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(base64(sha256(bytes)))
                .checksumType(ChecksumType.FULL_OBJECT)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", new ResponseInputStream<>(response, source));

        assertThatThrownBy(() -> client(fake.client()).get(BUCKET, KEY, null, bytes.length))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.SERVICE_UNAVAILABLE));
        assertThat(source.capturedBuffer()).isNotNull().containsOnly(0);
        assertThat(source.capturedBuffer()).isNotSameAs(bytes);
    }

    @Test
    void rejectsShortLongOversizedAndChecksumMismatchedBodies() {
        assertGetFailure(new byte[]{1, 2}, 3, 3, null, ConnectorErrorCode.SOURCE_MISMATCH);
        assertGetFailure(new byte[]{1, 2, 3, 4}, 3, 3, null,
                ConnectorErrorCode.SOURCE_MISMATCH);
        assertGetFailure(new byte[]{1, 2, 3}, 3, 2, null,
                ConnectorErrorCode.SOURCE_MISMATCH);
        assertGetFailure(new byte[]{1, 2, 3}, 3, 3, base64(new byte[32]),
                ConnectorErrorCode.SOURCE_MISMATCH);
    }

    @Test
    void normalizesGetNotFoundWithoutLeakingProviderDiagnostics() {
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", S3Exception.builder().statusCode(404)
                .message("secret endpoint and object key").build());
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThatThrownBy(() -> client.get(BUCKET, KEY, null, 12))
                .isInstanceOfSatisfying(ObjectStoreException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ConnectorErrorCode.SOURCE_UNAVAILABLE);
                    assertThat(failure.getMessage()).isEqualTo("SOURCE_UNAVAILABLE");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void conditionallyPutsExactBytesChecksumMetadataEncryptionAndRetention() throws IOException {
        byte[] bytes = {7, 8, 9};
        byte[] digest = sha256(bytes);
        Instant retainUntil = Instant.parse("2031-01-01T00:00:00Z");
        PutRequest request = new PutRequest(BUCKET, KEY, "application/cbor", bytes.length,
                digest, Map.of("yano-effect-id", "effect-1"), EncryptionMode.SSE_KMS,
                "arn:aws:kms:us-east-1:123456789012:key/abc", ObjectRetentionMode.COMPLIANCE,
                retainUntil.toEpochMilli());
        FakeS3 fake = new FakeS3();
        fake.respond("putObject", PutObjectResponse.builder()
                        .versionId("version-1")
                        .eTag("etag-1")
                        .checksumSHA256(base64(digest))
                        .checksumType(ChecksumType.FULL_OBJECT)
                        .build());
        AwsS3ObjectStoreClient client = client(fake.client());

        PutAcknowledgement result = client.putIfAbsent(request, bytes);

        assertThat(result).isEqualTo(new PutAcknowledgement("version-1", "etag-1"));
        Call call = fake.calls("putObject").getFirst();
        PutObjectRequest providerRequest = call.argument(0);
        RequestBody body = call.argument(1);
        assertThat(providerRequest.ifNoneMatch()).isEqualTo("*");
        assertThat(providerRequest.expectedBucketOwner()).isEqualTo(EXPECTED_OWNER);
        assertThat(providerRequest.contentLength()).isEqualTo(bytes.length);
        assertThat(providerRequest.checksumSHA256()).isEqualTo(base64(digest));
        assertThat(providerRequest.metadata())
                .containsExactlyEntriesOf(Map.of("yano-effect-id", "effect-1"));
        assertThat(providerRequest.serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(providerRequest.ssekmsKeyId()).isEqualTo(
                "arn:aws:kms:us-east-1:123456789012:key/abc");
        assertThat(providerRequest.objectLockMode())
                .isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(providerRequest.objectLockRetainUntilDate())
                .isEqualTo(retainUntil);
        assertThat(providerRequest.acl()).isNull();
        try (var input = body.contentStreamProvider().newStream()) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
    }

    @Test
    void rejectsMismatchedPutBeforeIoAndNormalizesUncertainMutation() {
        byte[] bytes = {1, 2, 3};
        PutRequest request = putRequest(bytes);
        FakeS3 fake = new FakeS3();
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThatThrownBy(() -> client.putIfAbsent(request, new byte[]{9, 9, 9}))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.SOURCE_MISMATCH));
        assertThat(fake.calls("putObject")).isEmpty();

        fake.respond("putObject", S3Exception.builder().statusCode(412)
                .message("secret destination").build());
        assertThatThrownBy(() -> client.putIfAbsent(request, bytes))
                .isInstanceOfSatisfying(ObjectStoreException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ConnectorErrorCode.ACK_UNKNOWN);
                    assertThat(failure.getMessage()).isEqualTo("ACK_UNKNOWN");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void reconcilesMalformedAcknowledgementAfterProviderReturned() {
        byte[] bytes = {1, 2, 3};
        PutRequest request = putRequest(bytes);
        FakeS3 fake = new FakeS3();
        fake.respond("putObject", PutObjectResponse.builder()
                .versionId("version-1")
                .checksumSHA256(base64(new byte[32]))
                .build());

        assertThatThrownBy(() -> client(fake.client()).putIfAbsent(request, bytes))
                .isInstanceOfSatisfying(ObjectStoreException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ConnectorErrorCode.ACK_UNKNOWN);
                    assertThat(failure.getMessage()).isEqualTo("ACK_UNKNOWN");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(fake.calls("putObject")).hasSize(1);
    }

    @Test
    void reconcilesNonFullObjectChecksumAndNullVersionAcknowledgements() {
        byte[] bytes = {1, 2, 3};
        byte[] digest = sha256(bytes);

        assertPutAcknowledgementUnknown(bytes, PutObjectResponse.builder()
                .versionId("version-1")
                .checksumSHA256(base64(digest) + "-3")
                .checksumType(ChecksumType.COMPOSITE)
                .build());
        assertPutAcknowledgementUnknown(bytes, PutObjectResponse.builder()
                .versionId("version-1")
                .checksumSHA256(base64(digest))
                .checksumType(ChecksumType.UNKNOWN_TO_SDK_VERSION)
                .build());
        assertPutAcknowledgementUnknown(bytes, PutObjectResponse.builder()
                .versionId("null")
                .checksumSHA256(base64(digest))
                .checksumType(ChecksumType.FULL_OBJECT)
                .build());
    }

    @Test
    void normalizesProviderAndTransportFailuresByOperation() {
        assertReadFailure(S3Exception.builder().statusCode(403).message("credential").build(),
                ConnectorErrorCode.AUTH_UNAVAILABLE);
        assertReadFailure(S3Exception.builder().statusCode(429).message("rate").build(),
                ConnectorErrorCode.RATE_LIMITED);
        assertReadFailure(S3Exception.builder().statusCode(503).message("endpoint").build(),
                ConnectorErrorCode.SERVICE_UNAVAILABLE);
        assertReadFailure(SdkClientException.create("signed URL and key"),
                ConnectorErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void sanitizesCredentialProviderFailure() {
        AwsCredentialsProvider failing = () -> {
            throw SdkClientException.create("/home/operator/.aws contains account details");
        };
        AwsS3ObjectStoreClientFactory.SanitizingCredentialsProvider provider =
                new AwsS3ObjectStoreClientFactory.SanitizingCredentialsProvider(failing);

        assertThatThrownBy(provider::resolveCredentials)
                .isInstanceOf(AwsS3ObjectStoreClientFactory.CredentialUnavailableException.class)
                .hasMessage("AUTH_UNAVAILABLE")
                .hasNoCause();
    }

    @Test
    void normalizesLocalhostWithoutDnsAndLeavesNumericLocalEndpointUntouched() {
        URI localhost = URI.create("http://localhost:9000");
        URI numeric = URI.create("http://10.0.0.2:9000");

        assertThat(AwsS3ObjectStoreClientFactory.normalizedEndpoint(
                ObjectStoreS3EffectConfig.SecurityProfile.LOCAL_DEMO, localhost))
                .isEqualTo(URI.create("http://127.0.0.1:9000"));
        assertThat(AwsS3ObjectStoreClientFactory.normalizedEndpoint(
                ObjectStoreS3EffectConfig.SecurityProfile.LOCAL_DEMO, numeric))
                .isSameAs(numeric);
    }

    @Test
    void leavesTlsProfileEndpointIdentityUntouchedWithoutDns() {
        URI endpoint = URI.create("https://s3.example.invalid:9443");

        URI result = AwsS3ObjectStoreClientFactory.normalizedEndpoint(
                ObjectStoreS3EffectConfig.SecurityProfile.TLS, endpoint);

        assertThat(result).isSameAs(endpoint);
    }

    @Test
    void closeIsIdempotentAndPostCloseCallsAreSafe() {
        FakeS3 fake = new FakeS3();
        AtomicInteger credentialCloses = new AtomicInteger();
        AutoCloseable credentials = () -> credentialCloses.incrementAndGet();
        AwsS3ObjectStoreClient client = new AwsS3ObjectStoreClient(fake.client(), credentials);

        client.close();
        client.close();

        assertThat(fake.closeCalls()).isEqualTo(1);
        assertThat(credentialCloses).hasValue(1);
        assertThatThrownBy(() -> client.bucketVersioning(BUCKET))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.SHUTDOWN));
    }

    @Test
    void managedAdapterRejectsAnUnconfiguredBucketBeforeProviderIo() {
        FakeS3 fake = new FakeS3();
        AwsS3ObjectStoreClient client = client(fake.client());

        assertThatThrownBy(() -> client.bucketVersioning("other-bucket"))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.POLICY_DENIED));
        assertThat(fake.calls("getBucketVersioning")).isEmpty();
    }

    @Test
    void customEndpointAdapterOmitsTheAwsOwnershipHeader() {
        FakeS3 fake = new FakeS3();
        fake.respond("getBucketVersioning", GetBucketVersioningResponse.builder()
                .status(BucketVersioningStatus.ENABLED).build());
        AwsS3ObjectStoreClient client = new AwsS3ObjectStoreClient(fake.client(), null);

        assertThat(client.bucketVersioning(BUCKET)).isEqualTo(BucketVersioning.ENABLED);

        GetBucketVersioningRequest request = fake.calls("getBucketVersioning")
                .getFirst().argument(0);
        assertThat(request.expectedBucketOwner()).isNull();
    }

    private static void assertGetFailure(byte[] actualBytes,
                                         long declaredSize,
                                         long maximum,
                                         String checksum,
                                         ConnectorErrorCode expected) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(declaredSize)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(checksum)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", new ResponseInputStream<>(response,
                new ByteArrayInputStream(actualBytes)));

        assertThatThrownBy(() -> client(fake.client()).get(BUCKET, KEY, null, maximum))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private static void assertGetChecksumFailure(String checksum,
                                                 ChecksumType type,
                                                 ConnectorErrorCode expected) {
        byte[] bytes = {1, 2, 3};
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .contentType("application/octet-stream")
                .metadata(Map.of())
                .checksumSHA256(checksum)
                .checksumType(type)
                .build();
        FakeS3 fake = new FakeS3();
        fake.respond("getObject", new ResponseInputStream<>(response,
                new ByteArrayInputStream(bytes)));

        assertThatThrownBy(() -> client(fake.client()).get(BUCKET, KEY, null, bytes.length))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private static void assertPutAcknowledgementUnknown(byte[] bytes,
                                                        PutObjectResponse response) {
        FakeS3 fake = new FakeS3();
        fake.respond("putObject", response);

        assertThatThrownBy(() -> client(fake.client()).putIfAbsent(putRequest(bytes), bytes))
                .isInstanceOfSatisfying(ObjectStoreException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ConnectorErrorCode.ACK_UNKNOWN));
        assertThat(fake.calls("putObject")).hasSize(1);
    }

    private static void assertReadFailure(RuntimeException providerFailure,
                                          ConnectorErrorCode expected) {
        FakeS3 fake = new FakeS3();
        fake.respond("getBucketVersioning", providerFailure);

        assertThatThrownBy(() -> client(fake.client()).bucketVersioning(BUCKET))
                .isInstanceOfSatisfying(ObjectStoreException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(expected);
                    assertThat(failure.getMessage()).isEqualTo(expected.wireCode());
                    assertThat(failure.getCause()).isNull();
                });
    }

    private static PutRequest putRequest(byte[] bytes) {
        return new PutRequest(BUCKET, KEY, "application/cbor", bytes.length,
                sha256(bytes), Map.of("yano-effect-id", "effect-1"), EncryptionMode.NONE,
                null, ObjectRetentionMode.NONE, null);
    }

    private static AwsS3ObjectStoreClient client(S3Client sdk) {
        return new AwsS3ObjectStoreClient(sdk, null, Map.of(BUCKET, EXPECTED_OWNER));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static class CapturingInputStream extends ByteArrayInputStream {
        private byte[] capturedBuffer;

        private CapturingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            capturedBuffer = target;
            return super.read(target, offset, length);
        }

        byte[] capturedBuffer() {
            return capturedBuffer;
        }
    }

    private static final class CloseFailingCapturingInputStream
            extends CapturingInputStream {
        private CloseFailingCapturingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            throw new IOException("simulated response close failure");
        }
    }

    /** Java-25-safe scripted proxy; avoids the repository's old inline Mockito agent. */
    private static final class FakeS3 implements InvocationHandler {
        private final Map<String, Deque<Object>> responses = new HashMap<>();
        private final List<Call> calls = new ArrayList<>();
        private final S3Client client = (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, this);
        private int closeCalls;

        S3Client client() {
            return client;
        }

        void respond(String method, Object... values) {
            Deque<Object> queue = responses.computeIfAbsent(method,
                    ignored -> new ArrayDeque<>());
            queue.addAll(List.of(values));
        }

        List<Call> calls(String method) {
            return calls.stream().filter(call -> call.method().equals(method)).toList();
        }

        int closeCalls() {
            return closeCalls;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "FakeS3";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected Object method");
                };
            }
            if (method.getName().equals("close")) {
                closeCalls++;
                return null;
            }
            Object[] snapshot = arguments == null ? new Object[0] : arguments.clone();
            calls.add(new Call(method.getName(), snapshot));
            Deque<Object> queue = responses.get(method.getName());
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("unexpected S3 call: " + method.getName());
            }
            Object response = queue.removeFirst();
            if (response instanceof Throwable failure) {
                throw failure;
            }
            return response;
        }
    }

    private record Call(String method, Object[] arguments) {
        @SuppressWarnings("unchecked")
        <T> T argument(int index) {
            return (T) arguments[index];
        }
    }
}
