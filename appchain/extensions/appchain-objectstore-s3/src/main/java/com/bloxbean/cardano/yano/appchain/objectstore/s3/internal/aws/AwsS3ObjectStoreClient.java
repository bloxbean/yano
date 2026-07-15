package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutAcknowledgement;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObject;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.StoredObjectMetadata;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.VersionInventory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, synchronous AWS SDK adapter for the frozen object.put profile. */
public final class AwsS3ObjectStoreClient implements ObjectStoreClient {
    private static final int MAX_LIST_ENTRIES = 64;
    private static final int MAX_LIST_PAGES = 8;

    private final S3Client client;
    private final AutoCloseable credentials;
    private final Map<String, String> expectedBucketOwners;
    private final AtomicBoolean closed = new AtomicBoolean();

    AwsS3ObjectStoreClient(S3Client client, AutoCloseable credentials) {
        this(client, credentials, Map.of());
    }

    AwsS3ObjectStoreClient(S3Client client,
                           AutoCloseable credentials,
                           Map<String, String> expectedBucketOwners) {
        this.client = Objects.requireNonNull(client, "client");
        this.credentials = credentials;
        this.expectedBucketOwners = validateExpectedBucketOwners(expectedBucketOwners);
    }

    @Override
    public BucketVersioning bucketVersioning(String bucket) {
        ensureOpen();
        String safeBucket = boundedAscii(bucket, 255);
        try {
            GetBucketVersioningRequest.Builder request = GetBucketVersioningRequest.builder()
                    .bucket(safeBucket);
            expectedBucketOwner(safeBucket).ifPresent(request::expectedBucketOwner);
            GetBucketVersioningResponse response = client.getBucketVersioning(request.build());
            return switch (response.status()) {
                case ENABLED -> BucketVersioning.ENABLED;
                case SUSPENDED -> BucketVersioning.DISABLED;
                case UNKNOWN_TO_SDK_VERSION -> BucketVersioning.UNKNOWN;
                case null -> BucketVersioning.DISABLED;
            };
        } catch (RuntimeException failure) {
            throw classify(failure, Operation.READ, false);
        }
    }

    @Override
    public VersionInventory listVersions(String bucket, String exactKey, int maxEntries) {
        ensureOpen();
        String safeBucket = boundedAscii(bucket, 255);
        String safeKey = boundedAscii(exactKey, 1_024);
        Optional<String> expectedOwner = expectedBucketOwner(safeBucket);
        if (maxEntries < 1 || maxEntries > MAX_LIST_ENTRIES) {
            throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
        }

        int examined = 0;
        int pages = 0;
        boolean found = false;
        String keyMarker = null;
        String versionMarker = null;
        Set<String> observedMarkers = new HashSet<>();
        try {
            while (true) {
                if (++pages > MAX_LIST_PAGES) {
                    throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
                }
                int remaining = maxEntries - examined;
                if (remaining <= 0) {
                    throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
                }
                ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                        .bucket(safeBucket)
                        .prefix(safeKey)
                        .maxKeys(remaining);
                expectedOwner.ifPresent(request::expectedBucketOwner);
                if (keyMarker != null) {
                    request.keyMarker(keyMarker);
                }
                if (versionMarker != null) {
                    request.versionIdMarker(versionMarker);
                }
                ListObjectVersionsResponse response = client.listObjectVersions(request.build());
                int pageEntries = response.versions().size() + response.deleteMarkers().size();
                if (pageEntries <= 0 && Boolean.TRUE.equals(response.isTruncated())
                        || pageEntries > remaining) {
                    throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
                }
                examined += pageEntries;
                found |= response.versions().stream().anyMatch(version -> safeKey.equals(version.key()));
                found |= response.deleteMarkers().stream()
                        .anyMatch(marker -> safeKey.equals(marker.key()));
                if (!Boolean.TRUE.equals(response.isTruncated())) {
                    return new VersionInventory(examined, found);
                }
                keyMarker = response.nextKeyMarker();
                versionMarker = response.nextVersionIdMarker();
                String marker = String.valueOf(keyMarker) + '\u0000' + versionMarker;
                if (keyMarker == null || !observedMarkers.add(marker)) {
                    throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
                }
            }
        } catch (ObjectStoreException normalized) {
            throw new ObjectStoreException(normalized.code());
        } catch (RuntimeException failure) {
            throw classify(failure, Operation.READ, false);
        }
    }

    @Override
    public Optional<StoredObjectMetadata> head(String bucket, String key, String versionId) {
        ensureOpen();
        String safeBucket = boundedAscii(bucket, 255);
        HeadObjectRequest.Builder request = HeadObjectRequest.builder()
                .bucket(safeBucket)
                .key(boundedAscii(key, 1_024))
                .checksumMode(ChecksumMode.ENABLED);
        expectedBucketOwner(safeBucket).ifPresent(request::expectedBucketOwner);
        optionalAscii(versionId, 1_024).ifPresent(request::versionId);
        try {
            HeadObjectResponse response = client.headObject(request.build());
            validateHeadChecksum(response.checksumSHA256(), response.checksumType());
            return Optional.of(metadata(response.versionId(), response.eTag(),
                    response.contentLength(), response.contentType(), response.metadata(),
                    response.serverSideEncryptionAsString(), response.ssekmsKeyId(),
                    response.objectLockModeAsString(),
                    response.objectLockRetainUntilDate()));
        } catch (S3Exception notFound) {
            if (notFound.statusCode() == 404) {
                return Optional.empty();
            }
            throw classify(notFound, Operation.READ, false);
        } catch (ObjectStoreException normalized) {
            throw new ObjectStoreException(normalized.code());
        } catch (RuntimeException failure) {
            throw classify(failure, Operation.READ, false);
        }
    }

    @Override
    public StoredObject get(String bucket, String key, String versionId, long maxBytes) {
        ensureOpen();
        if (maxBytes < 0 || maxBytes > ObjectPutCommandV1.MAX_OBJECT_BYTES) {
            throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
        }
        String safeBucket = boundedAscii(bucket, 255);
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(safeBucket)
                .key(boundedAscii(key, 1_024))
                .checksumMode(ChecksumMode.ENABLED);
        expectedBucketOwner(safeBucket).ifPresent(request::expectedBucketOwner);
        optionalAscii(versionId, 1_024).ifPresent(request::versionId);
        byte[] ownedBytes = null;
        StoredObjectMetadata metadata = null;
        try (ResponseInputStream<GetObjectResponse> body = client.getObject(request.build())) {
            GetObjectResponse response = body.response();
            Long contentLength = response.contentLength();
            if (contentLength == null || contentLength < 0 || contentLength > maxBytes
                    || contentLength > Integer.MAX_VALUE) {
                throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
            }
            ownedBytes = readExact(body, contentLength.intValue());
            byte[] sha256 = sha256(ownedBytes);
            validateGetChecksum(response.checksumSHA256(), response.checksumType(), sha256);
            metadata = metadata(response.versionId(), response.eTag(), contentLength,
                    response.contentType(), response.metadata(),
                    response.serverSideEncryptionAsString(), response.ssekmsKeyId(),
                    response.objectLockModeAsString(), response.objectLockRetainUntilDate());
        } catch (S3Exception notFound) {
            wipe(ownedBytes);
            if (notFound.statusCode() == 404) {
                throw new ObjectStoreException(ConnectorErrorCode.SOURCE_UNAVAILABLE);
            }
            throw classify(notFound, Operation.READ, false);
        } catch (ObjectStoreException normalized) {
            wipe(ownedBytes);
            throw new ObjectStoreException(normalized.code());
        } catch (IOException malformedBody) {
            wipe(ownedBytes);
            throw new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
        } catch (RuntimeException failure) {
            wipe(ownedBytes);
            throw classify(failure, Operation.READ, false);
        } catch (Error fatal) {
            wipe(ownedBytes);
            throw fatal;
        }
        try {
            return new StoredObject(metadata, ownedBytes);
        } catch (RuntimeException | Error failure) {
            Arrays.fill(ownedBytes, (byte) 0);
            throw failure;
        }
    }

    @Override
    public PutAcknowledgement putIfAbsent(PutRequest request, byte[] bytes) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        if (bytes == null || bytes.length != request.contentLength()
                || !MessageDigest.isEqual(sha256(bytes), request.sha256())) {
            throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
        }
        String checksum = Base64.getEncoder().encodeToString(request.sha256());
        String safeBucket = boundedAscii(request.bucket(), 255);
        PutObjectRequest.Builder providerRequest = PutObjectRequest.builder()
                .bucket(safeBucket)
                .key(request.key())
                .contentType(request.contentType())
                .contentLength(request.contentLength())
                .checksumSHA256(checksum)
                .metadata(request.userMetadata())
                .ifNoneMatch("*");
        expectedBucketOwner(safeBucket).ifPresent(providerRequest::expectedBucketOwner);
        applyEncryption(providerRequest, request);
        applyRetention(providerRequest, request);
        try {
            RequestBody body = RequestBody.fromContentProvider(
                    () -> new ByteArrayInputStream(bytes), bytes.length, request.contentType());
            PutObjectResponse response = client.putObject(
                    providerRequest.build(), body);
            validatePutChecksum(response.checksumSHA256(), response.checksumType(),
                    request.sha256());
            return new PutAcknowledgement(response.versionId(), response.eTag());
        } catch (ObjectStoreException invalidAcknowledgement) {
            // The provider call returned before response validation failed. The
            // mutation may exist and must be reconciled before any later PUT.
            throw new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
        } catch (RuntimeException failure) {
            throw classify(failure, Operation.MUTATION, false);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        boolean failed = false;
        try {
            client.close();
        } catch (RuntimeException ignored) {
            failed = true;
        }
        if (credentials != null) {
            try {
                credentials.close();
            } catch (Exception ignored) {
                failed = true;
            }
        }
        if (failed) {
            throw new ObjectStoreException(ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ObjectStoreException(ConnectorErrorCode.SHUTDOWN);
        }
    }

    private static byte[] readExact(ResponseInputStream<GetObjectResponse> input, int size)
            throws IOException {
        byte[] bytes = new byte[size];
        try {
            int offset = 0;
            while (offset < size) {
                int read = input.read(bytes, offset, size - offset);
                if (read < 0) {
                    throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
                }
                if (read == 0) {
                    int one = input.read();
                    if (one < 0) {
                        throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
                    }
                    bytes[offset++] = (byte) one;
                } else {
                    offset += read;
                }
            }
            if (input.read() != -1) {
                throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
            }
            return bytes;
        } catch (IOException | RuntimeException | Error failure) {
            Arrays.fill(bytes, (byte) 0);
            throw failure;
        }
    }

    private static StoredObjectMetadata metadata(String versionId,
                                                 String etag,
                                                 Long length,
                                                 String contentType,
                                                 Map<String, String> userMetadata,
                                                 String encryption,
                                                 String kmsKeyId,
                                                 String lockMode,
                                                 Instant retainUntil) {
        if (length == null) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        try {
            ObjectRetentionMode retentionMode = retention(lockMode, retainUntil);
            return new StoredObjectMetadata(versionId, etag, length, contentType,
                    userMetadata, encryption(encryption), kmsKeyId, retentionMode,
                    retainUntil == null ? null : retainUntil.toEpochMilli());
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
    }

    private static EncryptionMode encryption(String value) {
        if (value == null) {
            return EncryptionMode.NONE;
        }
        if (ServerSideEncryption.AES256.toString().equals(value)) {
            return EncryptionMode.SSE_S3;
        }
        if (ServerSideEncryption.AWS_KMS.toString().equals(value)) {
            return EncryptionMode.SSE_KMS;
        }
        throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
    }

    private static ObjectRetentionMode retention(String value, Instant retainUntil) {
        if (value == null && retainUntil == null) {
            return ObjectRetentionMode.NONE;
        }
        if (ObjectLockMode.GOVERNANCE.toString().equals(value) && retainUntil != null) {
            return ObjectRetentionMode.GOVERNANCE;
        }
        if (ObjectLockMode.COMPLIANCE.toString().equals(value) && retainUntil != null) {
            return ObjectRetentionMode.COMPLIANCE;
        }
        throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
    }

    private static void applyEncryption(PutObjectRequest.Builder builder, PutRequest request) {
        switch (request.encryptionMode()) {
            case NONE -> { }
            case SSE_S3 -> builder.serverSideEncryption(ServerSideEncryption.AES256);
            case SSE_KMS -> builder.serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(request.kmsKeyId());
        }
    }

    private static void applyRetention(PutObjectRequest.Builder builder, PutRequest request) {
        if (request.retentionMode() == ObjectRetentionMode.NONE) {
            return;
        }
        ObjectLockMode mode = request.retentionMode() == ObjectRetentionMode.GOVERNANCE
                ? ObjectLockMode.GOVERNANCE : ObjectLockMode.COMPLIANCE;
        builder.objectLockMode(mode)
                .objectLockRetainUntilDate(Instant.ofEpochMilli(
                        Objects.requireNonNull(request.retainUntilEpochMillis())));
    }

    private static void validateOptionalChecksum(String encoded) {
        if (encoded == null) {
            return;
        }
        try {
            if (Base64.getDecoder().decode(encoded).length != 32) {
                throw new IllegalArgumentException("invalid checksum");
            }
        } catch (IllegalArgumentException malformed) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
    }

    private static void validateChecksum(String encoded, byte[] expected) {
        if (encoded == null) {
            return;
        }
        try {
            byte[] actual = Base64.getDecoder().decode(encoded);
            if (actual.length != 32 || !MessageDigest.isEqual(actual, expected)) {
                throw new ObjectStoreException(ConnectorErrorCode.SOURCE_MISMATCH);
            }
        } catch (IllegalArgumentException malformed) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
    }

    private static void validateGetChecksum(String encoded,
                                            ChecksumType type,
                                            byte[] expected) {
        if (type == ChecksumType.UNKNOWN_TO_SDK_VERSION) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        if (type == ChecksumType.COMPOSITE) {
            validateCompositeChecksum(encoded);
            return;
        }
        validateChecksum(encoded, expected);
    }

    private static void validateHeadChecksum(String encoded, ChecksumType type) {
        if (type == ChecksumType.UNKNOWN_TO_SDK_VERSION) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        if (type == ChecksumType.COMPOSITE) {
            validateCompositeChecksum(encoded);
            return;
        }
        validateOptionalChecksum(encoded);
    }

    private static void validatePutChecksum(String encoded,
                                            ChecksumType type,
                                            byte[] expected) {
        if (type == ChecksumType.COMPOSITE
                || type == ChecksumType.UNKNOWN_TO_SDK_VERSION) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        validateChecksum(encoded, expected);
    }

    /**
     * Validates AWS's canonical {@code base64-digest-partCount} representation.
     * A provider that declares COMPOSITE but returns a plain digest is rejected
     * rather than silently treating that digest as a full-object checksum.
     */
    private static void validateCompositeChecksum(String encoded) {
        if (encoded == null) {
            return;
        }
        int separator = encoded.lastIndexOf('-');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        String digest = encoded.substring(0, separator);
        String partCount = encoded.substring(separator + 1);
        if (partCount.length() > 5 || partCount.charAt(0) < '1'
                || partCount.charAt(0) > '9') {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        int parsedCount = 0;
        for (int index = 0; index < partCount.length(); index++) {
            char character = partCount.charAt(index);
            if (character < '0' || character > '9') {
                throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
            }
            parsedCount = parsedCount * 10 + character - '0';
        }
        if (parsedCount > 10_000) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(digest);
            if (decoded.length != 32
                    || !Base64.getEncoder().encodeToString(decoded).equals(digest)) {
                throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
            }
        } catch (IllegalArgumentException malformed) {
            throw new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private Optional<String> expectedBucketOwner(String bucket) {
        if (expectedBucketOwners.isEmpty()) {
            return Optional.empty();
        }
        String owner = expectedBucketOwners.get(bucket);
        if (owner == null) {
            throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
        }
        return Optional.of(owner);
    }

    private static Map<String, String> validateExpectedBucketOwners(
            Map<String, String> configured) {
        Objects.requireNonNull(configured, "expectedBucketOwners");
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            boundedAscii(entry.getKey(), 255);
            String owner = boundedAscii(entry.getValue(), 12);
            if (owner.length() != 12
                    || owner.chars().anyMatch(character -> character < '0' || character > '9')) {
                throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
            }
        }
        return Map.copyOf(configured);
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String boundedAscii(String value, int maxBytes) {
        return optionalAscii(value, maxBytes)
                .orElseThrow(() -> new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED));
    }

    private static Optional<String> optionalAscii(String value, int maxBytes) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isEmpty() || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.US_ASCII).length > maxBytes) {
            throw new ObjectStoreException(ConnectorErrorCode.POLICY_DENIED);
        }
        return Optional.of(value);
    }

    private static ObjectStoreException classify(RuntimeException failure,
                                                   Operation operation,
                                                   boolean notFoundIsSource) {
        if (failure instanceof ObjectStoreException normalized) {
            return normalized;
        }
        if (hasCredentialFailure(failure)) {
            return new ObjectStoreException(ConnectorErrorCode.AUTH_UNAVAILABLE);
        }
        if (failure instanceof S3Exception service) {
            int status = service.statusCode();
            String errorCode = service.awsErrorDetails() == null
                    ? null : service.awsErrorDetails().errorCode();
            if (status == 401 || status == 403) {
                return new ObjectStoreException(ConnectorErrorCode.AUTH_UNAVAILABLE);
            }
            if (status == 429 || "SlowDown".equals(errorCode)
                    || "Throttling".equals(errorCode)) {
                return new ObjectStoreException(ConnectorErrorCode.RATE_LIMITED);
            }
            if (status == 404 && notFoundIsSource) {
                return new ObjectStoreException(ConnectorErrorCode.SOURCE_UNAVAILABLE);
            }
            if (operation == Operation.MUTATION
                    && (status == 408 || status == 409 || status == 412 || status >= 500)) {
                return new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
            }
            if (status == 408 || status >= 500) {
                return new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
            }
            return new ObjectStoreException(ConnectorErrorCode.PROVIDER_REJECTED);
        }
        if (operation == Operation.MUTATION) {
            return new ObjectStoreException(ConnectorErrorCode.ACK_UNKNOWN);
        }
        if (failure instanceof SdkClientException) {
            return new ObjectStoreException(ConnectorErrorCode.SERVICE_UNAVAILABLE);
        }
        return new ObjectStoreException(ConnectorErrorCode.INTERNAL_ERROR);
    }

    private static boolean hasCredentialFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof AwsS3ObjectStoreClientFactory.CredentialUnavailableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private enum Operation { READ, MUTATION }
}
