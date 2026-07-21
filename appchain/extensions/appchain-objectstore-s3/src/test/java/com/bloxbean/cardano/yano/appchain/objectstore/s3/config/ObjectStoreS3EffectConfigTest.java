package com.bloxbean.cardano.yano.appchain.objectstore.s3.config;

import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStoreS3EffectConfigTest {
    private static final String ACCESS_KEY = "s3-demo-access";
    private static final String SECRET_KEY = "object-store-secret-canary";
    private static final String SESSION_TOKEN = "object-store-session-canary";
    private static final String KMS_KEY = "arn:aws:kms:us-east-1:123456789012:key/demo-key";
    private static final String SOURCE_OWNER = "123456789012";
    private static final String DESTINATION_OWNER = "210987654321";

    @Test
    void emptyConfigurationIsInactiveAndActivationRequiresACompleteTarget() {
        ObjectStoreS3EffectConfig empty = ObjectStoreS3EffectConfig.parse(Map.of());

        assertThat(empty.enabled()).isFalse();
        assertThat(empty.targets()).isEmpty();
        assertThat(empty.target("archive")).isEmpty();
        assertThat(empty.detailArchivePath()).isEmpty();

        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(Map.of("enabled", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled object-store executor requires at least one target");

        Map<String, String> disabledSettings = localSettings();
        disabledSettings.put("enabled", "false");
        ObjectStoreS3EffectConfig disabled = ObjectStoreS3EffectConfig.parse(disabledSettings);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.target("archive")).isPresent();
    }

    @Test
    void localDemoProfileFreezesTargetPoliciesAndDestinationResolution() {
        ObjectStoreS3EffectConfig config = ObjectStoreS3EffectConfig.parse(localSettings());
        ObjectStoreS3EffectConfig.Target target = config.target("archive").orElseThrow();

        assertThat(config.enabled()).isTrue();
        assertThat(target.alias()).isEqualTo("archive");
        assertThat(target.targetId()).isEqualTo("archive-v1");
        assertThat(target.endpoint()).hasValueSatisfying(endpoint ->
                assertThat(endpoint.toString()).isEqualTo("http://127.0.0.1:9000"));
        assertThat(target.region()).isEqualTo("us-east-1");
        assertThat(target.securityProfile())
                .isEqualTo(ObjectStoreS3EffectConfig.SecurityProfile.LOCAL_DEMO);
        assertThat(target.pathStyle()).isTrue();
        assertThat(target.credentials().provider())
                .isEqualTo(ObjectStoreS3EffectConfig.CredentialsProvider.STATIC);
        assertThat(target.credentials().accessKeyId()).contains(ACCESS_KEY);
        assertThat(target.credentials().secretAccessKey()).contains(SECRET_KEY);
        assertThat(target.credentials().sessionToken()).contains(SESSION_TOKEN);
        assertThat(target.sourceBucket()).isEqualTo("evidence-staging");
        assertThat(target.sourcePrefix()).isEqualTo("incoming/v1");
        assertThat(target.sourceExpectedOwner()).isEmpty();
        assertThat(target.destinationBucket()).isEqualTo("evidence-archive");
        assertThat(target.destinationPrefix()).isEqualTo("verified/v1");
        assertThat(target.destinationExpectedOwner()).isEmpty();
        assertThat(target.encryptionPolicyId()).isEqualTo("demo-encryption-v1");
        assertThat(target.encryption().mode())
                .isEqualTo(ObjectStoreS3EffectConfig.EncryptionMode.NONE);
        assertThat(target.retentionPolicyId()).isEqualTo("worm-v1");
        assertThat(target.requireVersioning()).isTrue();
        assertThat(target.maxObjectBytes()).isEqualTo(16 * 1024 * 1024);
        assertThat(target.timeouts()).isEqualTo(new ObjectStoreS3EffectConfig.Timeouts(
                Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(5),
                Duration.ofSeconds(15), Duration.ofSeconds(5)));

        assertThat(target.composeSourceKey("passport/batch-1.json"))
                .isEqualTo("incoming/v1/passport/batch-1.json");
        assertThat(target.composeDestinationKey("passport/batch-1.json"))
                .isEqualTo("verified/v1/passport/batch-1.json");
        assertThat(target.destinationFingerprint("passport/batch-1.json").bytes())
                .containsExactly(ObjectDestinationFingerprint.compute(
                        "archive-v1", "evidence-archive", "verified/v1",
                        "passport/batch-1.json", "demo-encryption-v1", "worm-v1").bytes());
    }

    @Test
    void resolvesRetentionFromExternalMutationTimeWithoutRetryRecalculationInputs() {
        Map<String, String> settings = localSettings();
        settings.put("targets.archive.retention-classes.regulatory.mode", "compliance");
        settings.put("targets.archive.retention-classes.regulatory.days", "365");
        settings.put("targets.archive.retention-classes.review.mode", "governance");
        settings.put("targets.archive.retention-classes.review.days", "30");
        ObjectStoreS3EffectConfig config = ObjectStoreS3EffectConfig.parse(settings);
        Instant mutationTime = Instant.parse("2026-07-16T10:15:30.123456789Z");

        ObjectStoreS3EffectConfig.ResolvedObject resolved = config.resolve(
                command("archive", "regulatory", 128), mutationTime).orElseThrow();
        ObjectStoreS3EffectConfig.ResolvedRetention retention = resolved.retention().orElseThrow();

        assertThat(resolved.sourceObjectKey()).isEqualTo("incoming/v1/staged/document.json");
        assertThat(resolved.destinationObjectKey()).isEqualTo("verified/v1/final/document.json");
        assertThat(retention.retentionClass().alias()).isEqualTo("regulatory");
        assertThat(retention.retentionClass().mode()).isEqualTo(ObjectRetentionMode.COMPLIANCE);
        assertThat(retention.retentionClass().days()).isEqualTo(365);
        assertThat(retention.retainUntil())
                .isEqualTo(Instant.parse("2027-07-16T10:15:30.123Z"));

        assertThat(config.resolve(command("missing", null, 1), mutationTime)).isEmpty();
        assertThat(config.resolve(command("archive", "missing", 1), mutationTime)).isEmpty();
        assertThat(config.resolve(command("archive", null, 1), mutationTime).orElseThrow()
                .retention()).isEmpty();
        assertThatThrownBy(() -> retention.retentionClass().retainUntil(Instant.MAX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object-store retention deadline is outside Instant range");
    }

    @Test
    void managedAwsAllowsOnlyNoEndpointVirtualHostAndAmbientCredentialProfiles() {
        for (String provider : new String[]{"environment", "default"}) {
            Map<String, String> settings = managedSettings(provider);
            ObjectStoreS3EffectConfig.Target target = ObjectStoreS3EffectConfig.parse(settings)
                    .target("archive").orElseThrow();
            assertThat(target.endpoint()).isEmpty();
            assertThat(target.pathStyle()).isFalse();
            assertThat(target.credentials().accessKeyId()).isEmpty();
            assertThat(target.credentials().profileName()).isEmpty();
            assertThat(target.sourceExpectedOwner()).contains(SOURCE_OWNER);
            assertThat(target.destinationExpectedOwner()).contains(DESTINATION_OWNER);
            assertThat(target.encryption().mode())
                    .isEqualTo(ObjectStoreS3EffectConfig.EncryptionMode.SSE_S3);
        }

        Map<String, String> profile = managedSettings("profile");
        profile.put("targets.archive.credentials.profile-name", "production-s3");
        assertThat(ObjectStoreS3EffectConfig.parse(profile).target("archive").orElseThrow()
                .credentials().profileName()).contains("production-s3");

        Map<String, String> pathStyle = managedSettings("default");
        pathStyle.put("targets.archive.path-style", "true");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(pathStyle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("managed AWS targets require virtual-host addressing");
    }

    @Test
    void managedAwsRequiresExactOwnersAndSameBucketCannotChangeIdentity() {
        Map<String, String> missingSource = managedSettings("default");
        missingSource.remove("targets.archive.source-expected-owner");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(missingSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required object-store executor setting: "
                        + "source-expected-owner");

        Map<String, String> missingDestination = managedSettings("default");
        missingDestination.remove("targets.archive.destination-expected-owner");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(missingDestination))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required object-store executor setting: "
                        + "destination-expected-owner");

        for (String owner : new String[]{"12345678901", "1234567890123", "12345678901x",
                " 123456789012", "+12345678901"}) {
            Map<String, String> malformed = managedSettings("default");
            malformed.put("targets.archive.source-expected-owner", owner);
            assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(malformed)).as(owner)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid object-store source-expected-owner");
        }

        Map<String, String> sameBucketMismatch = managedSettings("default");
        sameBucketMismatch.put("targets.archive.destination-bucket", "evidence-staging");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(sameBucketMismatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the same source and destination bucket must have one expected owner");

        sameBucketMismatch.put("targets.archive.destination-expected-owner", SOURCE_OWNER);
        ObjectStoreS3EffectConfig.Target sameBucket = ObjectStoreS3EffectConfig
                .parse(sameBucketMismatch).target("archive").orElseThrow();
        assertThat(sameBucket.sourceExpectedOwner()).contains(SOURCE_OWNER);
        assertThat(sameBucket.destinationExpectedOwner()).contains(SOURCE_OWNER);
    }

    @Test
    void customEndpointsRejectExpectedOwnersAndDiagnosticsNeverRenderThem() {
        for (Map<String, String> settings : List.of(localSettings(), customTlsSettings())) {
            settings.put("targets.archive.source-expected-owner", SOURCE_OWNER);
            settings.put("targets.archive.destination-expected-owner", DESTINATION_OWNER);
            assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(settings))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("custom object-store endpoints do not accept expected bucket owners");
        }

        ObjectStoreS3EffectConfig config = ObjectStoreS3EffectConfig.parse(
                managedSettings("default"));
        String rendered = config + " " + config.safeDiagnostics() + " "
                + config.target("archive").orElseThrow();
        assertThat(rendered).doesNotContain(SOURCE_OWNER, DESTINATION_OWNER);
    }

    @Test
    void customEndpointsRequireExplicitCredentialsAndTheSelectedTransportProfile() {
        Map<String, String> tls = customTlsSettings();
        ObjectStoreS3EffectConfig.Target target = ObjectStoreS3EffectConfig.parse(tls)
                .target("archive").orElseThrow();
        assertThat(target.endpoint()).hasValueSatisfying(endpoint ->
                assertThat(endpoint.toString()).isEqualTo("https://objects.example.com:9443"));
        assertThat(target.credentials().provider())
                .isEqualTo(ObjectStoreS3EffectConfig.CredentialsProvider.STATIC);

        Map<String, String> customAmbient = customTlsSettings();
        useCredentialProvider(customAmbient, "environment");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(customAmbient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("custom object-store endpoints require explicit static credentials");

        Map<String, String> tlsHttp = customTlsSettings();
        tlsHttp.put("targets.archive.endpoint", "http://objects.example.com:9000");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(tlsHttp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("custom TLS object-store endpoints must use HTTPS");

        for (String endpoint : new String[]{
                "http://localhost:9000",
                "http://127.0.0.1:9000", "http://10.1.2.3:9000",
                "http://172.31.1.2:9000", "http://192.168.1.2:9000",
                "http://[::1]:9000", "http://[fd00::1]:9000"}) {
            Map<String, String> local = localSettings();
            local.put("targets.archive.endpoint", endpoint);
            assertThat(ObjectStoreS3EffectConfig.parse(local).enabled()).as(endpoint).isTrue();
        }

        for (String endpoint : new String[]{
                "http://storage:9000", "http://node.local:9000",
                "http://node.localhost:9000", "http://example.com:9000",
                "http://8.8.8.8:9000",
                "http://172.32.0.1:9000", "http://169.254.169.254:80",
                "http://[fe80::1]:9000",
                "http://010.0.0.1:9000", "http://2130706433:9000",
                "https://storage:9000"}) {
            Map<String, String> local = localSettings();
            local.put("targets.archive.endpoint", endpoint);
            assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(local)).as(endpoint)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("local-demo object storage requires localhost or a private "
                            + "numeric HTTP endpoint");
        }
    }

    @Test
    void credentialsAreExactPerProviderAndNeverRendered() {
        Map<String, String> missingSecret = localSettings();
        missingSecret.remove("targets.archive.credentials.secret-access-key");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(missingSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required object-store executor setting: "
                        + "credentials.secret-access-key");

        Map<String, String> staticWithProfile = localSettings();
        staticWithProfile.put("targets.archive.credentials.profile-name", "leak-me");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(staticWithProfile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("static credentials do not accept a profile name");

        Map<String, String> environmentWithSecret = managedSettings("environment");
        environmentWithSecret.put("targets.archive.credentials.secret-access-key", SECRET_KEY);
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(environmentWithSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("environment credentials do not accept explicit credential fields");

        Map<String, String> profileWithStatic = managedSettings("profile");
        profileWithStatic.put("targets.archive.credentials.profile-name", "prod");
        profileWithStatic.put("targets.archive.credentials.access-key-id", ACCESS_KEY);
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(profileWithStatic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("profile credentials do not accept static credential fields");

        Map<String, String> settings = localSettings();
        settings.put("detail-archive-path", "/tmp/object-detail-secret-path");
        ObjectStoreS3EffectConfig config = ObjectStoreS3EffectConfig.parse(settings);
        String rendered = config + " " + config.safeDiagnostics() + " "
                + config.target("archive").orElseThrow() + " "
                + config.target("archive").orElseThrow().credentials();
        assertThat(rendered)
                .contains("archive", "local-demo")
                .doesNotContain("http://127.0.0.1:9000", "us-east-1", "evidence-staging",
                        "incoming/v1", "evidence-archive", "verified/v1", ACCESS_KEY,
                        SECRET_KEY, SESSION_TOKEN, "/tmp/object-detail-secret-path");
    }

    @Test
    void encryptionModesAreClosedAndKmsMaterialIsModeSpecific() {
        Map<String, String> localNone = localSettings();
        assertThat(ObjectStoreS3EffectConfig.parse(localNone).target("archive").orElseThrow()
                .encryption().kmsKeyId()).isEmpty();

        Map<String, String> managedNone = managedSettings("default");
        managedNone.put("targets.archive.encryption-mode", "none");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(managedNone))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unencrypted object storage is limited to local-demo targets");

        Map<String, String> sseS3WithKey = managedSettings("default");
        sseS3WithKey.put("targets.archive.kms-key-id", KMS_KEY);
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(sseS3WithKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sse-s3 does not accept a KMS key");

        Map<String, String> kms = managedSettings("default");
        kms.put("targets.archive.encryption-mode", "sse-kms");
        kms.put("targets.archive.kms-key-id", KMS_KEY);
        ObjectStoreS3EffectConfig.Encryption encryption = ObjectStoreS3EffectConfig.parse(kms)
                .target("archive").orElseThrow().encryption();
        assertThat(encryption.mode()).isEqualTo(ObjectStoreS3EffectConfig.EncryptionMode.SSE_KMS);
        assertThat(encryption.kmsKeyId()).contains(KMS_KEY);
        assertThat(encryption.toString()).doesNotContain(KMS_KEY);

        Map<String, String> managedAlias = managedSettings("default");
        managedAlias.put("targets.archive.encryption-mode", "sse-kms");
        managedAlias.put("targets.archive.kms-key-id", "alias/archive-key");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(managedAlias))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("managed AWS sse-kms requires a full key ARN in the target region");

        Map<String, String> wrongRegion = managedSettings("default");
        wrongRegion.put("targets.archive.encryption-mode", "sse-kms");
        wrongRegion.put("targets.archive.kms-key-id",
                "arn:aws:kms:eu-west-1:123456789012:key/demo-key");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(wrongRegion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("managed AWS sse-kms requires a full key ARN in the target region");

        Map<String, String> compatibleProvider = customTlsSettings();
        compatibleProvider.put("targets.archive.encryption-mode", "sse-kms");
        compatibleProvider.put("targets.archive.kms-key-id", "kes/archive-key-v1");
        assertThat(ObjectStoreS3EffectConfig.parse(compatibleProvider)
                .target("archive").orElseThrow().encryption().kmsKeyId())
                .contains("kes/archive-key-v1");

        Map<String, String> missingKms = managedSettings("default");
        missingKms.put("targets.archive.encryption-mode", "sse-kms");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(missingKms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required object-store executor setting: kms-key-id");
    }

    @Test
    void versioningSizeAndTimeoutPoliciesAreMandatoryAndCoherent() {
        Map<String, String> noVersioning = localSettings();
        noVersioning.put("targets.archive.require-versioning", "false");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(noVersioning))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object-store destination versioning is mandatory");

        Map<String, String> bounded = localSettings();
        bounded.put("targets.archive.max-object-bytes", "1048576");
        bounded.put("targets.archive.api-call-timeout-ms", "120000");
        bounded.put("targets.archive.api-call-attempt-timeout-ms", "60000");
        bounded.put("targets.archive.connect-timeout-ms", "30000");
        bounded.put("targets.archive.socket-timeout-ms", "60000");
        bounded.put("targets.archive.close-timeout-ms", "30000");
        ObjectStoreS3EffectConfig.Target target = ObjectStoreS3EffectConfig.parse(bounded)
                .target("archive").orElseThrow();
        assertThat(target.maxObjectBytes()).isEqualTo(1_048_576);
        assertThat(target.timeouts().apiCall()).isEqualTo(Duration.ofMinutes(2));
        assertThat(target.timeouts().close()).isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(bounded).resolve(
                command("archive", null, 1_048_577), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object exceeds the configured target size limit");

        assertRejectedSetting("targets.archive.max-object-bytes", "16777217");
        assertRejectedSetting("targets.archive.max-object-bytes", "01");
        assertRejectedSetting("targets.archive.api-call-timeout-ms", "999");
        assertRejectedSetting("targets.archive.close-timeout-ms", "30001");

        Map<String, String> attemptAfterApi = localSettings();
        attemptAfterApi.put("targets.archive.api-call-timeout-ms", "1000");
        attemptAfterApi.put("targets.archive.api-call-attempt-timeout-ms", "1001");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(attemptAfterApi))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object-store attempt timeout must not exceed API timeout");

        Map<String, String> transportAfterAttempt = localSettings();
        transportAfterAttempt.put("targets.archive.api-call-attempt-timeout-ms", "1000");
        transportAfterAttempt.put("targets.archive.socket-timeout-ms", "1001");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(transportAfterAttempt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object-store transport timeouts must not exceed attempt timeout");
    }

    @Test
    void settingsPrefixesBucketsAliasesAndArchivePathsFailClosed() {
        assertRejectedSetting("targets.archive.unknown", "value");
        assertRejectedSetting("arbitrary.sdk.property", "value");
        assertRejectedSetting("targets.Archive.target-id", "archive-v1");
        assertRejectedSetting("targets.archive.target-id", "Archive-v1");
        assertRejectedSetting("targets.archive.source-prefix", "../incoming");
        assertRejectedSetting("targets.archive.source-prefix", "incoming/");
        assertRejectedSetting("targets.archive.destination-prefix", "verified//v1");
        assertRejectedSetting("targets.archive.destination-prefix", "verified/%2e%2e");
        assertRejectedSetting("targets.archive.source-bucket", "bad bucket");
        assertRejectedSetting("targets.archive.retention-classes.lock.mode", "NONE");
        assertRejectedSetting("targets.archive.retention-classes.lock.days", "0");
        assertRejectedSetting("targets.archive.retention-classes.lock.days", "36501");
        assertRejectedSetting("targets.archive.retention-classes.lock.extra", "value");

        Map<String, String> incompleteRetention = localSettings();
        incompleteRetention.put("targets.archive.retention-classes.lock.mode", "governance");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(incompleteRetention))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missing required object-store executor setting: days");

        Map<String, String> duplicateId = localSettings();
        copyTarget(duplicateId, "archive", "backup");
        duplicateId.put("targets.backup.target-id", "archive-v1");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(duplicateId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate object-store target-id");

        Map<String, String> relativeArchive = localSettings();
        relativeArchive.put("detail-archive-path", "relative/archive");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(relativeArchive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an absolute normalized non-root path");

        Map<String, String> nullValue = localSettings();
        nullValue.put("targets.archive.target-id", null);
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid or oversized object-store executor value");

        Map<String, String> newline = localSettings();
        newline.put("targets.archive.endpoint", "http://127.0.0.1:9000\nsecret");
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(newline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid or oversized object-store executor value");

        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(Map.of("enabled", "TRUE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("object-store boolean setting must be true or false: enabled");
    }

    @Test
    void targetRetentionAndComposedKeyBoundsAreEnforced() {
        Map<String, String> tooManyTargets = localSettings();
        for (int index = 0; index < 16; index++) {
            copyTarget(tooManyTargets, "archive", "target-" + index);
            tooManyTargets.put("targets.target-" + index + ".target-id", "identity-" + index);
        }
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(tooManyTargets))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("too many object-store targets");

        Map<String, String> tooManyClasses = localSettings();
        for (int index = 0; index < 33; index++) {
            tooManyClasses.put("targets.archive.retention-classes.policy-" + index + ".mode",
                    "governance");
            tooManyClasses.put("targets.archive.retention-classes.policy-" + index + ".days", "1");
        }
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(tooManyClasses))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("too many object-store retention classes");

        String maximumKey = "a".repeat(128) + "/" + "b".repeat(128) + "/"
                + "c".repeat(128) + "/" + "d".repeat(125);
        assertThat(maximumKey).hasSize(512);
        Map<String, String> maximumPrefix = localSettings();
        maximumPrefix.put("targets.archive.destination-prefix", maximumKey);
        ObjectStoreS3EffectConfig.Target target = ObjectStoreS3EffectConfig.parse(maximumPrefix)
                .target("archive").orElseThrow();
        assertThatThrownBy(() -> target.composeDestinationKey(maximumKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("composed object-store key exceeds the provider limit");
    }

    @Test
    void endpointSyntaxIsCanonicalAndCannotCarryUrlsOrCredentials() {
        for (String endpoint : new String[]{
                "https://user:pass@objects.example.com", "https://objects.example.com/",
                "https://objects.example.com/path", "https://objects.example.com?x=1",
                "https://objects.example.com#fragment", "HTTPS://objects.example.com",
                "https://OBJECTS.example.com", "https://objects.example.com:0",
                "ftp://objects.example.com", "not-a-uri"}) {
            Map<String, String> settings = customTlsSettings();
            settings.put("targets.archive.endpoint", endpoint);
            assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(settings)).as(endpoint)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void mapsAndDiagnosticsAreImmutableAndRedacted() {
        ObjectStoreS3EffectConfig config = ObjectStoreS3EffectConfig.parse(localSettings());
        ObjectStoreS3EffectConfig.Target target = config.target("archive").orElseThrow();

        assertThatThrownBy(() -> config.targets().put("other", target))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> target.retentionClasses().put("other", null))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(config.safeDiagnostics())
                .containsEntry("enabled", true)
                .containsEntry("detailArchiveConfigured", false);
        assertThat(config.safeDiagnostics().toString())
                .contains("archive", "local-demo", "customEndpointConfigured")
                .doesNotContain("127.0.0.1", "9000", "evidence", "incoming", "verified",
                        ACCESS_KEY, SECRET_KEY, SESSION_TOKEN, "worm-v1", "demo-encryption-v1");
    }

    private static void assertRejectedSetting(String key, String value) {
        Map<String, String> settings = localSettings();
        settings.put(key, value);
        assertThatThrownBy(() -> ObjectStoreS3EffectConfig.parse(settings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectPutCommandV1 command(String target, String retentionClass, long size) {
        return new ObjectPutCommandV1(target, "staged/document.json", "final/document.json",
                DigestAlgorithm.SHA_256, new byte[32], size, "application/json", retentionClass);
    }

    private static Map<String, String> localSettings() {
        Map<String, String> settings = commonSettings();
        settings.put("targets.archive.endpoint", "http://127.0.0.1:9000");
        settings.put("targets.archive.security-profile", "local-demo");
        settings.put("targets.archive.path-style", "true");
        settings.put("targets.archive.credentials-provider", "static");
        settings.put("targets.archive.credentials.access-key-id", ACCESS_KEY);
        settings.put("targets.archive.credentials.secret-access-key", SECRET_KEY);
        settings.put("targets.archive.credentials.session-token", SESSION_TOKEN);
        settings.put("targets.archive.encryption-mode", "none");
        return settings;
    }

    private static Map<String, String> customTlsSettings() {
        Map<String, String> settings = commonSettings();
        settings.put("targets.archive.endpoint", "https://objects.example.com:9443");
        settings.put("targets.archive.security-profile", "tls");
        settings.put("targets.archive.path-style", "true");
        settings.put("targets.archive.credentials-provider", "static");
        settings.put("targets.archive.credentials.access-key-id", ACCESS_KEY);
        settings.put("targets.archive.credentials.secret-access-key", SECRET_KEY);
        settings.put("targets.archive.encryption-mode", "sse-s3");
        return settings;
    }

    private static Map<String, String> managedSettings(String provider) {
        Map<String, String> settings = commonSettings();
        settings.put("targets.archive.security-profile", "tls");
        settings.put("targets.archive.path-style", "false");
        settings.put("targets.archive.credentials-provider", provider);
        settings.put("targets.archive.encryption-mode", "sse-s3");
        settings.put("targets.archive.source-expected-owner", SOURCE_OWNER);
        settings.put("targets.archive.destination-expected-owner", DESTINATION_OWNER);
        return settings;
    }

    private static Map<String, String> commonSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("targets.archive.target-id", "archive-v1");
        settings.put("targets.archive.region", "us-east-1");
        settings.put("targets.archive.source-bucket", "evidence-staging");
        settings.put("targets.archive.source-prefix", "incoming/v1");
        settings.put("targets.archive.destination-bucket", "evidence-archive");
        settings.put("targets.archive.destination-prefix", "verified/v1");
        settings.put("targets.archive.encryption-policy-id", "demo-encryption-v1");
        settings.put("targets.archive.retention-policy-id", "worm-v1");
        settings.put("targets.archive.require-versioning", "true");
        return settings;
    }

    private static void useCredentialProvider(Map<String, String> settings, String provider) {
        settings.put("targets.archive.credentials-provider", provider);
        settings.remove("targets.archive.credentials.access-key-id");
        settings.remove("targets.archive.credentials.secret-access-key");
        settings.remove("targets.archive.credentials.session-token");
        settings.remove("targets.archive.credentials.profile-name");
    }

    private static void copyTarget(Map<String, String> settings, String source, String destination) {
        Map<String, String> copy = new LinkedHashMap<>();
        String prefix = "targets." + source + ".";
        settings.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                copy.put("targets." + destination + "." + key.substring(prefix.length()), value);
            }
        });
        settings.putAll(copy);
    }
}
