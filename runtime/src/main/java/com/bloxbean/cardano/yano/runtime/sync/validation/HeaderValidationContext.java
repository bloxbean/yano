package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;

/**
 * Input shared by ordered header-validation stages.
 */
public final class HeaderValidationContext {
    private final BlockHeader blockHeader;
    private final byte[] originalHeaderBytes;
    private final long slotsPerKESPeriod;
    private final long maxKESEvolutions;
    private ShelleyHeaderView shelleyHeaderView;

    public HeaderValidationContext(BlockHeader blockHeader,
                                   byte[] originalHeaderBytes,
                                   long slotsPerKESPeriod,
                                   long maxKESEvolutions) {
        this.blockHeader = blockHeader;
        this.originalHeaderBytes = originalHeaderBytes != null ? originalHeaderBytes.clone() : null;
        this.slotsPerKESPeriod = slotsPerKESPeriod;
        this.maxKESEvolutions = maxKESEvolutions;
    }

    public BlockHeader blockHeader() {
        return blockHeader;
    }

    public byte[] originalHeaderBytes() {
        return originalHeaderBytes != null ? originalHeaderBytes.clone() : null;
    }

    public long slotsPerKESPeriod() {
        return slotsPerKESPeriod;
    }

    public long maxKESEvolutions() {
        return maxKESEvolutions;
    }

    public ShelleyHeaderView shelleyHeader() {
        if (shelleyHeaderView == null) {
            shelleyHeaderView = ShelleyHeaderView.from(blockHeader, originalHeaderBytes);
        }
        return shelleyHeaderView;
    }
}
