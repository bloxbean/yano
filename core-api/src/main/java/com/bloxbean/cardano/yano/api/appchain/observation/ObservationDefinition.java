package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;
import java.util.Objects;

/** Operator-installed, consensus-identified observation provider contract. */
public record ObservationDefinition(
        int version,
        String id,
        int schemaVersion,
        byte[] parameterSchemaDigest,
        byte[] reportSchemaDigest,
        byte[] resultSchemaDigest,
        byte[] sourceIdentitySchemaDigest,
        ObservationReporterMode reporterMode,
        byte[] reporterSetDigest,
        int reporterFaultBound,
        int reportThreshold,
        int sourceThreshold,
        boolean certificateLocalUniqueness,
        String acquisitionAdapterId,
        byte[] sourceConfigurationDigest,
        String normalizationId,
        String evidenceVerifierId,
        String reconciliationPolicyId,
        byte[] policyParametersDigest,
        byte[] roundPolicySchemaDigest,
        String sourceDiversityPolicyId,
        String freshnessPolicyId,
        String evidenceRetentionPolicyId,
        int orderingVersion,
        int maxParameterBytes,
        int maxValueBytes,
        int maxEvidenceBytes,
        int maxReports,
        int maxSources
) {
    public static final int MAX_ENCODED_BYTES = 8 * 1024;
    private static final int FIELDS = 29;

    public ObservationDefinition {
        if (version != ObservationCbor.VERSION || schemaVersion < 1 || orderingVersion < 1) {
            throw new IllegalArgumentException("invalid observation definition version");
        }
        id = ObservationCbor.boundedText(id, 128, "definition id");
        parameterSchemaDigest = ObservationCbor.fixed(parameterSchemaDigest, 32, "parameter schema digest");
        reportSchemaDigest = ObservationCbor.fixed(reportSchemaDigest, 32, "report schema digest");
        resultSchemaDigest = ObservationCbor.fixed(resultSchemaDigest, 32, "result schema digest");
        sourceIdentitySchemaDigest = ObservationCbor.fixed(
                sourceIdentitySchemaDigest, 32, "source identity schema digest");
        reporterMode = Objects.requireNonNull(reporterMode, "reporterMode");
        reporterSetDigest = ObservationCbor.fixed(reporterSetDigest, 32, "reporter set digest");
        acquisitionAdapterId = ObservationCbor.boundedText(acquisitionAdapterId, 128, "acquisition adapter id");
        sourceConfigurationDigest = ObservationCbor.fixed(
                sourceConfigurationDigest, 32, "source configuration digest");
        normalizationId = ObservationCbor.boundedText(normalizationId, 128, "normalization id");
        evidenceVerifierId = ObservationCbor.boundedText(evidenceVerifierId, 128, "evidence verifier id");
        reconciliationPolicyId = ObservationCbor.boundedText(reconciliationPolicyId, 128, "reconciliation policy id");
        policyParametersDigest = ObservationCbor.fixed(
                policyParametersDigest, 32, "policy parameters digest");
        roundPolicySchemaDigest = ObservationCbor.fixed(
                roundPolicySchemaDigest, 32, "round policy schema digest");
        sourceDiversityPolicyId = ObservationCbor.boundedText(sourceDiversityPolicyId, 128, "source diversity policy id");
        freshnessPolicyId = ObservationCbor.boundedText(freshnessPolicyId, 128, "freshness policy id");
        evidenceRetentionPolicyId = ObservationCbor.boundedText(evidenceRetentionPolicyId, 128, "evidence retention policy id");
        if (reporterFaultBound < 0 || reportThreshold < 1
                || maxParameterBytes < 1 || maxValueBytes < 1 || maxEvidenceBytes < 0
                || sourceThreshold < 1 || maxReports < reportThreshold
                || maxSources < sourceThreshold || maxSources > maxReports) {
            throw new IllegalArgumentException("invalid observation definition thresholds or bounds");
        }
    }

    @Override public byte[] parameterSchemaDigest() { return parameterSchemaDigest.clone(); }
    @Override public byte[] reportSchemaDigest() { return reportSchemaDigest.clone(); }
    @Override public byte[] resultSchemaDigest() { return resultSchemaDigest.clone(); }
    @Override public byte[] sourceIdentitySchemaDigest() { return sourceIdentitySchemaDigest.clone(); }
    @Override public byte[] reporterSetDigest() { return reporterSetDigest.clone(); }
    @Override public byte[] sourceConfigurationDigest() { return sourceConfigurationDigest.clone(); }
    @Override public byte[] policyParametersDigest() { return policyParametersDigest.clone(); }
    @Override public byte[] roundPolicySchemaDigest() { return roundPolicySchemaDigest.clone(); }

    public byte[] digest() {
        return ObservationHashes.definitionDigest(this);
    }

    public byte[] encode() {
        Array value = ObservationCbor.array();
        ObservationCbor.uint(value, version);
        ObservationCbor.text(value, id);
        ObservationCbor.uint(value, schemaVersion);
        ObservationCbor.bytes(value, parameterSchemaDigest);
        ObservationCbor.bytes(value, reportSchemaDigest);
        ObservationCbor.bytes(value, resultSchemaDigest);
        ObservationCbor.bytes(value, sourceIdentitySchemaDigest);
        ObservationCbor.uint(value, reporterMode.code());
        ObservationCbor.bytes(value, reporterSetDigest);
        ObservationCbor.uint(value, reporterFaultBound);
        ObservationCbor.uint(value, reportThreshold);
        ObservationCbor.uint(value, sourceThreshold);
        ObservationCbor.uint(value, certificateLocalUniqueness ? 1 : 0);
        ObservationCbor.text(value, acquisitionAdapterId);
        ObservationCbor.bytes(value, sourceConfigurationDigest);
        ObservationCbor.text(value, normalizationId);
        ObservationCbor.text(value, evidenceVerifierId);
        ObservationCbor.text(value, reconciliationPolicyId);
        ObservationCbor.bytes(value, policyParametersDigest);
        ObservationCbor.bytes(value, roundPolicySchemaDigest);
        ObservationCbor.text(value, sourceDiversityPolicyId);
        ObservationCbor.text(value, freshnessPolicyId);
        ObservationCbor.text(value, evidenceRetentionPolicyId);
        ObservationCbor.uint(value, orderingVersion);
        ObservationCbor.uint(value, maxParameterBytes);
        ObservationCbor.uint(value, maxValueBytes);
        ObservationCbor.uint(value, maxEvidenceBytes);
        ObservationCbor.uint(value, maxReports);
        ObservationCbor.uint(value, maxSources);
        return ObservationCbor.encode(value);
    }

    public static ObservationDefinition decode(byte[] bytes) {
        try {
            List<DataItem> f = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES, 64, 32,
                    1024, FIELDS, "definition");
            ObservationDefinition value = new ObservationDefinition(
                    ObservationCbor.intValue(f.get(0)), ObservationCbor.textValue(f.get(1)),
                    ObservationCbor.intValue(f.get(2)), ObservationCbor.bytesValue(f.get(3)),
                    ObservationCbor.bytesValue(f.get(4)), ObservationCbor.bytesValue(f.get(5)),
                    ObservationCbor.bytesValue(f.get(6)),
                    ObservationReporterMode.fromCode(ObservationCbor.intValue(f.get(7))),
                    ObservationCbor.bytesValue(f.get(8)), ObservationCbor.intValue(f.get(9)),
                    ObservationCbor.intValue(f.get(10)), ObservationCbor.intValue(f.get(11)),
                    booleanCode(f.get(12)), ObservationCbor.textValue(f.get(13)),
                    ObservationCbor.bytesValue(f.get(14)), ObservationCbor.textValue(f.get(15)),
                    ObservationCbor.textValue(f.get(16)), ObservationCbor.textValue(f.get(17)),
                    ObservationCbor.bytesValue(f.get(18)), ObservationCbor.bytesValue(f.get(19)),
                    ObservationCbor.textValue(f.get(20)), ObservationCbor.textValue(f.get(21)),
                    ObservationCbor.textValue(f.get(22)), ObservationCbor.intValue(f.get(23)),
                    ObservationCbor.intValue(f.get(24)), ObservationCbor.intValue(f.get(25)),
                    ObservationCbor.intValue(f.get(26)), ObservationCbor.intValue(f.get(27)),
                    ObservationCbor.intValue(f.get(28)));
            ObservationCbor.canonical(bytes, value.encode(), "definition");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("definition");
        }
    }

    private static boolean booleanCode(DataItem item) {
        int code = ObservationCbor.intValue(item);
        if (code > 1) {
            throw ObservationCbor.invalid("definition");
        }
        return code == 1;
    }
}
