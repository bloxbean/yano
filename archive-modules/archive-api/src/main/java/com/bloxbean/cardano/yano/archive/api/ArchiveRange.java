package com.bloxbean.cardano.yano.archive.api;

public sealed interface ArchiveRange permits BlockRange, EpochRange {
    SourceKind sourceKind();

    long startInclusive();

    long endInclusive();

    default String canonicalForm() {
        return sourceKind().name() + ':' + startInclusive() + ':' + endInclusive();
    }
}
