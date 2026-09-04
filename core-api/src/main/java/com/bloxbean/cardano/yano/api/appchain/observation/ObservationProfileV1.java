package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Complete consensus-critical identity and bounds of generic observations. */
public record ObservationProfileV1(
        int version,
        boolean enabled,
        int frameworkAbiVersion,
        int stateCodecVersion,
        int topicVersion,
        int logicalTimeVersion,
        int roundRulesVersion,
        int orderingVersion,
        int incorporationVersion,
        List<ObservationDefinition> definitions,
        int maxActiveSubscriptions,
        int maxSubscriptionsPerApplication,
        int maxSubscriptionsPerDefinition,
        int maxRoundsOpenedPerBlock,
        int maxOpenRounds,
        int maxReportsPerRound,
        int maxSourcesPerRound,
        int maxReportBytes,
        int maxEvidenceBytes,
        int maxCertificateBytes,
        int maxResultsPerBlock,
        int maxResultBytesPerBlock,
        int maxTicksPerBlock,
        long maxRoundHeights,
        long resultInclusionGraceHeights
) {
    public static final int MAX_ENCODED_BYTES = 1024 * 1024;
    public static final int MAX_DEFINITIONS = 256;
    private static final int FIELDS = 25;

    public ObservationProfileV1 {
        if (version != ObservationCbor.VERSION) {
            throw new IllegalArgumentException("observation profile version must be 1");
        }
        List<ObservationDefinition> source = new ArrayList<>(
                Objects.requireNonNull(definitions, "definitions"));
        source.forEach(definition -> Objects.requireNonNull(definition, "definition"));
        if (source.size() > MAX_DEFINITIONS) {
            throw new IllegalArgumentException("observation profile exceeds definition bound");
        }
        Set<String> definitionIds = new HashSet<>();
        for (ObservationDefinition definition : source) {
            if (!definitionIds.add(definition.id())) {
                throw new IllegalArgumentException("duplicate observation definition id");
            }
        }
        source.sort(Comparator.comparing(ObservationDefinition::digest, Arrays::compareUnsigned));
        for (int index = 1; index < source.size(); index++) {
            if (Arrays.equals(source.get(index - 1).digest(), source.get(index).digest())) {
                throw new IllegalArgumentException("duplicate observation definition digest");
            }
        }
        definitions = List.copyOf(source);
        long[] values = {frameworkAbiVersion, stateCodecVersion, topicVersion,
                logicalTimeVersion, roundRulesVersion, orderingVersion, incorporationVersion,
                maxActiveSubscriptions, maxSubscriptionsPerApplication,
                maxSubscriptionsPerDefinition, maxRoundsOpenedPerBlock, maxOpenRounds,
                maxReportsPerRound, maxSourcesPerRound, maxReportBytes, maxEvidenceBytes,
                maxCertificateBytes, maxResultsPerBlock, maxResultBytesPerBlock,
                maxTicksPerBlock, maxRoundHeights, resultInclusionGraceHeights};
        if (!enabled) {
            if (!definitions.isEmpty() || Arrays.stream(values).anyMatch(value -> value != 0)) {
                throw new IllegalArgumentException("disabled observation profile must be canonical zero/empty");
            }
        } else if (definitions.isEmpty() || Arrays.stream(values).anyMatch(value -> value <= 0)) {
            throw new IllegalArgumentException("enabled observation profile requires definitions and positive bounds");
        }
        if (maxReportsPerRound > ObservationCertificate.MAX_REPORTS
                || maxCertificateBytes > ObservationCertificate.MAX_ENCODED_BYTES
                || maxReportBytes > ObservationReport.MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("observation profile exceeds v1 wire bounds");
        }
        if (enabled && (maxSubscriptionsPerApplication > maxActiveSubscriptions
                || maxSubscriptionsPerDefinition > maxActiveSubscriptions
                || maxRoundsOpenedPerBlock > maxOpenRounds
                || maxSourcesPerRound > maxReportsPerRound
                || definitions.stream().anyMatch(definition ->
                definition.maxReports() > maxReportsPerRound
                        || definition.maxSources() > maxSourcesPerRound
                        || definition.maxEvidenceBytes() > maxEvidenceBytes
                        || definition.maxValueBytes() > maxReportBytes))) {
            throw new IllegalArgumentException("observation profile contains inconsistent nested bounds");
        }
    }

    public static ObservationProfileV1 disabled() {
        return new ObservationProfileV1(1, false, 0, 0, 0, 0, 0, 0, 0,
                List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override public List<ObservationDefinition> definitions() { return List.copyOf(definitions); }

    public byte[] digest() {
        return ObservationHashes.profileDigest(this);
    }

    public byte[] encode() {
        Array result = ObservationCbor.array();
        ObservationCbor.uint(result, version);
        ObservationCbor.uint(result, enabled ? 1 : 0);
        ObservationCbor.uint(result, frameworkAbiVersion);
        ObservationCbor.uint(result, stateCodecVersion);
        ObservationCbor.uint(result, topicVersion);
        ObservationCbor.uint(result, logicalTimeVersion);
        ObservationCbor.uint(result, roundRulesVersion);
        ObservationCbor.uint(result, orderingVersion);
        ObservationCbor.uint(result, incorporationVersion);
        Array encodedDefinitions = ObservationCbor.array();
        for (ObservationDefinition definition : definitions) {
            encodedDefinitions.add(new ByteString(definition.encode()));
        }
        result.add(encodedDefinitions);
        ObservationCbor.uint(result, maxActiveSubscriptions);
        ObservationCbor.uint(result, maxSubscriptionsPerApplication);
        ObservationCbor.uint(result, maxSubscriptionsPerDefinition);
        ObservationCbor.uint(result, maxRoundsOpenedPerBlock);
        ObservationCbor.uint(result, maxOpenRounds);
        ObservationCbor.uint(result, maxReportsPerRound);
        ObservationCbor.uint(result, maxSourcesPerRound);
        ObservationCbor.uint(result, maxReportBytes);
        ObservationCbor.uint(result, maxEvidenceBytes);
        ObservationCbor.uint(result, maxCertificateBytes);
        ObservationCbor.uint(result, maxResultsPerBlock);
        ObservationCbor.uint(result, maxResultBytesPerBlock);
        ObservationCbor.uint(result, maxTicksPerBlock);
        ObservationCbor.uint(result, maxRoundHeights);
        ObservationCbor.uint(result, resultInclusionGraceHeights);
        return ObservationCbor.encode(result);
    }

    public static ObservationProfileV1 decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 4096,
                    1024, ObservationDefinition.MAX_ENCODED_BYTES, FIELDS, "profile");
            int enabledCode = ObservationCbor.intValue(f.get(1));
            if (enabledCode > 1) {
                throw ObservationCbor.invalid("profile");
            }
            List<ObservationDefinition> definitions = new ArrayList<>();
            for (DataItem definition : ((Array) f.get(9)).getDataItems()) {
                definitions.add(ObservationDefinition.decode(ObservationCbor.bytesValue(definition)));
            }
            ObservationProfileV1 value = new ObservationProfileV1(
                    ObservationCbor.intValue(f.get(0)), enabledCode == 1,
                    ObservationCbor.intValue(f.get(2)), ObservationCbor.intValue(f.get(3)),
                    ObservationCbor.intValue(f.get(4)), ObservationCbor.intValue(f.get(5)),
                    ObservationCbor.intValue(f.get(6)), ObservationCbor.intValue(f.get(7)),
                    ObservationCbor.intValue(f.get(8)), definitions,
                    ObservationCbor.intValue(f.get(10)), ObservationCbor.intValue(f.get(11)),
                    ObservationCbor.intValue(f.get(12)), ObservationCbor.intValue(f.get(13)),
                    ObservationCbor.intValue(f.get(14)), ObservationCbor.intValue(f.get(15)),
                    ObservationCbor.intValue(f.get(16)), ObservationCbor.intValue(f.get(17)),
                    ObservationCbor.intValue(f.get(18)), ObservationCbor.intValue(f.get(19)),
                    ObservationCbor.intValue(f.get(20)), ObservationCbor.intValue(f.get(21)),
                    ObservationCbor.intValue(f.get(22)), ObservationCbor.longValue(f.get(23)),
                    ObservationCbor.longValue(f.get(24)));
            ObservationCbor.canonical(bytes, value.encode(), "profile");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("profile");
        }
    }
}
