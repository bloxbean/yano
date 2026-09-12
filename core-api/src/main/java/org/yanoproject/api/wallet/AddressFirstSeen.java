package org.yanoproject.api.wallet;

import org.yanoproject.api.chain.ChainPoint;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A null slot is definitive only in a successful, complete-coverage response. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AddressFirstSeen(Long firstSeenSlot, WalletIndexCoverage coverage, ChainPoint liveTip) {
    public AddressFirstSeen(Long firstSeenSlot, WalletIndexCoverage coverage) {
        this(firstSeenSlot, coverage, null);
    }
}
