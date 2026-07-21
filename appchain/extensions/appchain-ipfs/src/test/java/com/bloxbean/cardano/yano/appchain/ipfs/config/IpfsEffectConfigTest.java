package com.bloxbean.cardano.yano.appchain.ipfs.config;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsV1Policy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpfsEffectConfigTest {
    private static final String TOKEN = "ipfs-bearer-token.canary_013+/=";
    private static final String TARGET_ID = "local-kubo-v1";
    private static final String REPLICATION_POLICY = "demo-single";

    @Test
    void emptyConfigurationIsInactiveAndActivationRequiresACompleteTarget() {
        IpfsEffectConfig empty = IpfsEffectConfig.parse(Map.of());

        assertThat(empty.enabled()).isFalse();
        assertThat(empty.targets()).isEmpty();
        assertThat(empty.target("local")).isEmpty();
        assertThat(empty.target(null)).isEmpty();
        assertThat(empty.detailArchivePath()).isEmpty();

        assertThatThrownBy(() -> IpfsEffectConfig.parse(Map.of("enabled", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled IPFS executor requires at least one target");

        Map<String, String> disabledSettings = localSettings();
        disabledSettings.put("enabled", "false");
        IpfsEffectConfig disabled = IpfsEffectConfig.parse(disabledSettings);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.target("local")).isPresent();
    }

    @Test
    void localDemoProfileFreezesTargetPolicyAndFingerprintsTargetId() {
        Map<String, String> settings = localSettings();
        settings.put("detail-archive-path", "/var/lib/yano/ipfs-details");
        IpfsEffectConfig config = IpfsEffectConfig.parse(settings);
        IpfsEffectConfig.Target target = config.target("local").orElseThrow();

        assertThat(config.enabled()).isTrue();
        assertThat(config.detailArchivePath()).contains(Path.of("/var/lib/yano/ipfs-details"));
        assertThat(target.alias()).isEqualTo("local");
        assertThat(target.targetId()).isEqualTo(TARGET_ID);
        assertThat(target.apiUrl().toString()).isEqualTo("http://127.0.0.1:5001");
        assertThat(target.securityProfile()).isEqualTo(IpfsEffectConfig.SecurityProfile.LOCAL_DEMO);
        assertThat(target.securityProfile().configValue()).isEqualTo("local-demo");
        assertThat(target.allowedCodecs())
                .containsExactlyInAnyOrder(IpfsV1Policy.RAW_CODEC, CanonicalCid.DAG_PB_CODEC);
        assertThat(target.allowsCodec(IpfsV1Policy.RAW_CODEC)).isTrue();
        assertThat(target.allowsCodec(CanonicalCid.DAG_PB_CODEC)).isTrue();
        assertThat(target.allowsCodec(0x71)).isFalse();
        assertThat(target.recursive()).isTrue();
        assertThat(target.replicationPolicy()).isEqualTo(REPLICATION_POLICY);
        assertThat(target.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(target.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(target.closeTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(target.bearerToken()).isEmpty();
        assertThat(target.targetFingerprint())
                .isEqualTo(IpfsTargetFingerprint.compute(TARGET_ID));
    }

    @Test
    void bearerTlsRequiresHttpsAndAnExplicitBoundedToken() {
        Map<String, String> settings = bearerSettings();
        IpfsEffectConfig.Target target = IpfsEffectConfig.parse(settings)
                .target("remote").orElseThrow();

        assertThat(target.apiUrl().toString()).isEqualTo("https://pins.example.com:443");
        assertThat(target.securityProfile()).isEqualTo(IpfsEffectConfig.SecurityProfile.BEARER_TLS);
        assertThat(target.securityProfile().configValue()).isEqualTo("bearer-tls");
        assertThat(target.bearerToken()).contains(TOKEN);

        settings.remove("targets.remote.bearer-token");
        assertInvalid(settings, "bearer-tls IPFS targets require bearer-token");

        settings = bearerSettings();
        settings.put("targets.remote.api-url", "http://pins.example.com:5001");
        assertInvalid(settings, "bearer-tls IPFS targets require HTTPS");

        for (String invalid : List.of("token with spaces", "token\tvalue", "token:value",
                "token=value", "")) {
            settings = bearerSettings();
            settings.put("targets.remote.bearer-token", invalid);
            assertInvalid(settings, "invalid IPFS bearer-token");
        }

        settings = bearerSettings();
        settings.put("targets.remote.bearer-token", "x".repeat(2_049));
        assertInvalid(settings, "invalid IPFS bearer-token");
    }

    @Test
    void everyTargetFieldIsRequiredExceptBearerTokenForLocalDemo() {
        List<String> required = List.of("target-id", "api-url", "security-profile",
                "allowed-codecs", "recursive", "replication-policy", "connect-timeout-ms",
                "request-timeout-ms", "close-timeout-ms");
        for (String field : required) {
            Map<String, String> settings = localSettings();
            settings.remove("targets.local." + field);
            assertInvalid(settings, "missing required IPFS executor setting: " + field);
        }

        Map<String, String> localWithToken = localSettings();
        localWithToken.put("targets.local.bearer-token", TOKEN);
        assertInvalid(localWithToken,
                "local-demo IPFS targets do not accept bearer authentication");
    }

    @Test
    void localDemoAcceptsOnlyCanonicalLocalNumericOriginsOrExactLocalhost() {
        for (String origin : List.of(
                "http://localhost:5001",
                "http://127.0.0.1:5001",
                "http://10.0.0.1:5001",
                "http://172.16.0.1:5001",
                "http://172.31.255.255:5001",
                "http://192.168.1.2:5001",
                "http://[::1]:5001",
                "http://[fc00::1]:5001",
                "http://[fd12:3456:789a::1]:5001")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.api-url", origin);
            assertThat(IpfsEffectConfig.parse(settings).target("local").orElseThrow().apiUrl())
                    .hasToString(origin);
        }

        for (String origin : List.of(
                "http://ipfs:5001",
                "http://node.local:5001",
                "http://example.com:5001",
                "http://8.8.8.8:5001",
                "http://172.15.0.1:5001",
                "http://172.32.0.1:5001",
                "http://169.254.1.1:5001",
                "http://[fe80::1]:5001")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.api-url", origin);
            assertInvalid(settings, "local-demo IPFS targets require a local numeric HTTP origin");
        }
    }

    @Test
    void apiUrlIsAnExactCanonicalOrigin() {
        for (String origin : List.of(
                "HTTP://127.0.0.1:5001",
                "http://LOCALHOST:5001",
                "http://127.00.0.1:5001",
                "http://127.0.0.1:0",
                "http://user@127.0.0.1:5001",
                "http://127.0.0.1:5001/",
                "http://127.0.0.1:5001/api/v0",
                "http://127.0.0.1:5001?q=x",
                "http://127.0.0.1:5001#fragment",
                "http://[FD00::1]:5001",
                "http://[fd00:0:0:0:0:0:0:1]:5001")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.api-url", origin);
            assertThatThrownBy(() -> IpfsEffectConfig.parse(settings))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        Map<String, String> noPort = bearerSettings();
        noPort.put("targets.remote.api-url", "https://pins.example.com");
        assertThat(IpfsEffectConfig.parse(noPort).target("remote").orElseThrow().apiUrl())
                .hasToString("https://pins.example.com");
    }

    @Test
    void codecsUseTheFrozenCanonicalListAndResolveTheCommandCodec() {
        for (String accepted : List.of("raw", "dag-pb", "raw,dag-pb")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.allowed-codecs", accepted);
            assertThat(IpfsEffectConfig.parse(settings).target("local")).isPresent();
        }
        for (String invalid : List.of("", "raw,", "raw,raw", "dag-pb,raw",
                "raw, dag-pb", "dag-cbor", "RAW")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.allowed-codecs", invalid);
            assertInvalid(settings, invalid.isEmpty()
                    ? "missing required IPFS executor setting: allowed-codecs"
                    : "invalid or non-canonical IPFS allowed-codecs");
        }

        IpfsEffectConfig config = IpfsEffectConfig.parse(localSettings());
        assertThat(config.resolve(command("local", rawCid(), true, null))).isPresent();
        assertThat(config.resolve(command("local", rawCid(), true, REPLICATION_POLICY))).isPresent();
        assertThat(config.resolve(command("missing", rawCid(), true, null))).isEmpty();
        assertThat(config.resolve(command("local", rawCid(), false, null))).isEmpty();
        assertThat(config.resolve(command("local", rawCid(), true, "other-policy"))).isEmpty();

        Map<String, String> dagOnly = localSettings();
        dagOnly.put("targets.local.allowed-codecs", "dag-pb");
        assertThat(IpfsEffectConfig.parse(dagOnly)
                .resolve(command("local", rawCid(), true, null))).isEmpty();
        assertThat(IpfsEffectConfig.parse(dagOnly)
                .resolve(command("local", dagPbCid(), true, null))).isPresent();
        assertThatThrownBy(() -> config.resolve(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void booleansAliasesAndTargetIdsAreStrictAndUnique() {
        for (String invalid : List.of("TRUE", "False", "1", " true", "true ")) {
            Map<String, String> settings = localSettings();
            settings.put("targets.local.recursive", invalid);
            assertInvalid(settings,
                    "IPFS boolean setting must be true or false: recursive");
        }

        Map<String, String> settings = localSettings();
        settings.put("enabled", "yes");
        assertInvalid(settings, "IPFS boolean setting must be true or false: enabled");

        settings = localSettings();
        settings.put("targets.Local.target-id", "other-v1");
        assertInvalid(settings, "invalid IPFS target alias");

        settings = localSettings();
        settings.put("targets.local.target-id", "not_valid");
        assertInvalid(settings, "invalid IPFS target-id");

        settings = localSettings();
        addTarget(settings, "backup", TARGET_ID, "http://127.0.0.2:5001");
        assertInvalid(settings, "duplicate IPFS target-id");

        settings = localSettings();
        settings.put("targets.local.replication-policy", "not_valid");
        assertInvalid(settings, "invalid IPFS replication-policy");
    }

    @Test
    void timeoutsAreMandatoryBoundedAndCoherent() {
        for (String key : List.of("connect-timeout-ms", "request-timeout-ms", "close-timeout-ms")) {
            for (String invalid : List.of("0", "-1", "+1", "01", "300001", "9999999999")) {
                Map<String, String> settings = localSettings();
                settings.put("targets.local." + key, invalid);
                assertThatThrownBy(() -> IpfsEffectConfig.parse(settings))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        Map<String, String> settings = localSettings();
        settings.put("targets.local.connect-timeout-ms", "30001");
        assertInvalid(settings,
                "IPFS connect and close timeouts must not exceed request timeout");

        settings = localSettings();
        settings.put("targets.local.close-timeout-ms", "30001");
        assertInvalid(settings,
                "IPFS connect and close timeouts must not exceed request timeout");

        settings = localSettings();
        settings.put("targets.local.connect-timeout-ms", "30000");
        settings.put("targets.local.close-timeout-ms", "30000");
        assertThat(IpfsEffectConfig.parse(settings).target("local")).isPresent();
    }

    @Test
    void archivePathMustBeAbsoluteNormalizedAndNonRoot() {
        for (String invalid : List.of("relative/details", "/", "/var/lib/../tmp/details", " /tmp/x")) {
            Map<String, String> settings = localSettings();
            settings.put("detail-archive-path", invalid);
            assertThatThrownBy(() -> IpfsEffectConfig.parse(settings))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unknownMalformedDuplicateAndOversizedSettingsFailClosed() {
        Map<String, String> settings = localSettings();
        settings.put("unexpected", "value");
        assertInvalid(settings, "unknown IPFS executor setting");

        settings = localSettings();
        settings.put("targets.local.unknown", "value");
        assertInvalid(settings, "unknown IPFS executor setting");

        settings = localSettings();
        settings.put("targets.local.extra.field", "value");
        assertInvalid(settings, "invalid IPFS executor setting");

        settings = localSettings();
        settings.put("targets..api-url", "http://127.0.0.1:5001");
        assertInvalid(settings, "invalid IPFS executor setting");

        settings = localSettings();
        settings.put("targets.local.api-url", "x".repeat(2_049));
        assertInvalid(settings, "invalid IPFS api-url");

        settings = localSettings();
        settings.put("targets.local.api-url", "http://127.0.0.1:5001\nsecret");
        assertInvalid(settings, "invalid or oversized IPFS executor value");

        assertThatThrownBy(() -> IpfsEffectConfig.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("IPFS settings must be present and bounded");

        Map<String, String> tooManySettings = new LinkedHashMap<>();
        for (int index = 0; index < 257; index++) {
            tooManySettings.put("unknown-" + index, "x");
        }
        assertInvalid(tooManySettings, "IPFS settings must be present and bounded");

        Map<String, String> tooManyTargets = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            addTarget(tooManyTargets, "target-" + index, "target-id-" + index,
                    "http://127.0.0." + (index + 1) + ":5001");
        }
        assertInvalid(tooManyTargets, "too many IPFS targets");

        Map<String, String> nullValue = new LinkedHashMap<>(localSettings());
        nullValue.put("enabled", null);
        assertInvalid(nullValue, "invalid or oversized IPFS executor value");

        AbstractMap<String, String> duplicateEntries = new AbstractMap<>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                Set<Entry<String, String>> entries = new LinkedHashSet<>();
                entries.add(Map.entry("enabled", "true"));
                entries.add(Map.entry("enabled", "false"));
                return entries;
            }
        };
        assertInvalid(duplicateEntries, "duplicate IPFS executor setting");
    }

    @Test
    void configurationAndDiagnosticsAreImmutableAndSecretSafe() {
        Map<String, String> settings = bearerSettings();
        settings.put("detail-archive-path", "/secret/archive/location");
        IpfsEffectConfig config = IpfsEffectConfig.parse(settings);
        IpfsEffectConfig.Target target = config.target("remote").orElseThrow();

        assertThatThrownBy(() -> config.targets().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> target.allowedCodecs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> config.safeDiagnostics().put("secret", true))
                .isInstanceOf(UnsupportedOperationException.class);

        String diagnostics = config.safeDiagnostics().toString();
        String rendered = config + " " + target;
        for (String secret : List.of(TOKEN, "pins.example.com", "/secret/archive/location",
                "remote-pin-v1", "provider-three")) {
            assertThat(diagnostics).doesNotContain(secret);
            assertThat(rendered).doesNotContain(secret);
        }
        assertThat(diagnostics)
                .contains("remote")
                .contains("bearer-tls")
                .contains("authenticationConfigured={remote=true}")
                .contains("detailArchiveConfigured=true");
    }

    @Test
    void sourceMapMutationDoesNotChangeParsedConfiguration() {
        Map<String, String> settings = localSettings();
        IpfsEffectConfig config = IpfsEffectConfig.parse(settings);
        settings.put("targets.local.api-url", "http://127.0.0.2:5001");
        settings.clear();

        assertThat(config.target("local").orElseThrow().apiUrl())
                .hasToString("http://127.0.0.1:5001");
    }

    private static IpfsPinCommandV1 command(String target,
                                            CanonicalCid cid,
                                            boolean recursive,
                                            String replicationPolicy) {
        return new IpfsPinCommandV1(target, cid, recursive, replicationPolicy);
    }

    private static CanonicalCid rawCid() {
        return cid(IpfsV1Policy.RAW_CODEC, 1);
    }

    private static CanonicalCid dagPbCid() {
        return cid(CanonicalCid.DAG_PB_CODEC, 2);
    }

    private static CanonicalCid cid(long codec, int seed) {
        byte[] bytes = new byte[36];
        bytes[0] = 1;
        bytes[1] = (byte) codec;
        bytes[2] = (byte) CanonicalCid.SHA2_256_MULTIHASH;
        bytes[3] = (byte) CanonicalCid.SHA2_256_DIGEST_LENGTH;
        for (int index = 4; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return CanonicalCid.fromBytes(bytes);
    }

    private static Map<String, String> localSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("enabled", "true");
        addTarget(settings, "local", TARGET_ID, "http://127.0.0.1:5001");
        return settings;
    }

    private static Map<String, String> bearerSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("enabled", "true");
        addTarget(settings, "remote", "remote-pin-v1", "https://pins.example.com:443");
        settings.put("targets.remote.security-profile", "bearer-tls");
        settings.put("targets.remote.replication-policy", "provider-three");
        settings.put("targets.remote.bearer-token", TOKEN);
        return settings;
    }

    private static void addTarget(Map<String, String> settings,
                                  String alias,
                                  String targetId,
                                  String apiUrl) {
        String prefix = "targets." + alias + ".";
        settings.put(prefix + "target-id", targetId);
        settings.put(prefix + "api-url", apiUrl);
        settings.put(prefix + "security-profile", "local-demo");
        settings.put(prefix + "allowed-codecs", "raw,dag-pb");
        settings.put(prefix + "recursive", "true");
        settings.put(prefix + "replication-policy", REPLICATION_POLICY);
        settings.put(prefix + "connect-timeout-ms", "2000");
        settings.put(prefix + "request-timeout-ms", "30000");
        settings.put(prefix + "close-timeout-ms", "5000");
    }

    private static void assertInvalid(Map<String, String> settings, String message) {
        assertThatThrownBy(() -> IpfsEffectConfig.parse(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
