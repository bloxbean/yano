package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectLockConfigurationRequest;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BucketBootstrapperRealIntegrationTest {
    private static final String ENABLED = "yano.s3.bootstrap.integration.enabled";
    private static final String ENDPOINT = "yano.s3.bootstrap.integration.endpoint";
    private static final String ACCESS_FILE = "yano.s3.bootstrap.integration.access-key-file";
    private static final String SECRET_FILE = "yano.s3.bootstrap.integration.secret-key-file";
    private static final String SOURCE_BUCKET = "evidence-staging";
    private static final String DESTINATION_BUCKET = "evidence-archive";

    @Test
    void retentionControlDriftFailsClosedAgainstRealRustFs() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(ENABLED, "false")),
                "real RustFS bootstrap integration is opt-in");
        S3BootstrapConfig config = config();

        assertThatCode(() -> bootstrap(config)).doesNotThrowAnyException();

        try (S3Client root = rootClient(config)) {
            String unexpectedPolicy = """
                    {"Version":"2012-10-17","Statement":[{
                      "Sid":"UnexpectedRetainedPolicy",
                      "Effect":"Allow",
                      "Principal":"*",
                      "Action":"s3:GetObject",
                      "Resource":"arn:aws:s3:::evidence-staging/incoming/v1/*"
                    }]}
                    """.replaceAll("\\s+", "");
            root.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(SOURCE_BUCKET).policy(unexpectedPolicy).build());
            try {
                assertExternalStateMismatch(() -> bootstrap(config));
            } finally {
                root.deleteBucketPolicy(DeleteBucketPolicyRequest.builder()
                        .bucket(SOURCE_BUCKET).build());
            }

            ObjectLockConfiguration retainedDefault = ObjectLockConfiguration.builder()
                    .objectLockEnabled(ObjectLockEnabled.ENABLED)
                    .rule(ObjectLockRule.builder()
                            .defaultRetention(DefaultRetention.builder()
                                    .mode(ObjectLockRetentionMode.GOVERNANCE)
                                    .days(1)
                                    .build())
                            .build())
                    .build();
            root.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                    .bucket(DESTINATION_BUCKET)
                    .objectLockConfiguration(retainedDefault)
                    .build());
            assertThat(root.getObjectLockConfiguration(
                            GetObjectLockConfigurationRequest.builder()
                                    .bucket(DESTINATION_BUCKET).build())
                    .objectLockConfiguration().rule()).isNotNull();
            assertExternalStateMismatch(() -> bootstrap(config));

            root.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                    .bucket(DESTINATION_BUCKET)
                    .objectLockConfiguration(ObjectLockConfiguration.builder()
                            .objectLockEnabled(ObjectLockEnabled.ENABLED)
                            .build())
                    .build());
            assertThatCode(() -> bootstrap(config)).doesNotThrowAnyException();

            assertLifecycleDriftFailsClosed(root, config, SOURCE_BUCKET);
            assertLifecycleDriftFailsClosed(root, config, DESTINATION_BUCKET);

            String lockedSourceBucket = "yano-source-lock-drift-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            root.createBucket(CreateBucketRequest.builder()
                    .bucket(lockedSourceBucket)
                    .objectLockEnabledForBucket(true)
                    .build());
            try {
                root.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                        .bucket(lockedSourceBucket)
                        .objectLockConfiguration(retainedDefault)
                        .build());
                assertThat(root.getObjectLockConfiguration(
                                GetObjectLockConfigurationRequest.builder()
                                        .bucket(lockedSourceBucket).build())
                        .objectLockConfiguration().rule()).isNotNull();

                S3BootstrapConfig lockedSourceConfig = withSourceBucket(
                        config, lockedSourceBucket);
                assertExternalStateMismatch(() -> bootstrap(lockedSourceConfig));
            } finally {
                root.putObjectLockConfiguration(PutObjectLockConfigurationRequest.builder()
                        .bucket(lockedSourceBucket)
                        .objectLockConfiguration(ObjectLockConfiguration.builder()
                                .objectLockEnabled(ObjectLockEnabled.ENABLED)
                                .build())
                        .build());
                root.deleteBucket(DeleteBucketRequest.builder()
                        .bucket(lockedSourceBucket)
                        .build());
            }
        }
    }

    private static void assertLifecycleDriftFailsClosed(
            S3Client root, S3BootstrapConfig config, String bucket) {
        LifecycleRule deletionRule = LifecycleRule.builder()
                .id("unexpected-delete-" + bucket)
                .prefix(bucket.equals(SOURCE_BUCKET) ? "incoming/v1/" : "verified/v1/")
                .status(ExpirationStatus.ENABLED)
                .expiration(LifecycleExpiration.builder().days(1).build())
                .build();
        root.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest.builder()
                        .bucket(bucket)
                        .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                                .rules(deletionRule)
                                .build())
                        .build());
        try {
            assertExternalStateMismatch(() -> bootstrap(config));
        } finally {
            root.deleteBucketLifecycle(DeleteBucketLifecycleRequest.builder()
                    .bucket(bucket)
                    .build());
        }
        assertThatCode(() -> bootstrap(config)).doesNotThrowAnyException();
    }

    private static void bootstrap(S3BootstrapConfig config) {
        try (S3BucketBootstrapper bootstrapper = new S3BucketBootstrapper(config)) {
            bootstrapper.bootstrap();
        }
    }

    private static void assertExternalStateMismatch(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }

    private static S3BootstrapConfig config() {
        String endpoint = required(ENDPOINT);
        SecretValue access = SecretFiles.read(Path.of(required(ACCESS_FILE)));
        SecretValue secret = SecretFiles.read(Path.of(required(SECRET_FILE)));
        return new S3BootstrapConfig(URI.create(endpoint), "us-east-1", access, secret,
                Path.of("unused-by-bucket-bootstrap"), secret, secret,
                SOURCE_BUCKET, DESTINATION_BUCKET, true);
    }

    private static S3BootstrapConfig withSourceBucket(
            S3BootstrapConfig config, String sourceBucket) {
        return new S3BootstrapConfig(
                config.endpoint(), config.region(), config.accessKey(), config.secretKey(),
                config.iamSpecFile(), config.runnerSecretKey(), config.executorSecretKey(),
                sourceBucket, config.destinationBucket(), config.pathStyle());
    }

    private static S3Client rootClient(S3BootstrapConfig config) {
        return S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                config.accessKey().reveal(), config.secretKey().reveal())))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(20))
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false)
                                .useEnvironmentVariablesValues(false)
                                .build())
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing integration property: " + property);
        }
        return value;
    }
}
