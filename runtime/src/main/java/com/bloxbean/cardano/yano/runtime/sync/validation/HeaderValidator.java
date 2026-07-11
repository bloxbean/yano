package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;

/**
 * Pluggable upstream header validation boundary.
 */
public interface HeaderValidator {
    HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes);

    HeaderValidationSnapshot snapshot();

    static HeaderValidator none() {
        return new HeaderValidator() {
            @Override
            public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
                return HeaderValidationResult.accepted("none");
            }

            @Override
            public HeaderValidationSnapshot snapshot() {
                return HeaderValidationSnapshot.none();
            }
        };
    }
}
