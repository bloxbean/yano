package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Live positive and safe-negative proof of the runner/executor policy split. */
final class S3IamVerifier {
    private static final byte[] PROBE = "yano-rustfs-iam-probe-v1\n".getBytes(StandardCharsets.UTF_8);
    private static final String STAGED = "incoming/v1/.yano-iam-probe-v1";
    private static final String VERIFIED = "verified/v1/.yano-iam-probe-v1";
    private static final String OUTSIDE = "outside/v1/.yano-iam-negative-v1";

    private final S3BootstrapConfig config;
    private final RustFsIamSpec spec;

    S3IamVerifier(S3BootstrapConfig config) {
        this.config = config;
        this.spec = RustFsIamSpec.load(config.iamSpecFile());
    }

    void verify() {
        try (S3Client runner = client(spec.runner().accessKey(), config.runnerSecretKey());
             S3Client executor = client(spec.executor().accessKey(), config.executorSecretKey())) {
            runner.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(config.sourceBucket()).prefix("incoming/v1/").maxKeys(1).build());
            runner.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(config.destinationBucket()).prefix("verified/v1/").maxKeys(1).build());
            runner.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(config.sourceBucket()).build());
            runner.getBucketVersioning(GetBucketVersioningRequest.builder()
                    .bucket(config.destinationBucket()).build());
            executor.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(config.sourceBucket()).prefix("incoming/v1/").maxKeys(1).build());
            executor.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(config.destinationBucket()).prefix("verified/v1/").maxKeys(1).build());

            putOnce(runner, config.sourceBucket(), STAGED);
            requireProbe(executor, config.sourceBucket(), STAGED);
            putOnce(executor, config.destinationBucket(), VERIFIED);
            requireProbe(runner, config.destinationBucket(), VERIFIED);

            requireDenied(() -> runner.putObject(PutObjectRequest.builder()
                            .bucket(config.destinationBucket()).key(VERIFIED).build(),
                    RequestBody.fromBytes(PROBE)));
            requireDenied(() -> executor.putObject(PutObjectRequest.builder()
                            .bucket(config.sourceBucket()).key(STAGED).build(),
                    RequestBody.fromBytes(PROBE)));
            requireDenied(() -> runner.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.sourceBucket()).key(STAGED).build()));
            requireDenied(() -> executor.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.destinationBucket()).key(VERIFIED).build()));
            requireOutsidePrefixDenied(runner, config.sourceBucket());
            requireOutsidePrefixDenied(runner, config.destinationBucket());
            requireOutsidePrefixDenied(executor, config.sourceBucket());
            requireOutsidePrefixDenied(executor, config.destinationBucket());

            requireProbe(executor, config.sourceBucket(), STAGED);
            requireProbe(runner, config.destinationBucket(), VERIFIED);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private void requireOutsidePrefixDenied(S3Client client, String bucket) {
        requireDenied(() -> client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix("outside/v1/").maxKeys(1).build()));
        requireDenied(() -> requireProbe(client, bucket, OUTSIDE));
        requireDenied(() -> client.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(OUTSIDE).build(), RequestBody.fromBytes(PROBE)));
    }

    private S3Client client(String accessKey, SecretValue secretKey) {
        return S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey.reveal())))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(20))
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false)
                                .useEnvironmentVariablesValues(false).build())
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15)).build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyle())
                        .chunkedEncodingEnabled(false).build())
                .build();
    }

    private void putOnce(S3Client client, String bucket, String key) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType("application/octet-stream").ifNoneMatch("*").build(),
                    RequestBody.fromBytes(PROBE));
        } catch (S3Exception existing) {
            if (existing.statusCode() != 412) {
                throw existing;
            }
        }
        requireProbe(client, bucket, key);
    }

    private void requireProbe(S3Client client, String bucket, String key) {
        try (ResponseInputStream<GetObjectResponse> object = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] actual = readProbeBody(object);
            if (!Arrays.equals(actual, PROBE)) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    static byte[] readProbeBody(ResponseInputStream<GetObjectResponse> object) throws IOException {
        Long declared = object.response().contentLength();
        if (declared != null && declared != PROBE.length) {
            object.abort();
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        byte[] actual;
        try {
            actual = object.readNBytes(PROBE.length + 1);
        } catch (IOException failure) {
            object.abort();
            throw failure;
        }
        if (actual.length != PROBE.length) {
            object.abort();
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return actual;
    }

    private void requireDenied(Runnable operation) {
        try {
            operation.run();
        } catch (S3Exception denied) {
            if (denied.statusCode() == 403) {
                return;
            }
            throw denied;
        }
        throw new DemoException(DemoError.INITIALIZATION_FAILED);
    }
}
