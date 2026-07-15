package com.bloxbean.cardano.yano.appchain.integration.detail;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

/** Stable compact action codes used only inside connector detail documents. */
public enum ConnectorAction {
    /** Detail action for {@code kafka.publish}. */
    KAFKA_PUBLISH(1, ConnectorTypes.KAFKA_PUBLISH),
    /** Detail action for {@code object.put}. */
    OBJECT_PUT(2, ConnectorTypes.OBJECT_PUT),
    /** Detail action for {@code ipfs.pin}. */
    IPFS_PIN(3, ConnectorTypes.IPFS_PIN);

    private final int code;
    private final String type;

    ConnectorAction(int code, String type) {
        this.code = code;
        this.type = type;
    }

    /**
     * Returns the compact detail-document wire code.
     *
     * @return the positive action code
     */
    public int code() { return code; }

    /**
     * Returns the corresponding effect type.
     *
     * @return the stable connector effect type
     */
    public String type() { return type; }

    /**
     * Resolves a detail-document wire code.
     *
     * @param code the unsigned action code
     * @return the matching action
     * @throws com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException
     *         when the code is unknown
     */
    public static ConnectorAction fromCode(long code) {
        for (ConnectorAction action : values()) {
            if (action.code == code) {
                return action;
            }
        }
        throw CanonicalCbor.malformed();
    }
}
