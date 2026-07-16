package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/** Least-privilege S3-compatible staging and external-state verifier. */
final class S3DemoStore implements AutoCloseable {
    private static final int MAX_OBJECT_BYTES = 16_777_216;

    private final DemoConfig.S3Settings settings;
    private final S3Client client;

    S3DemoStore(DemoConfig.S3Settings settings) {
        this.settings = settings;
        try {
            client = S3Client.builder()
                    .endpointOverride(settings.endpoint())
                    .region(Region.of(settings.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            settings.accessKey().reveal(), settings.secretKey().reveal())))
                    .httpClient(UrlConnectionHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(5))
                            .socketTimeout(Duration.ofSeconds(30))
                            .proxyConfiguration(ProxyConfiguration.builder()
                                    .useSystemPropertyValues(false)
                                    .useEnvironmentVariablesValues(false).build())
                            .build())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(Duration.ofSeconds(20)).build())
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(settings.pathStyle())
                            .chunkedEncodingEnabled(false).build())
                    .build();
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    /** Validates pre-provisioned least-privilege buckets and mandatory archive versioning. */
    void validate() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(settings.sourceBucket()).build());
            client.headBucket(HeadBucketRequest.builder().bucket(settings.destinationBucket()).build());
            BucketVersioningStatus status = client.getBucketVersioning(
                    GetBucketVersioningRequest.builder()
                            .bucket(settings.destinationBucket()).build()).status();
            if (status != BucketVersioningStatus.ENABLED) {
                throw new DemoException(DemoError.INITIALIZATION_FAILED);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    /** Creates the staging object once, or proves an existing object is byte-identical. */
    void stage(String relativeKey, byte[] document, byte[] sha256) {
        if (document.length > MAX_OBJECT_BYTES || sha256.length != 32) {
            throw new DemoException(DemoError.SAMPLE_INVALID);
        }
        String key = key(settings.sourcePrefix(), relativeKey);
        Existing existing = read(settings.sourceBucket(), key);
        if (existing != null) {
            requireContent(existing.bytes(), document.length, sha256);
            return;
        }
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(settings.sourceBucket())
                            .key(key)
                            .contentType("application/octet-stream")
                            .contentLength((long) document.length)
                            .metadata(Map.of("yano-sha256", Digests.hex(sha256)))
                            .ifNoneMatch("*")
                            .build(), RequestBody.fromBytes(document));
        } catch (S3Exception conflict) {
            if (conflict.statusCode() != 409 && conflict.statusCode() != 412) {
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
        Existing reconciled = read(settings.sourceBucket(), key);
        if (reconciled == null) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        requireContent(reconciled.bytes(), document.length, sha256);
    }

    /** Re-downloads the immutable archive version and binds it to the receipt. */
    DestinationAudit verifyDestination(String relativeKey, ObjectPutReceiptV1 receipt) {
        Existing existing = read(settings.destinationBucket(),
                key(settings.destinationPrefix(), relativeKey));
        if (existing == null || existing.versionId() == null || existing.versionId().isBlank()) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        requireContent(existing.bytes(), receipt.size(), receipt.verifiedSha256());
        byte[] versionFingerprint = ObjectVersionFingerprint.compute(existing.versionId()).bytes();
        if (!Arrays.equals(versionFingerprint, receipt.objectVersionFingerprint())) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return new DestinationAudit(Digests.hex(versionFingerprint), existing.bytes().length);
    }

    private Existing read(String bucket, String key) {
        try {
            try (ResponseInputStream<GetObjectResponse> object = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                Long declared = object.response().contentLength();
                if (declared != null && declared > MAX_OBJECT_BYTES) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                byte[] bytes = object.readNBytes(MAX_OBJECT_BYTES + 1);
                if (bytes.length > MAX_OBJECT_BYTES) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                return new Existing(bytes, object.response().versionId());
            }
        } catch (S3Exception absent) {
            if (absent.statusCode() == 404 || "NoSuchKey".equals(absent.awsErrorDetails().errorCode())) {
                return null;
            }
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        }
    }

    private static void requireContent(byte[] actual, long expectedSize, byte[] expectedDigest) {
        if (actual.length != expectedSize
                || !Arrays.equals(Digests.sha256(actual), expectedDigest)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static String key(String prefix, String relative) {
        return prefix == null || prefix.isEmpty() ? relative : prefix + "/" + relative;
    }

    @Override
    public void close() {
        client.close();
    }

    record DestinationAudit(String versionFingerprint, long size) {
    }

    private record Existing(byte[] bytes, String versionId) {
    }
}
