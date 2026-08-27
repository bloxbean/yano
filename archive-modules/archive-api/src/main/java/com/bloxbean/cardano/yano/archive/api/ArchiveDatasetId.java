package com.bloxbean.cardano.yano.archive.api;

public enum ArchiveDatasetId {
    ACCOUNT_EVENT(SourceKind.BLOCK),
    ADDRESS_TRANSACTION(SourceKind.BLOCK),
    TRANSACTION(SourceKind.BLOCK),
    UTXO_HISTORY(SourceKind.BLOCK),
    REWARD(SourceKind.EPOCH),
    EPOCH_STAKE(SourceKind.EPOCH),
    DREP_DISTRIBUTION(SourceKind.EPOCH),
    ADA_POT(SourceKind.EPOCH),
    GOVERNANCE_PROPOSAL_STATUS(SourceKind.EPOCH);

    private final SourceKind sourceKind;

    ArchiveDatasetId(SourceKind sourceKind) {
        this.sourceKind = sourceKind;
    }

    public SourceKind sourceKind() {
        return sourceKind;
    }

    public String logicalName() {
        return name().toLowerCase();
    }
}
