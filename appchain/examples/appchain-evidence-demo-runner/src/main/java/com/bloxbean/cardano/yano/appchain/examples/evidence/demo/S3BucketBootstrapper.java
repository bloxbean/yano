package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.time.Duration;

/** Idempotently provisions only the two local-demo buckets and verifies their safety controls. */
final class S3BucketBootstrapper implements AutoCloseable {
    private final S3BootstrapConfig config;
    private final S3Client client;

    S3BucketBootstrapper(S3BootstrapConfig config) {
        this.config = config;
        try {
            client = S3Client.builder()
                    .endpointOverride(config.endpoint())
                    .region(Region.of(config.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            config.accessKey().reveal(), config.secretKey().reveal())))
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
                            .pathStyleAccessEnabled(config.pathStyle())
                            .chunkedEncodingEnabled(false).build())
                    .build();
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    void bootstrap() {
        try {
            ensureBucket(config.sourceBucket(), false);
            ensureBucket(config.destinationBucket(), true);
            ensureVersioning(config.sourceBucket());
            ensureVersioning(config.destinationBucket());
            verifyVersioning(config.sourceBucket());
            verifyVersioning(config.destinationBucket());
            verifyNoObjectLockConfiguration(config.sourceBucket());
            ObjectLockConfiguration objectLock = client.getObjectLockConfiguration(
                    GetObjectLockConfigurationRequest.builder()
                            .bucket(config.destinationBucket()).build())
                    .objectLockConfiguration();
            requireExpectedObjectLock(objectLock);
            verifyNoLifecycleConfiguration(config.sourceBucket());
            verifyNoLifecycleConfiguration(config.destinationBucket());
            verifyNoBucketPolicy(config.sourceBucket());
            verifyNoBucketPolicy(config.destinationBucket());
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private void ensureBucket(String bucket, boolean objectLock) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            if (objectLock) {
                ObjectLockEnabled enabled = client.getObjectLockConfiguration(
                        GetObjectLockConfigurationRequest.builder().bucket(bucket).build())
                        .objectLockConfiguration().objectLockEnabled();
                if (enabled != ObjectLockEnabled.ENABLED) {
                    throw new DemoException(DemoError.INITIALIZATION_FAILED);
                }
            }
            return;
        } catch (S3Exception absent) {
            if (absent.statusCode() != 404) {
                throw absent;
            }
        }
        client.createBucket(CreateBucketRequest.builder()
                .bucket(bucket)
                .objectLockEnabledForBucket(objectLock ? Boolean.TRUE : null)
                .build());
    }

    private void ensureVersioning(String bucket) {
        BucketVersioningStatus current = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        if (current != BucketVersioningStatus.ENABLED) {
            client.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED).build())
                    .build());
        }
    }

    private void verifyVersioning(String bucket) {
        BucketVersioningStatus status = client.getBucketVersioning(
                GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        if (status != BucketVersioningStatus.ENABLED) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private void verifyNoObjectLockConfiguration(String bucket) {
        try {
            client.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder()
                    .bucket(bucket)
                    .build());
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        } catch (S3Exception absent) {
            if (!isNoObjectLockConfiguration(absent)) {
                throw absent;
            }
        }
    }

    private void verifyNoBucketPolicy(String bucket) {
        try {
            client.getBucketPolicy(GetBucketPolicyRequest.builder().bucket(bucket).build());
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        } catch (S3Exception absent) {
            if (!isNoBucketPolicy(absent)) {
                throw absent;
            }
        }
    }

    private void verifyNoLifecycleConfiguration(String bucket) {
        try {
            client.getBucketLifecycleConfiguration(
                    GetBucketLifecycleConfigurationRequest.builder()
                            .bucket(bucket)
                            .build());
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        } catch (S3Exception absent) {
            if (!isNoLifecycleConfiguration(absent)) {
                throw absent;
            }
        }
    }

    static void requireExpectedObjectLock(ObjectLockConfiguration configuration) {
        if (configuration == null
                || configuration.objectLockEnabled() != ObjectLockEnabled.ENABLED
                || configuration.rule() != null) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    static boolean isNoBucketPolicy(S3Exception failure) {
        return failure != null
                && failure.statusCode() == 404
                && failure.awsErrorDetails() != null
                && "NoSuchBucketPolicy".equals(failure.awsErrorDetails().errorCode());
    }

    static boolean isNoObjectLockConfiguration(S3Exception failure) {
        return failure != null
                && failure.statusCode() == 404
                && failure.awsErrorDetails() != null
                && "ObjectLockConfigurationNotFoundError".equals(
                failure.awsErrorDetails().errorCode());
    }

    static boolean isNoLifecycleConfiguration(S3Exception failure) {
        return failure != null
                && failure.statusCode() == 404
                && failure.awsErrorDetails() != null
                && "NoSuchLifecycleConfiguration".equals(
                failure.awsErrorDetails().errorCode());
    }

    @Override
    public void close() {
        client.close();
    }
}
