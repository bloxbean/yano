package com.bloxbean.cardano.yano.appchain.integration.objectstore;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorFingerprintDomain;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.regex.Pattern;

/** Credential-free identity of one resolved immutable object destination. */
public final class ObjectDestinationFingerprint {
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    private ObjectDestinationFingerprint() {
    }

    /**
     * Computes the credential-free commitment to a resolved immutable destination.
     *
     * @param targetId the configured object-store target alias
     * @param destinationBucket the resolved provider bucket or container
     * @param destinationPrefix the normalized configured prefix, or empty
     * @param destinationRelativeKey the command-relative destination key
     * @param encryptionPolicyId the configured encryption-policy alias
     * @param retentionPolicyId the configured retention-policy alias
     * @return the resolved-destination commitment
     */
    public static ConnectorTargetFingerprint compute(String targetId,
                                                     String destinationBucket,
                                                     String destinationPrefix,
                                                     String destinationRelativeKey,
                                                     String encryptionPolicyId,
                                                     String retentionPolicyId) {
        String id = ContractValidation.alias(targetId, "targetId");
        if (destinationBucket == null || !RESOURCE.matcher(destinationBucket).matches()) {
            throw CanonicalCbor.malformed();
        }
        String prefix = destinationPrefix == null || destinationPrefix.isEmpty()
                ? "" : ContractValidation.objectKey(destinationPrefix);
        String key = ContractValidation.objectKey(destinationRelativeKey);
        String encryption = ContractValidation.alias(encryptionPolicyId, "encryptionPolicyId");
        String retention = ContractValidation.alias(retentionPolicyId, "retentionPolicyId");
        Array descriptor = new Array();
        descriptor.add(new UnicodeString(id));
        descriptor.add(new UnicodeString(destinationBucket));
        descriptor.add(new UnicodeString(prefix));
        descriptor.add(new UnicodeString(key));
        descriptor.add(new UnicodeString(encryption));
        descriptor.add(new UnicodeString(retention));
        return ConnectorTargetFingerprint.compute(ConnectorFingerprintDomain.OBJECT_DESTINATION,
                CanonicalCbor.encode(descriptor));
    }
}
