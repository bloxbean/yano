package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.testing.IntegrationSecretFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in executor-level compatibility test against a real S3-compatible service. */
@EnabledIfSystemProperty(named = "yano.s3.integration.enabled", matches = "true")
class S3ObjectPutExecutorS3IntegrationTest {
    private static final String TARGET_ALIAS = "archive";
    private static final String TARGET_ID = "archive-v1";
    private static final String SOURCE_KEY = "evidence.json";
    private static final String DESTINATION_KEY = "evidence.json";
    private static final String COMPOSED_SOURCE_KEY = "staged/" + SOURCE_KEY;
    private static final String COMPOSED_DESTINATION_KEY = "verified/" + DESTINATION_KEY;
    private static final String CONTENT_TYPE = "application/json";
    private static final byte[] CONTENT = "{\"passport\":\"P-ADR-013\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void executorConfirmsOnceReconcilesAfterRestartAndNeverResurrectsDeletedKey() {
        String endpoint = requiredProperty("yano.s3.integration.endpoint");
        String accessKey = IntegrationSecretFiles.read(
                "yano.s3.integration.access-key-file");
        String secretKey = IntegrationSecretFiles.read(
                "yano.s3.integration.secret-key-file");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String chainId = "s3-real-" + suffix;
        String sourceBucket = "yano-e2e-src-" + suffix;
        String destinationBucket = "yano-e2e-dst-" + suffix;
        Map<String, String> settings = settings(endpoint, accessKey, secretKey,
                sourceBucket, destinationBucket);
        ObjectPutCommandV1 command = new ObjectPutCommandV1(TARGET_ALIAS,
                SOURCE_KEY, DESTINATION_KEY, DigestAlgorithm.SHA_256, sha256(CONTENT),
                CONTENT.length, CONTENT_TYPE, null);
        PendingEffect effect = effect(chainId, command);

        try (S3Client admin = admin(endpoint, accessKey, secretKey)) {
            try {
                stage(admin, sourceBucket, destinationBucket);

                EffectExecution.Confirmed first = executeConfirmed(chainId, settings, effect);
                ObjectPutReceiptV1 firstReceipt = ObjectPutReceiptV1.decode(first.externalRef());
                Inventory afterFirst = inventory(admin, destinationBucket,
                        COMPOSED_DESTINATION_KEY);

                assertThat(afterFirst.versionIds()).hasSize(1);
                assertThat(afterFirst.deleteMarkerIds()).isEmpty();
                String providerVersionId = afterFirst.versionIds().getFirst();
                assertThat(providerVersionId).isNotBlank();
                assertThat(firstReceipt.verifiedSha256()).isEqualTo(sha256(CONTENT));
                assertThat(firstReceipt.size()).isEqualTo(CONTENT.length);
                assertThat(firstReceipt.objectVersionFingerprint()).isEqualTo(
                        ObjectVersionFingerprint.compute(providerVersionId).bytes());
                assertThat(firstReceipt.destinationFingerprint()).isEqualTo(
                        ObjectStoreS3EffectConfig.parse(settings)
                                .resolve(command, Instant.EPOCH).orElseThrow()
                                .destinationFingerprint().bytes());
                assertStoredObject(admin, destinationBucket, providerVersionId, effect);

                // A new factory/executor with no submittedRef must reconcile the
                // immutable current version and must not issue another write.
                EffectExecution.Confirmed afterRestart = executeConfirmed(
                        chainId, settings, effect);
                assertThat(afterRestart.externalRef()).isEqualTo(first.externalRef());
                assertThat(inventory(admin, destinationBucket, COMPOSED_DESTINATION_KEY))
                        .isEqualTo(afterFirst);

                var deletion = admin.deleteObject(DeleteObjectRequest.builder()
                        .bucket(destinationBucket).key(COMPOSED_DESTINATION_KEY).build());
                assertThat(deletion.deleteMarker()).isTrue();
                assertThat(deletion.versionId()).isNotBlank();
                Inventory afterDelete = inventory(admin, destinationBucket,
                        COMPOSED_DESTINATION_KEY);
                assertThat(afterDelete.versionIds()).containsExactly(providerVersionId);
                assertThat(afterDelete.deleteMarkerIds()).containsExactly(deletion.versionId());

                EffectExecution conflict = execute(chainId, settings, effect);
                assertThat(conflict).isInstanceOfSatisfying(EffectExecution.Failed.class,
                        failed -> {
                            assertThat(failed.reason()).isEqualTo(
                                    ConnectorErrorCode.DESTINATION_CONFLICT.wireCode());
                            assertThat(failed.retryable()).isFalse();
                        });
                // The delete marker remains current: no retry may resurrect the key.
                assertThat(inventory(admin, destinationBucket, COMPOSED_DESTINATION_KEY))
                        .isEqualTo(afterDelete);
            } finally {
                deleteBucketAndAllVersions(admin, destinationBucket);
                deleteBucketAndAllVersions(admin, sourceBucket);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "yano.s3.integration.phase", matches = "seed")
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void seedsDurableObjectForServiceRestart() {
        RestartScenario scenario = restartScenario();
        boolean seeded = false;
        try (S3Client admin = admin(scenario.endpoint(), scenario.accessKey(),
                scenario.secretKey())) {
            deleteBucketAndAllVersions(admin, scenario.destinationBucket());
            deleteBucketAndAllVersions(admin, scenario.sourceBucket());
            try {
                stage(admin, scenario.sourceBucket(), scenario.destinationBucket());
                executeConfirmed(scenario.chainId(), scenario.settings(), scenario.effect());
                assertThat(inventory(admin, scenario.destinationBucket(),
                        COMPOSED_DESTINATION_KEY).versionIds()).hasSize(1);
                seeded = true;
            } finally {
                if (!seeded) {
                    deleteBucketAndAllVersions(admin, scenario.destinationBucket());
                    deleteBucketAndAllVersions(admin, scenario.sourceBucket());
                }
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "yano.s3.integration.phase", matches = "reconcile")
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void reconcilesDurableObjectAfterServiceRestartWithoutAnotherVersion() {
        RestartScenario scenario = restartScenario();
        try (S3Client admin = admin(scenario.endpoint(), scenario.accessKey(),
                scenario.secretKey())) {
            try {
                Inventory before = inventory(admin, scenario.destinationBucket(),
                        COMPOSED_DESTINATION_KEY);
                assertThat(before.versionIds()).hasSize(1);
                assertThat(before.deleteMarkerIds()).isEmpty();

                executeConfirmed(scenario.chainId(), scenario.settings(), scenario.effect());

                assertThat(inventory(admin, scenario.destinationBucket(),
                        COMPOSED_DESTINATION_KEY)).isEqualTo(before);
            } finally {
                deleteBucketAndAllVersions(admin, scenario.destinationBucket());
                deleteBucketAndAllVersions(admin, scenario.sourceBucket());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "yano.s3.integration.phase", matches = "unavailable")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void unavailableServiceFailsClosedWithRetryableNormalizedCode() {
        RestartScenario scenario = restartScenario();

        assertThat(execute(scenario.chainId(), scenario.settings(), scenario.effect()))
                .isInstanceOfSatisfying(EffectExecution.Failed.class, failed -> {
                    assertThat(failed.reason()).isEqualTo(
                            ConnectorErrorCode.SERVICE_UNAVAILABLE.wireCode());
                    assertThat(failed.retryable()).isTrue();
                });
    }

    private static EffectExecution.Confirmed executeConfirmed(String chainId,
                                                               Map<String, String> settings,
                                                               PendingEffect effect) {
        EffectExecution result = execute(chainId, settings, effect);
        assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
        return (EffectExecution.Confirmed) result;
    }

    private static EffectExecution execute(String chainId,
                                           Map<String, String> settings,
                                           PendingEffect effect) {
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory();
        List<AppEffectExecutor> contributions = factory.create(chainId, settings);
        assertThat(contributions).singleElement()
                .isInstanceOf(S3ObjectPutExecutor.class);
        try (AppEffectExecutor executor = contributions.getFirst()) {
            return executor.execute(context(chainId), effect);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static PendingEffect effect(String chainId, ObjectPutCommandV1 command) {
        EffectRecord record = new EffectRecord(EffectRecord.RECORD_VERSION, chainId,
                17, 0, S3ObjectPutExecutor.TYPE, command.encode(), "s3-compatible",
                FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 100, null);
        return PendingEffect.of(record);
    }

    private static EffectExecutionContext context(String chainId) {
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return 20; }
            @Override public long anchoredHeight() { return 19; }
            @Override public int attempt() { return 1; }
            @Override public byte[] submittedRef() { return new byte[0]; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    private static void stage(S3Client admin,
                              String sourceBucket,
                              String destinationBucket) {
        admin.createBucket(CreateBucketRequest.builder().bucket(sourceBucket).build());
        admin.createBucket(CreateBucketRequest.builder().bucket(destinationBucket).build());
        admin.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(destinationBucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED).build())
                .build());
        admin.putObject(PutObjectRequest.builder()
                        .bucket(sourceBucket).key(COMPOSED_SOURCE_KEY)
                        .contentType(CONTENT_TYPE).build(),
                RequestBody.fromBytes(CONTENT));
    }

    private static void assertStoredObject(S3Client admin,
                                           String destinationBucket,
                                           String versionId,
                                           PendingEffect effect) {
        var head = admin.headObject(HeadObjectRequest.builder()
                .bucket(destinationBucket).key(COMPOSED_DESTINATION_KEY)
                .versionId(versionId).build());
        assertThat(head.versionId()).isEqualTo(versionId);
        assertThat(head.contentLength()).isEqualTo(CONTENT.length);
        assertThat(head.contentType()).isEqualTo(CONTENT_TYPE);
        assertThat(head.serverSideEncryptionAsString()).isNull();
        assertThat(head.objectLockModeAsString()).isNull();
        assertThat(head.objectLockRetainUntilDate()).isNull();

        Map<String, String> expectedMetadata = new LinkedHashMap<>();
        expectedMetadata.put("yano-schema", "1");
        expectedMetadata.put("yano-action", S3ObjectPutExecutor.TYPE);
        expectedMetadata.put("yano-effect-id", HexFormat.of().formatHex(effect.idHash()));
        expectedMetadata.put("yano-sha256", HexFormat.of().formatHex(sha256(CONTENT)));
        expectedMetadata.put("yano-size", Integer.toString(CONTENT.length));
        expectedMetadata.put("yano-content-type", CONTENT_TYPE);
        expectedMetadata.put("yano-target-id", TARGET_ID);
        expectedMetadata.put("yano-encryption-policy", "plain-demo-v1");
        expectedMetadata.put("yano-retention-policy", "none-v1");
        expectedMetadata.put("yano-retention-class", "none");
        expectedMetadata.put("yano-retain-until", "none");
        assertThat(head.metadata()).isEqualTo(expectedMetadata);

        var body = admin.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(destinationBucket).key(COMPOSED_DESTINATION_KEY)
                .versionId(versionId).build());
        assertThat(body.asByteArray()).isEqualTo(CONTENT);
        assertThat(sha256(body.asByteArray())).isEqualTo(sha256(CONTENT));
    }

    private static Inventory inventory(S3Client admin, String bucket, String exactKey) {
        List<String> versions = new ArrayList<>();
        List<String> deleteMarkers = new ArrayList<>();
        admin.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                        .bucket(bucket).prefix(exactKey).build())
                .forEach(page -> {
                    page.versions().stream().filter(value -> exactKey.equals(value.key()))
                            .map(value -> value.versionId()).forEach(versions::add);
                    page.deleteMarkers().stream().filter(value -> exactKey.equals(value.key()))
                            .map(value -> value.versionId()).forEach(deleteMarkers::add);
                });
        return new Inventory(List.copyOf(versions), List.copyOf(deleteMarkers));
    }

    private static Map<String, String> settings(String endpoint,
                                                String accessKey,
                                                String secretKey,
                                                String sourceBucket,
                                                String destinationBucket) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("enabled", "true");
        String prefix = "targets." + TARGET_ALIAS + ".";
        settings.put(prefix + "target-id", TARGET_ID);
        settings.put(prefix + "endpoint", endpoint);
        settings.put(prefix + "region", "us-east-1");
        settings.put(prefix + "security-profile", "local-demo");
        settings.put(prefix + "path-style", "true");
        settings.put(prefix + "credentials-provider", "static");
        settings.put(prefix + "credentials.access-key-id", accessKey);
        settings.put(prefix + "credentials.secret-access-key", secretKey);
        settings.put(prefix + "source-bucket", sourceBucket);
        settings.put(prefix + "source-prefix", "staged");
        settings.put(prefix + "destination-bucket", destinationBucket);
        settings.put(prefix + "destination-prefix", "verified");
        settings.put(prefix + "encryption-policy-id", "plain-demo-v1");
        settings.put(prefix + "encryption-mode", "none");
        settings.put(prefix + "retention-policy-id", "none-v1");
        settings.put(prefix + "require-versioning", "true");
        settings.put(prefix + "max-object-bytes", "16777216");
        settings.put(prefix + "api-call-timeout-ms", "3000");
        settings.put(prefix + "api-call-attempt-timeout-ms", "2000");
        settings.put(prefix + "connect-timeout-ms", "500");
        settings.put(prefix + "socket-timeout-ms", "1500");
        settings.put(prefix + "close-timeout-ms", "500");
        return Map.copyOf(settings);
    }

    private static RestartScenario restartScenario() {
        String runId = requiredProperty("yano.s3.integration.run-id");
        if (!runId.matches("[a-z0-9]{6,20}")) {
            throw new IllegalStateException("Invalid S3 integration run id");
        }
        String suffix = runId.substring(0, Math.min(runId.length(), 12));
        String endpoint = requiredProperty("yano.s3.integration.endpoint");
        String accessKey = IntegrationSecretFiles.read(
                "yano.s3.integration.access-key-file");
        String secretKey = IntegrationSecretFiles.read(
                "yano.s3.integration.secret-key-file");
        String chainId = "restart-" + suffix;
        String sourceBucket = "yano-restart-src-" + suffix;
        String destinationBucket = "yano-restart-dst-" + suffix;
        Map<String, String> settings = settings(endpoint, accessKey, secretKey,
                sourceBucket, destinationBucket);
        ObjectPutCommandV1 command = new ObjectPutCommandV1(TARGET_ALIAS,
                SOURCE_KEY, DESTINATION_KEY, DigestAlgorithm.SHA_256, sha256(CONTENT),
                CONTENT.length, CONTENT_TYPE, null);
        return new RestartScenario(endpoint, accessKey, secretKey, chainId,
                sourceBucket, destinationBucket, settings, effect(chainId, command));
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
                                .useEnvironmentVariablesValues(false).build()))
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

    private static void deleteBucketAndAllVersions(S3Client admin, String bucket) {
        try {
            List<DeleteObjectRequest> deletions = new ArrayList<>();
            admin.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                            .bucket(bucket).build())
                    .forEach(page -> {
                        page.versions().forEach(version -> deletions.add(
                                deleteRequest(bucket, version.key(), version.versionId())));
                        page.deleteMarkers().forEach(marker -> deletions.add(
                                deleteRequest(bucket, marker.key(), marker.versionId())));
                    });
            deletions.forEach(admin::deleteObject);
            admin.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                    .contents().forEach(object -> admin.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(object.key()).build()));
            try {
                admin.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception providerQuirk) {
                if (providerQuirk.statusCode() != 409
                        || !Boolean.getBoolean("yano.s3.integration.disposable-service")) {
                    throw providerQuirk;
                }
                assertThat(admin.listObjectVersions(
                        ListObjectVersionsRequest.builder().bucket(bucket).build()).versions())
                        .isEmpty();
                assertThat(admin.listObjectVersions(
                        ListObjectVersionsRequest.builder().bucket(bucket).build()).deleteMarkers())
                        .isEmpty();
                assertThat(admin.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket).build()).contents())
                        .isEmpty();
            }
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) {
                throw missing;
            }
        }
    }

    private static DeleteObjectRequest deleteRequest(String bucket,
                                                     String key,
                                                     String versionId) {
        DeleteObjectRequest.Builder request = DeleteObjectRequest.builder()
                .bucket(bucket).key(key);
        if (versionId != null && !"null".equals(versionId)) {
            request.versionId(versionId);
        }
        return request.build();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required S3 integration property: " + name);
        }
        return value;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Inventory(List<String> versionIds, List<String> deleteMarkerIds) {
    }

    private record RestartScenario(String endpoint,
                                   String accessKey,
                                   String secretKey,
                                   String chainId,
                                   String sourceBucket,
                                   String destinationBucket,
                                   Map<String, String> settings,
                                   PendingEffect effect) {
    }
}
