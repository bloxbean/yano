package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.nio.file.Path;
import java.util.Objects;

/** One explicit business operation executed against an already-running demo. */
record ScenarioRequest(Operation operation,
                       String evidenceId,
                       long businessVersion,
                       Path sampleFile) {
    private static final String EVIDENCE_ID = "[a-z][a-z0-9-]{0,62}";

    ScenarioRequest {
        operation = Objects.requireNonNull(operation, "operation");
        if (evidenceId == null || !evidenceId.matches(EVIDENCE_ID)) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        if (businessVersion < 0 || businessVersion == Long.MAX_VALUE) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        if (operation == Operation.PUBLISH && businessVersion != 1
                || operation == Operation.REPUBLISH && businessVersion < 2
                || operation == Operation.REPLAY && businessVersion < 1
                || operation == Operation.RUN && businessVersion != 0) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        if (operation.requiresSample() != (sampleFile != null)) {
            throw new DemoException(DemoError.INVALID_ARGUMENT);
        }
        if (sampleFile != null) {
            sampleFile = sampleFile.toAbsolutePath().normalize();
        }
    }

    static ScenarioRequest runDefaults(DemoConfig config) {
        return new ScenarioRequest(Operation.RUN, config.evidenceId(), 0, config.sampleFile());
    }

    enum Operation {
        RUN,
        PUBLISH,
        REPUBLISH,
        VERIFY,
        REPLAY;

        boolean requiresSample() {
            return this != VERIFY;
        }
    }
}
