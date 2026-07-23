package com.bloxbean.cardano.yano.appchain.integration.detail;

/** One connector-specific, canonical, stable detail payload. */
public interface ConnectorDetailData {
    /**
     * Returns the connector action encoded by this detail payload.
     *
     * @return the connector action
     */
    ConnectorAction action();

    /**
     * Encodes this payload as its strict canonical CBOR array.
     *
     * @return a new canonical encoding
     */
    byte[] encode();
}
