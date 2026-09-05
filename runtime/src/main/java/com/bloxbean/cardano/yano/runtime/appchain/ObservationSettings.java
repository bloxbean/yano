package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.observation.ExactValueQuorumPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAttestation;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationEvidenceVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReconciliationPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSourceConfiguration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Normalized fresh-chain settings and deterministic verifier registry for ADR-037. */
final class ObservationSettings {
    static final String PROFILE_HEX = "observations.profile-cbor-hex";
    static final String RAW_EXACT_EVIDENCE = "raw-exact-v1";
    static final String ATTESTATION_EVIDENCE = "ed25519-attestation-v1";
    static final String EXACT_POLICY = "exact-value-quorum-v1";

    private final ObservationProfileV1 profile;
    private final Map<String, Set<ByteKey>> attestors;
    private final Map<String, byte[]> rawSources;

    private ObservationSettings(ObservationProfileV1 profile,
                                Map<String, Set<ByteKey>> attestors,
                                Map<String, byte[]> rawSources) {
        this.profile = profile;
        this.attestors = attestors;
        Map<String, byte[]> copied = new LinkedHashMap<>();
        rawSources.forEach((id, source) -> copied.put(id, source.clone()));
        this.rawSources = Map.copyOf(copied);
    }

    static ObservationSettings from(AppChainConfig config, MemberGroup members) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(members, "members");
        String encoded = config.pluginSettings().get(PROFILE_HEX);
        if (encoded == null || encoded.isBlank()) {
            return new ObservationSettings(ObservationProfileV1.disabled(), Map.of(), Map.of());
        }
        final ObservationProfileV1 profile;
        try {
            profile = ObservationProfileV1.decode(HexUtil.decodeHexString(encoded.trim()));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "Invalid observations.profile-cbor-hex", malformed);
        }
        if (!profile.enabled()) {
            throw new IllegalArgumentException(
                    "Omit observations.profile-cbor-hex for the canonical disabled profile");
        }
        if (profile.frameworkAbiVersion() != 1
                || (profile.stateCodecVersion() != 1 && profile.stateCodecVersion() != 2)
                || profile.topicVersion() != 1
                || (profile.logicalTimeVersion() != 1 && profile.logicalTimeVersion() != 2)
                || profile.roundRulesVersion() != profile.stateCodecVersion()
                || profile.orderingVersion() != 1
                || profile.incorporationVersion() != 1) {
            throw new IllegalArgumentException("Unsupported observation protocol versions");
        }
        if (profile.logicalTimeVersion() == 2
                && (profile.roundRulesVersion() != 2 || config.l1StabilityDepth() <= 0)) {
            throw new IllegalArgumentException(
                    "Verified-slot observations require v2 scheduling and positive L1 stability depth");
        }
        if (profile.maxCertificateBytes() > config.maxMessageBytes()
                || profile.maxResultBytesPerBlock() > config.proposalMaxBytes()) {
            throw new IllegalArgumentException(
                    "Observation result bounds exceed the app-message or proposal profile");
        }
        int maximumFaults = Integer.parseInt(config.pluginSettings().getOrDefault(
                "consensus.max-byzantine-members", "0"));
        List<byte[]> reporterKeys = members.members().stream().sorted()
                .map(HexUtil::decodeHexString).toList();
        byte[] reporterSetDigest = ObservationHashes.reporterSetDigest(reporterKeys);
        Map<String, Set<ByteKey>> attestors = new LinkedHashMap<>();
        Map<String, byte[]> rawSources = new LinkedHashMap<>();
        for (ObservationDefinition definition : profile.definitions()) {
            if (definition.reporterMode() != ObservationReporterMode.ACTIVE_MEMBERS
                    || definition.reporterFaultBound() != maximumFaults
                    || (profile.roundRulesVersion() == 1
                        && definition.reportThreshold() != members.threshold())
                    || Math.max(definition.reportThreshold(), members.threshold()) > definition.maxReports()
                    || Math.max(definition.reportThreshold(), members.threshold()) > profile.maxReportsPerRound()
                    || Math.max(definition.reportThreshold(), members.threshold()) > members.size() - maximumFaults
                    || !Arrays.equals(definition.reporterSetDigest(), profile.roundRulesVersion() == 2
                            ? ObservationHashes.activeMemberRuleDigest() : reporterSetDigest)) {
                throw new IllegalArgumentException("Observation definition '" + definition.id()
                        + "' does not bind the configured active-member quorum");
            }
            if (!EXACT_POLICY.equals(definition.reconciliationPolicyId())) {
                throw new IllegalArgumentException("Observation definition '" + definition.id()
                        + "' uses an unreleased reconciliation policy");
            }
            if (RAW_EXACT_EVIDENCE.equals(definition.evidenceVerifierId())) {
                if (!ObservationProviders.HTTPS_EXACT.equals(
                        definition.acquisitionAdapterId())) {
                    throw new IllegalArgumentException("Phase 1 raw exact observations require "
                            + ObservationProviders.HTTPS_EXACT);
                }
                registerRawSource(config.pluginSettings(), definition, rawSources);
                continue;
            }
            if (!ATTESTATION_EVIDENCE.equals(definition.evidenceVerifierId())) {
                throw new IllegalArgumentException("Observation definition '" + definition.id()
                        + "' uses an unreleased evidence verifier");
            }
            List<byte[]> keys = parseAttestors(config.pluginSettings().get(
                    "observations.attestors." + definition.id()));
            byte[] expectedSource = definition.acquisitionAdapterId().equals(
                    ObservationProviders.HTTPS_ATTESTED)
                    ? attestedHttpsSource(config.pluginSettings(), definition, keys)
                    : ObservationSourceConfiguration.attestorSetDigest(keys);
            if (!Arrays.equals(definition.sourceConfigurationDigest(), expectedSource)) {
                throw new IllegalArgumentException("Attestor keys for observation definition '"
                        + definition.id() + "' differ from its source configuration digest");
            }
            Set<ByteKey> allowed = new LinkedHashSet<>();
            keys.forEach(key -> allowed.add(new ByteKey(key)));
            attestors.put(definition.id(), Set.copyOf(allowed));
        }
        ObservationSettings settings = new ObservationSettings(profile, Map.copyOf(attestors), rawSources);
        for (MemberGroup.Epoch epoch : members.history()) {
            if (!settings.admitsMembership(epoch.members(), epoch.threshold())) {
                throw new IllegalArgumentException("Retained membership is incompatible with the observation profile");
            }
        }
        return settings;
    }

    boolean admitsMembership(Set<String> memberKeys, int finalityQuorum) {
        if (!profile.enabled()) return true;
        int count = memberKeys.size();
        List<byte[]> keys = memberKeys.stream().map(HexUtil::decodeHexString).toList();
        for (ObservationDefinition definition : profile.definitions()) {
            int faults = definition.reporterFaultBound();
            if (2L * finalityQuorum - count <= faults || finalityQuorum > count - faults) return false;
            int reports = Math.max(definition.reportThreshold(), finalityQuorum);
            if (reports > count - faults || reports > definition.maxReports()
                    || reports > profile.maxReportsPerRound()) return false;
            if (profile.roundRulesVersion() == 1 && (definition.reportThreshold() != finalityQuorum
                    || !Arrays.equals(definition.reporterSetDigest(), ObservationHashes.reporterSetDigest(keys)))) {
                return false;
            }
        }
        return true;
    }

    ObservationProfileV1 profile() {
        return profile;
    }

    ObservationKernel.VerifierRegistry verifierRegistry() {
        return new ObservationKernel.VerifierRegistry() {
            @Override
            public ObservationEvidenceVerifier evidenceVerifier(ObservationDefinition definition) {
                return switch (definition.evidenceVerifierId()) {
                    case RAW_EXACT_EVIDENCE -> (ignoredDefinition, round, report) ->
                            report.evidence().length == 0 && report.sourceVersion().length > 0
                                    && report.freshnessAnchorType() == round.anchorType().code()
                                    && report.freshnessAnchor() == round.dueAnchor()
                                    && Arrays.equals(report.sourceId(),
                                    rawSources.get(definition.id()));
                    case ATTESTATION_EVIDENCE -> (ignoredDefinition, round, report) ->
                            report.freshnessAnchorType() == round.anchorType().code()
                                    && report.freshnessAnchor() >= round.dueAnchor()
                                    && report.freshnessAnchor() <= round.reportDeadlineAnchor()
                                    && verifyAttestation(definition, report);
                    default -> throw new IllegalArgumentException(
                            "Unknown observation evidence verifier: "
                                    + definition.evidenceVerifierId());
                };
            }

            @Override
            public ObservationReconciliationPolicy policy(ObservationDefinition definition) {
                if (!EXACT_POLICY.equals(definition.reconciliationPolicyId())) {
                    throw new IllegalArgumentException(
                            "Unknown observation reconciliation policy: "
                                    + definition.reconciliationPolicyId());
                }
                return new ExactValueQuorumPolicy();
            }
        };
    }

    private boolean verifyAttestation(ObservationDefinition definition,
                                      ObservationReport report) {
        final ObservationAttestation attestation;
        try {
            attestation = ObservationAttestation.decode(report.evidence());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        Set<ByteKey> allowed = attestors.getOrDefault(definition.id(), Set.of());
        return allowed.contains(new ByteKey(attestation.signerPublicKey()))
                && Arrays.equals(attestation.definitionDigest(), definition.digest())
                && Arrays.equals(attestation.subscriptionId(), report.subscriptionId())
                && attestation.roundNumber() == report.roundNumber()
                && Arrays.equals(attestation.sourceId(), report.sourceId())
                && Arrays.equals(attestation.claim(), report.value())
                && Arrays.equals(attestation.sourceVersion(), report.sourceVersion())
                && attestation.freshnessAnchorType() == report.freshnessAnchorType()
                && attestation.freshnessAnchor() == report.freshnessAnchor()
                && AppMessageSigner.verify(attestation.signature(),
                attestation.signingDigest(), attestation.signerPublicKey());
    }

    private static List<byte[]> parseAttestors(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Attested observation requires authorized keys");
        }
        List<byte[]> keys = new ArrayList<>();
        for (String item : configured.split(",")) {
            byte[] key;
            try {
                key = HexUtil.decodeHexString(item.trim().toLowerCase(Locale.ROOT));
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("Invalid observation attestor key", malformed);
            }
            if (key.length != 32) {
                throw new IllegalArgumentException("Observation attestor key must be 32 bytes");
            }
            keys.add(key);
        }
        keys.sort(Arrays::compareUnsigned);
        for (int index = 1; index < keys.size(); index++) {
            if (Arrays.equals(keys.get(index - 1), keys.get(index))) {
                throw new IllegalArgumentException("Duplicate observation attestor key");
            }
        }
        return List.copyOf(keys);
    }

    private static void registerRawSource(Map<String, String> settings,
                                          ObservationDefinition definition,
                                          Map<String, byte[]> rawSources) {
        String prefix = "observations.providers." + definition.id() + ".";
        String url = settings.get(prefix + "url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Raw observation definition '" + definition.id()
                    + "' requires a fixed provider URL");
        }
        URI endpoint;
        try {
            endpoint = URI.create(url.trim()).normalize();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Invalid raw observation provider URL", malformed);
        }
        String source = settings.getOrDefault(prefix + "source-id", endpoint.getHost());
        String versionHeader = settings.getOrDefault(prefix + "version-header", "ETag")
                .trim().toLowerCase(Locale.ROOT);
        if (source == null || source.isEmpty()
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(source)) {
            throw new IllegalArgumentException("Raw observation source-id must be nonempty ASCII");
        }
        String method = settings.getOrDefault(prefix + "method", "GET")
                .trim().toUpperCase(Locale.ROOT);
        byte[] expected = ObservationSourceConfiguration.httpsSourceDigest(
                endpoint.toASCIIString(), method, source, versionHeader);
        if (!Arrays.equals(expected, definition.sourceConfigurationDigest())) {
            throw new IllegalArgumentException("Raw provider settings for observation definition '"
                    + definition.id() + "' differ from its source configuration digest");
        }
        rawSources.put(definition.id(), source.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] attestedHttpsSource(Map<String, String> settings,
                                              ObservationDefinition definition,
                                              List<byte[]> keys) {
        String prefix = "observations.providers." + definition.id() + ".";
        String url = settings.get(prefix + "url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Attested observation definition '"
                    + definition.id() + "' requires a fixed provider URL");
        }
        URI endpoint;
        try {
            endpoint = URI.create(url.trim()).normalize();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "Invalid attested observation provider URL", malformed);
        }
        String method = settings.getOrDefault(prefix + "method", "GET")
                .trim().toUpperCase(Locale.ROOT);
        return ObservationSourceConfiguration.attestedHttpsSourceDigest(
                endpoint.toASCIIString(), method, keys);
    }

    private record ByteKey(byte[] value) {
        private ByteKey {
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
