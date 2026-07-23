package com.bloxbean.cardano.yano.appchain.integration.detail;

import java.io.IOException;
import java.util.Optional;

/**
 * Durable content-addressed archive boundary. Implementations must complete a
 * create-if-absent durable write before returning. Retrieval is an internal
 * operation and must only be exposed through an authorized operator surface.
 */
public interface ConnectorDetailArchive extends AutoCloseable {
    /**
     * Durably creates or verifies the content-addressed entry.
     *
     * @param document the canonical detail document
     * @return the document commitment used as its retrieval key
     * @throws IOException when durable archival or verification fails
     */
    ConnectorDetailHash archive(ConnectorDetailDocumentV1 document) throws IOException;

    /**
     * Retrieves and verifies a previously archived document.
     *
     * @param hash the content-addressed retrieval key
     * @return the verified document, or empty when no entry exists
     * @throws IOException when the archive cannot be read or verification fails
     */
    Optional<ConnectorDetailDocumentV1> retrieve(ConnectorDetailHash hash) throws IOException;

    @Override
    default void close() throws IOException {
    }
}
