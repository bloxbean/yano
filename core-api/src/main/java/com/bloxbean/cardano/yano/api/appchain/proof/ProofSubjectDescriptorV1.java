package com.bloxbean.cardano.yano.api.appchain.proof;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Closed, data-only proof-subject discovery grammar. */
public record ProofSubjectDescriptorV1(
        int schemaVersion,
        String subjectId,
        int subjectVersion,
        String componentId,
        String label,
        String description,
        String descriptorDigest,
        ProofLabVocabulary.StorageScope storageScope,
        List<Coordinate> coordinates,
        List<Claim> claims,
        List<FactField> factView,
        ProofLabVocabulary.Completeness completeness,
        List<ProofLabVocabulary.VerificationTarget> verificationTargets,
        RetentionHints retentionHints,
        Limits limits
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DESCRIPTORS = 64;
    public static final int MAX_COORDINATES = 16;
    public static final int MAX_CLAIMS = 32;

    public ProofSubjectDescriptorV1 {
        if (schemaVersion != SCHEMA_VERSION || subjectVersion < 1) throw invalid("version");
        subjectId = subjectId(subjectId);
        componentId = componentId == null || componentId.isEmpty() ? "" : id(componentId, "componentId");
        label = text(label, "label", 128);
        description = text(description, "description", 1024);
        storageScope = Objects.requireNonNull(storageScope, "storageScope");
        coordinates = sorted(coordinates, Coordinate::id, MAX_COORDINATES, "coordinates");
        claims = sorted(claims, Claim::claimId, MAX_CLAIMS, "claims");
        factView = sorted(factView, FactField::id, 32, "factView");
        completeness = Objects.requireNonNull(completeness, "completeness");
        verificationTargets = Objects.requireNonNull(verificationTargets, "verificationTargets")
                .stream().distinct().sorted().toList();
        if (verificationTargets.isEmpty()) throw invalid("verificationTargets");
        retentionHints = Objects.requireNonNull(retentionHints, "retentionHints");
        limits = Objects.requireNonNull(limits, "limits");
        String expected = digest(schemaVersion, subjectId, subjectVersion, componentId, label,
                description, storageScope, coordinates, claims, factView, completeness,
                verificationTargets, retentionHints, limits);
        if (descriptorDigest == null || descriptorDigest.isEmpty()) descriptorDigest = expected;
        else if (!descriptorDigest.equals(expected)) throw invalid("descriptorDigest");
    }

    public record Coordinate(String id, ValueType type, String label,
                             Map<String, String> constraints, String encoding) {
        public Coordinate {
            id = ProofSubjectDescriptorV1.id(id, "coordinate id");
            type = Objects.requireNonNull(type, "type");
            label = text(label, "coordinate label", 128);
            encoding = text(encoding, "coordinate encoding", 64);
            constraints = Map.copyOf(new TreeMap<>(Objects.requireNonNull(constraints, "constraints")));
            if (constraints.size() > 16) throw invalid("coordinate constraints");
            constraints.forEach((key, value) -> {
                ProofSubjectDescriptorV1.id(key, "constraint key");
                text(value, "constraint value", 256);
            });
        }
    }

    public record Claim(String claimId, List<String> operands, List<ValueType> supportedTypes) {
        public Claim {
            claimId = id(claimId, "claimId");
            operands = Objects.requireNonNull(operands, "operands").stream()
                    .map(value -> id(value, "claim operand")).toList();
            supportedTypes = List.copyOf(Objects.requireNonNull(supportedTypes, "supportedTypes"));
            if (operands.size() > 8 || supportedTypes.isEmpty()) throw invalid("claim shape");
        }
    }

    public record FactField(String id, ValueType type, String label, String displayUnit) {
        public FactField {
            id = ProofSubjectDescriptorV1.id(id, "fact field id");
            type = Objects.requireNonNull(type, "type");
            label = text(label, "fact field label", 128);
            displayUnit = displayUnit == null || displayUnit.isEmpty() ? ""
                    : text(displayUnit, "displayUnit", 64);
        }
    }

    public record RetentionHints(boolean historical, boolean snapshot,
                                 String unavailableHint) {
        public RetentionHints {
            unavailableHint = text(unavailableHint, "unavailableHint", 256);
        }
    }

    public record Limits(int maxCoordinateBytes, int maxValueBytes,
                         int maxProofBytes, int maxClaimOperandBytes) {
        public Limits {
            if (maxCoordinateBytes < 1 || maxCoordinateBytes > 64 * 1024
                    || maxValueBytes < 1 || maxValueBytes > 1024 * 1024
                    || maxProofBytes < 1 || maxProofBytes > 1024 * 1024
                    || maxClaimOperandBytes < 1 || maxClaimOperandBytes > 64 * 1024) {
                throw invalid("limits");
            }
        }
        public static Limits defaults() { return new Limits(4096, 1024 * 1024, 1024 * 1024, 4096); }
    }

    public enum ValueType { STRING, BYTES_HEX, UINT64, INTEGER, BOOLEAN, DIGEST_HEX, ENUM }

    /** Rebinds a released semantic contract to its effective application/component identity. */
    public ProofSubjectDescriptorV1 withIdentity(String effectiveSubjectId,
                                                 String effectiveComponentId) {
        return new ProofSubjectDescriptorV1(schemaVersion, effectiveSubjectId, subjectVersion,
                effectiveComponentId, label, description, "", storageScope, coordinates, claims,
                factView, completeness, verificationTargets, retentionHints, limits);
    }

    private static String digest(int version, String id, int subjectVersion, String component,
                                 String label, String description,
                                 ProofLabVocabulary.StorageScope scope,
                                 List<Coordinate> coordinates, List<Claim> claims,
                                 List<FactField> facts, ProofLabVocabulary.Completeness completeness,
                                 List<ProofLabVocabulary.VerificationTarget> targets,
                                 RetentionHints retention, Limits limits) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(version); write(out, id); out.writeInt(subjectVersion); write(out, component);
            write(out, label); write(out, description); write(out, scope.name());
            out.writeInt(coordinates.size());
            for (Coordinate value : coordinates) {
                write(out, value.id()); write(out, value.type().name()); write(out, value.label());
                out.writeInt(value.constraints().size());
                for (var entry : value.constraints().entrySet()) { write(out, entry.getKey()); write(out, entry.getValue()); }
                write(out, value.encoding());
            }
            out.writeInt(claims.size());
            for (Claim value : claims) { write(out, value.claimId()); writes(out, value.operands());
                writes(out, value.supportedTypes().stream().map(Enum::name).toList()); }
            out.writeInt(facts.size());
            for (FactField value : facts) { write(out, value.id()); write(out, value.type().name());
                write(out, value.label()); write(out, value.displayUnit()); }
            write(out, completeness.name()); writes(out, targets.stream().map(Enum::name).toList());
            out.writeBoolean(retention.historical()); out.writeBoolean(retention.snapshot());
            write(out, retention.unavailableHint()); out.writeInt(limits.maxCoordinateBytes());
            out.writeInt(limits.maxValueBytes()); out.writeInt(limits.maxProofBytes());
            out.writeInt(limits.maxClaimOperandBytes()); out.flush();
            return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes.toByteArray()));
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writes(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : values) write(out, value);
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length); out.write(encoded);
    }
    private static <T> List<T> sorted(List<T> source, java.util.function.Function<T, String> key,
                                      int maximum, String field) {
        List<T> values = Objects.requireNonNull(source, field).stream()
                .sorted(Comparator.comparing(key)).toList();
        if (values.size() > maximum || values.stream().map(key).distinct().count() != values.size())
            throw invalid(field);
        return values;
    }
    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field);
        if (!value.matches("[a-z0-9][a-z0-9:._-]{0,127}")) throw invalid(field);
        return value;
    }
    private static String subjectId(String value) {
        value = Objects.requireNonNull(value, "subjectId");
        if (!value.matches("[a-z0-9][a-z0-9:._/-]{0,127}")) throw invalid("subjectId");
        return value;
    }
    private static String text(String value, String field, int maximumBytes) {
        value = Objects.requireNonNull(value, field);
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank() || value.indexOf('\0') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || lower.contains("javascript:") || lower.contains("://")) throw invalid(field);
        return value;
    }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("invalid proof subject " + field);
    }
}
