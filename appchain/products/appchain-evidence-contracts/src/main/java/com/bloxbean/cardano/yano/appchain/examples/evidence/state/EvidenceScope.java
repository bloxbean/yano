package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Strict reversible evidence effect scope used by result callbacks. */
public record EvidenceScope(byte[] evidenceIdHash,
                            long businessVersion,
                            EvidenceEffectOperation operation) {
    /** Validates the fixed hash, positive version, and operation. */
    public EvidenceScope {
        evidenceIdHash = EvidenceValidation.exactBytes(evidenceIdHash, 32);
        businessVersion = EvidenceValidation.positiveVersion(businessVersion);
        operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public byte[] evidenceIdHash() {
        return evidenceIdHash.clone();
    }

    /** Returns the exact canonical scope text. */
    public String encode() {
        return "evidence/" + HexFormat.of().formatHex(evidenceIdHash) + "/"
                + businessVersion + "/" + operation.scopeSuffix();
    }

    /** Parses lowercase hex, canonical positive decimal, and one known suffix. */
    public static EvidenceScope parse(String encoded) {
        if (encoded == null || encoded.length() > 100) {
            throw EvidenceValidation.invalid();
        }
        String[] fields = encoded.split("/", -1);
        if (fields.length != 4 || !fields[0].equals("evidence")
                || !fields[1].matches("[0-9a-f]{64}")
                || !fields[2].matches("[1-9][0-9]{0,18}")) {
            throw EvidenceValidation.invalid();
        }
        try {
            long version = Long.parseLong(fields[2]);
            EvidenceEffectOperation operation = Arrays.stream(EvidenceEffectOperation.values())
                    .filter(candidate -> candidate.scopeSuffix().equals(fields[3]))
                    .findFirst()
                    .orElseThrow(EvidenceValidation::invalid);
            EvidenceScope scope = new EvidenceScope(
                    HexFormat.of().parseHex(fields[1]), version, operation);
            if (!scope.encode().equals(encoded)) {
                throw EvidenceValidation.invalid();
            }
            return scope;
        } catch (IllegalArgumentException exception) {
            throw EvidenceValidation.invalid();
        }
    }

    /** Returns the immutable record key addressed by this scope. */
    public byte[] recordKey() {
        return EvidenceKeys.recordKey(evidenceIdHash, businessVersion);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceScope scope
                && businessVersion == scope.businessVersion
                && operation == scope.operation
                && Arrays.equals(evidenceIdHash, scope.evidenceIdHash);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(businessVersion, operation)
                + Arrays.hashCode(evidenceIdHash);
    }
}
