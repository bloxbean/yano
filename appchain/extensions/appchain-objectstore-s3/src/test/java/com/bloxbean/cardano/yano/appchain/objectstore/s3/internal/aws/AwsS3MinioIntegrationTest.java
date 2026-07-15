package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.BucketVersioning;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.EncryptionMode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.PutRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real MinIO compatibility test; ordinary check remains offline. */
@EnabledIfSystemProperty(named = "yano.s3.integration.enabled", matches = "true")
class AwsS3MinioIntegrationTest {
    @Test
    void conditionalVersionedPromotionAndNoResurrectionProfile() {
        String endpoint = requiredProperty("yano.s3.integration.endpoint");
        String accessKey = requiredProperty("yano.s3.integration.access-key");
        String secretKey = requiredProperty("yano.s3.integration.secret-key");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sourceBucket = "yano-stage-" + suffix;
        String destinationBucket = "yano-archive-" + suffix;
        String sourceKey = "incoming/evidence.cbor";
        String destinationKey = "verified/evidence.cbor";
        byte[] content = "phase-1.2-minio-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] digest = sha256(content);

        try (S3Client admin = admin(endpoint, accessKey, secretKey)) {
            admin.createBucket(CreateBucketRequest.builder().bucket(sourceBucket).build());
            admin.createBucket(CreateBucketRequest.builder().bucket(destinationBucket).build());
            admin.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(destinationBucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED).build())
                    .build());
            admin.putObject(PutObjectRequest.builder()
                            .bucket(sourceBucket)
                            .key(sourceKey)
                            .contentType("application/cbor")
                            .build(),
                    RequestBody.fromBytes(content));

            ObjectStoreS3EffectConfig.Target target = target(endpoint, accessKey, secretKey,
                    sourceBucket, destinationBucket);
            try (ObjectStoreClient client = AwsS3ObjectStoreClientFactory.INSTANCE.open(target)) {
                assertThat(client.bucketVersioning(destinationBucket))
                        .isEqualTo(BucketVersioning.ENABLED);
                assertThat(client.listVersions(destinationBucket, destinationKey, 64)
                        .anyVersionOrDeleteMarker()).isFalse();
                byte[] source = client.get(sourceBucket, sourceKey, null,
                        content.length).takeBytes();
                try {
                    assertThat(source).containsExactly(content);
                } finally {
                    Arrays.fill(source, (byte) 0);
                }

                PutRequest request = new PutRequest(destinationBucket, destinationKey,
                        "application/cbor", content.length, digest,
                        Map.of("yano-effect-id", HexFormat.of().formatHex(digest)),
                        EncryptionMode.NONE, null, ObjectRetentionMode.NONE, null);
                var acknowledgement = client.putIfAbsent(request, content);

                assertThat(acknowledgement.versionId()).isNotBlank();
                var head = client.head(destinationBucket, destinationKey,
                        acknowledgement.versionId()).orElseThrow();
                assertThat(head.versionId()).isEqualTo(acknowledgement.versionId());
                byte[] destination = client.get(destinationBucket, destinationKey,
                        acknowledgement.versionId(), content.length).takeBytes();
                try {
                    assertThat(destination).containsExactly(content);
                } finally {
                    Arrays.fill(destination, (byte) 0);
                }
                assertThat(client.listVersions(destinationBucket, destinationKey, 64)
                        .anyVersionOrDeleteMarker()).isTrue();
                assertThatThrownBy(() -> client.putIfAbsent(request, content))
                        .isInstanceOfSatisfying(ObjectStoreException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(ConnectorErrorCode.ACK_UNKNOWN));
            } finally {
                cleanup(admin, sourceBucket, sourceKey, destinationBucket);
            }
        }
    }

    private static ObjectStoreS3EffectConfig.Target target(String endpoint,
                                                            String accessKey,
                                                            String secretKey,
                                                            String sourceBucket,
                                                            String destinationBucket) {
        Map<String, String> settings = Map.ofEntries(
                Map.entry("enabled", "true"),
                Map.entry("targets.archive.target-id", "archive-v1"),
                Map.entry("targets.archive.endpoint", endpoint),
                Map.entry("targets.archive.region", "us-east-1"),
                Map.entry("targets.archive.security-profile", "local-demo"),
                Map.entry("targets.archive.path-style", "true"),
                Map.entry("targets.archive.credentials-provider", "static"),
                Map.entry("targets.archive.credentials.access-key-id", accessKey),
                Map.entry("targets.archive.credentials.secret-access-key", secretKey),
                Map.entry("targets.archive.source-bucket", sourceBucket),
                Map.entry("targets.archive.destination-bucket", destinationBucket),
                Map.entry("targets.archive.encryption-policy-id", "none-v1"),
                Map.entry("targets.archive.encryption-mode", "none"),
                Map.entry("targets.archive.retention-policy-id", "none-v1"),
                Map.entry("targets.archive.require-versioning", "true"),
                Map.entry("targets.archive.max-object-bytes", "16777216"));
        return ObjectStoreS3EffectConfig.parse(settings).target("archive").orElseThrow();
    }

    private static S3Client admin(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(15))
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false)
                                .useEnvironmentVariablesValues(false)
                                .build()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(20))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build();
    }

    private static void cleanup(S3Client admin,
                                String sourceBucket,
                                String sourceKey,
                                String destinationBucket) {
        admin.deleteObject(DeleteObjectRequest.builder()
                .bucket(sourceBucket).key(sourceKey).build());
        admin.deleteBucket(DeleteBucketRequest.builder().bucket(sourceBucket).build());

        ListObjectVersionsResponse versions = admin.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(destinationBucket).build());
        versions.versions().forEach(version -> admin.deleteObject(DeleteObjectRequest.builder()
                .bucket(destinationBucket).key(version.key()).versionId(version.versionId())
                .build()));
        versions.deleteMarkers().forEach(marker -> admin.deleteObject(
                DeleteObjectRequest.builder().bucket(destinationBucket).key(marker.key())
                        .versionId(marker.versionId()).build()));
        admin.deleteBucket(DeleteBucketRequest.builder().bucket(destinationBucket).build());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required MinIO integration property: " + name);
        }
        return value;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
