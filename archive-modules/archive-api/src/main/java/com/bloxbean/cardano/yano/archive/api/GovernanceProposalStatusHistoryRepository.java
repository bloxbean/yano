package com.bloxbean.cardano.yano.archive.api;

public interface GovernanceProposalStatusHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS; }
}
