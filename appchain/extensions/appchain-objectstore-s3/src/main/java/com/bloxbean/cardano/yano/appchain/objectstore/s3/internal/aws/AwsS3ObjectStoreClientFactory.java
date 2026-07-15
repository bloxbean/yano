package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClientFactory;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds one explicitly bounded AWS SDK v2 client for each effect executor. */
public enum AwsS3ObjectStoreClientFactory implements ObjectStoreClientFactory {
    /** Stateless factory instance used by the plugin contribution. */
    INSTANCE;

    @Override
    public ObjectStoreClient open(ObjectStoreS3EffectConfig.Target target) {
        Objects.requireNonNull(target, "target");
        SanitizingCredentialsProvider credentials = null;
        try {
            Map<String, String> expectedOwners = expectedBucketOwners(target);
            credentials = new SanitizingCredentialsProvider(credentials(target.credentials()));
            ObjectStoreS3EffectConfig.Timeouts timeouts = target.timeouts();
            ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(timeouts.apiCall())
                    .apiCallAttemptTimeout(timeouts.apiCallAttempt())
                    // A mutating PUT must never be repeated invisibly inside the SDK. The
                    // Effect Runtime owns retry and first reconciles provider state.
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                    .build();
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(target.region()))
                    .credentialsProvider(credentials)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(timeouts.connect())
                            .socketTimeout(timeouts.socket())
                            // Implicit JVM/environment proxies are outside the frozen target
                            // policy and could receive signed requests or static credentials.
                            .proxyConfiguration(ProxyConfiguration.builder()
                                    .useSystemPropertyValues(false)
                                    .useEnvironmentVariablesValues(false)
                                    .build()))
                    .overrideConfiguration(override)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(target.pathStyle())
                            .build());
            target.endpoint().map(endpoint -> normalizedEndpoint(
                            target.securityProfile(), endpoint))
                    .ifPresent(builder::endpointOverride);
            return new AwsS3ObjectStoreClient(builder.build(), credentials, expectedOwners);
        } catch (RuntimeException failure) {
            closeCredentials(credentials);
            if (failure instanceof ObjectStoreException normalized) {
                throw normalized;
            }
            throw new ObjectStoreException(ConnectorErrorCode.INTERNAL_ERROR);
        }
    }

    private static Map<String, String> expectedBucketOwners(
            ObjectStoreS3EffectConfig.Target target) {
        if (target.endpoint().isPresent()) {
            return Map.of();
        }
        String sourceOwner = target.sourceExpectedOwner().orElseThrow();
        String destinationOwner = target.destinationExpectedOwner().orElseThrow();
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(target.sourceBucket(), sourceOwner);
        String previous = owners.put(target.destinationBucket(), destinationOwner);
        if (previous != null && !previous.equals(destinationOwner)) {
            throw new ObjectStoreException(ConnectorErrorCode.INTERNAL_ERROR);
        }
        return Map.copyOf(owners);
    }

    static URI normalizedEndpoint(ObjectStoreS3EffectConfig.SecurityProfile profile,
                                  URI endpoint) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(endpoint, "endpoint");
        if (profile != ObjectStoreS3EffectConfig.SecurityProfile.LOCAL_DEMO) {
            return endpoint;
        }
        // Local-demo configuration accepts only canonical numeric private/loopback
        // literals and the exact name "localhost". Canonicalize localhost without
        // consulting the platform resolver so executor construction remains bounded.
        if ("localhost".equals(endpoint.getHost())) {
            return URI.create("http://127.0.0.1"
                    + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort()));
        }
        return endpoint;
    }

    private static AwsCredentialsProvider credentials(
            ObjectStoreS3EffectConfig.Credentials configured) {
        return switch (configured.provider()) {
            case STATIC -> StaticCredentialsProvider.create(staticCredentials(configured));
            case ENVIRONMENT -> EnvironmentVariableCredentialsProvider.create();
            case PROFILE -> ProfileCredentialsProvider.builder()
                    .profileName(configured.profileName().orElseThrow())
                    .build();
            case DEFAULT -> DefaultCredentialsProvider.builder()
                    .asyncCredentialUpdateEnabled(false)
                    .build();
        };
    }

    private static AwsCredentials staticCredentials(
            ObjectStoreS3EffectConfig.Credentials configured) {
        String accessKey = configured.accessKeyId().orElseThrow();
        String secretKey = configured.secretAccessKey().orElseThrow();
        return configured.sessionToken()
                .<AwsCredentials>map(token -> AwsSessionCredentials.create(
                        accessKey, secretKey, token))
                .orElseGet(() -> AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static void closeCredentials(SanitizingCredentialsProvider credentials) {
        if (credentials == null) {
            return;
        }
        try {
            credentials.close();
        } catch (RuntimeException ignored) {
            // Construction is already failing and no vendor failure is allowed to escape.
        }
    }

    /**
     * Prevents credential-provider diagnostics (which may contain file names or
     * endpoint details) from crossing the connector boundary.
     */
    static final class SanitizingCredentialsProvider
            implements AwsCredentialsProvider, AutoCloseable {
        private final AwsCredentialsProvider delegate;

        SanitizingCredentialsProvider(AwsCredentialsProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public AwsCredentials resolveCredentials() {
            try {
                return delegate.resolveCredentials();
            } catch (RuntimeException unavailable) {
                throw new CredentialUnavailableException();
            }
        }

        @Override
        public void close() {
            if (delegate instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    throw new ObjectStoreException(ConnectorErrorCode.INTERNAL_ERROR);
                }
            }
        }
    }

    /** Safe marker recognized without inspecting a vendor message. */
    static final class CredentialUnavailableException extends RuntimeException {
        CredentialUnavailableException() {
            super(ConnectorErrorCode.AUTH_UNAVAILABLE.wireCode());
        }
    }
}
