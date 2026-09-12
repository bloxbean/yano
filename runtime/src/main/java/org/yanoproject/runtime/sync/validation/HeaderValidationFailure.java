package org.yanoproject.runtime.sync.validation;

/**
 * Internal fail-closed signal for a named header-validation stage.
 */
final class HeaderValidationFailure extends RuntimeException {
    private final String stage;

    HeaderValidationFailure(String stage, String reason) {
        super(reason);
        this.stage = stage;
    }

    String stage() {
        return stage;
    }
}
