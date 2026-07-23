package com.bloxbean.cardano.yano.appchain.integration.kafka;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorFingerprintDomain;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.regex.Pattern;

/** Credential-free identity of the resolved Kafka topic. */
public final class KafkaDestinationFingerprint {
    private static final Pattern PHYSICAL_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");

    private KafkaDestinationFingerprint() {
    }

    /**
     * Computes the credential-free commitment to a resolved physical topic.
     *
     * @param targetId the configured Kafka target alias
     * @param physicalTopic the resolved provider topic name
     * @return the resolved-destination commitment
     */
    public static ConnectorTargetFingerprint compute(String targetId, String physicalTopic) {
        String id = ContractValidation.alias(targetId, "targetId");
        if (physicalTopic == null || physicalTopic.equals(".") || physicalTopic.equals("..")
                || !PHYSICAL_TOPIC.matcher(physicalTopic).matches()) {
            throw CanonicalCbor.malformed();
        }
        Array descriptor = new Array();
        descriptor.add(new UnicodeString(id));
        descriptor.add(new UnicodeString(physicalTopic));
        return ConnectorTargetFingerprint.compute(ConnectorFingerprintDomain.KAFKA_DESTINATION,
                CanonicalCbor.encode(descriptor));
    }
}
