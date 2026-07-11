package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;

/**
 * Compatibility facade for Shelley and later header validation.
 *
 * <p>`structural` validates the header CBOR shape and declared hash.
 * `header-signature` additionally validates KES signature and operational
 * certificate cold signature through named pipeline stages.</p>
 */
public final class ShelleyHeaderValidator implements HeaderValidator {
    private final HeaderValidationPipeline pipeline;

    public ShelleyHeaderValidator(String level, long slotsPerKESPeriod, long maxKESEvolutions) {
        this(level, slotsPerKESPeriod, maxKESEvolutions, HeaderValidationNonceProvider.none());
    }

    public ShelleyHeaderValidator(String level,
                                  long slotsPerKESPeriod,
                                  long maxKESEvolutions,
                                  HeaderValidationNonceProvider nonceProvider) {
        this(level, slotsPerKESPeriod, maxKESEvolutions,
                nonceProvider, HeaderValidationLedgerViewProvider.none());
    }

    public ShelleyHeaderValidator(String level,
                                  long slotsPerKESPeriod,
                                  long maxKESEvolutions,
                                  HeaderValidationNonceProvider nonceProvider,
                                  HeaderValidationLedgerViewProvider ledgerViewProvider) {
        this.pipeline = HeaderValidationPipeline.forProfile(level, slotsPerKESPeriod, maxKESEvolutions,
                nonceProvider, ledgerViewProvider);
    }

    @Override
    public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
        return pipeline.validateShelley(blockHeader, originalHeaderBytes);
    }

    @Override
    public HeaderValidationSnapshot snapshot() {
        return pipeline.snapshot();
    }
}
