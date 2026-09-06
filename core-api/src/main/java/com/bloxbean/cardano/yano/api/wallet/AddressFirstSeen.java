package com.bloxbean.cardano.yano.api.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A null slot is definitive only in a successful, complete-coverage response. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AddressFirstSeen(Long firstSeenSlot, WalletIndexCoverage coverage, WalletChainPoint liveTip) {
    public AddressFirstSeen(Long firstSeenSlot, WalletIndexCoverage coverage) {
        this(firstSeenSlot, coverage, null);
    }
}
