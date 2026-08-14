package com.bloxbean.cardano.yano.api.plugin.domain;

/**
 * Narrow host capability for state-machine-owned privileged commands.
 * Authentication remains host-owned. The retained context handle is usable
 * only while the host is dispatching a route declared
 * {@link DomainApiAccess#PRIVILEGED}; read callbacks and plugin-created
 * background threads fail closed.
 */
public interface PrivilegedSystemMessageService {
    int MAX_TOPIC_LENGTH = 160;
    int MAX_BODY_BYTES = DomainApiRequest.MAX_BODY_BYTES;

    /** Runs the selected state machine's admission checks without submission. */
    void validate(String chainId, String topic, byte[] body);

    /** Validates, signs, and submits one admitted reserved-topic command. */
    String submit(String chainId, String topic, byte[] body);

    static PrivilegedSystemMessageService unavailable() {
        return new PrivilegedSystemMessageService() {
            @Override
            public void validate(String chainId, String topic, byte[] body) {
                throw new IllegalStateException(
                        "privileged system-message service is unavailable");
            }

            @Override
            public String submit(String chainId, String topic, byte[] body) {
                throw new IllegalStateException(
                        "privileged system-message service is unavailable");
            }
        };
    }
}
