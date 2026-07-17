package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BucketBootstrapperTest {
    @Test
    void acceptsEnabledObjectLockOnlyWithoutADefaultRetentionRule() {
        ObjectLockConfiguration expected = ObjectLockConfiguration.builder()
                .objectLockEnabled(ObjectLockEnabled.ENABLED)
                .build();

        assertThatCode(() -> S3BucketBootstrapper.requireExpectedObjectLock(expected))
                .doesNotThrowAnyException();

        ObjectLockConfiguration retainedDefault = expected.toBuilder()
                .rule(ObjectLockRule.builder()
                        .defaultRetention(DefaultRetention.builder()
                                .mode(ObjectLockRetentionMode.GOVERNANCE)
                                .days(7)
                                .build())
                        .build())
                .build();
        assertMismatch(retainedDefault);
        assertMismatch(expected.toBuilder().objectLockEnabled((String) null).build());
        assertMismatch(null);
    }

    @Test
    void recognizesOnlyTheExactNoBucketPolicyResponse() {
        assertThat(S3BucketBootstrapper.isNoBucketPolicy(
                failure(404, "NoSuchBucketPolicy"))).isTrue();
        assertThat(S3BucketBootstrapper.isNoBucketPolicy(
                failure(403, "NoSuchBucketPolicy"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoBucketPolicy(
                failure(404, "NoSuchPolicy"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoBucketPolicy(
                (S3Exception) S3Exception.builder().statusCode(404).build())).isFalse();
    }

    @Test
    void recognizesOnlyTheExactNoObjectLockConfigurationResponse() {
        assertThat(S3BucketBootstrapper.isNoObjectLockConfiguration(
                failure(404, "ObjectLockConfigurationNotFoundError"))).isTrue();
        assertThat(S3BucketBootstrapper.isNoObjectLockConfiguration(
                failure(403, "ObjectLockConfigurationNotFoundError"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoObjectLockConfiguration(
                failure(404, "NoSuchObjectLockConfiguration"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoObjectLockConfiguration(
                (S3Exception) S3Exception.builder().statusCode(404).build())).isFalse();
        assertThat(S3BucketBootstrapper.isNoObjectLockConfiguration(null)).isFalse();
    }

    @Test
    void recognizesOnlyTheExactNoLifecycleConfigurationResponse() {
        assertThat(S3BucketBootstrapper.isNoLifecycleConfiguration(
                failure(404, "NoSuchLifecycleConfiguration"))).isTrue();
        assertThat(S3BucketBootstrapper.isNoLifecycleConfiguration(
                failure(403, "NoSuchLifecycleConfiguration"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoLifecycleConfiguration(
                failure(404, "LifecycleConfigurationNotFoundError"))).isFalse();
        assertThat(S3BucketBootstrapper.isNoLifecycleConfiguration(
                (S3Exception) S3Exception.builder().statusCode(404).build())).isFalse();
        assertThat(S3BucketBootstrapper.isNoLifecycleConfiguration(null)).isFalse();
    }

    private static void assertMismatch(ObjectLockConfiguration configuration) {
        assertThatThrownBy(() ->
                S3BucketBootstrapper.requireExpectedObjectLock(configuration))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }

    private static S3Exception failure(int status, String code) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }
}
