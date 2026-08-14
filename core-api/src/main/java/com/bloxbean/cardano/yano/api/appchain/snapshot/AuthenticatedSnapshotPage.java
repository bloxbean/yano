package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.List;
import java.util.Objects;

/** One bounded, root-fixed page of public authenticated snapshot descriptors. */
public record AuthenticatedSnapshotPage(
        List<AuthenticatedSnapshotSummary> items,
        String nextCursor,
        long viewHeight,
        byte[] viewRoot
) {
    public AuthenticatedSnapshotPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        viewRoot = Objects.requireNonNull(viewRoot, "viewRoot").clone();
        if (viewHeight <= 0 || viewRoot.length != 32) {
            throw new IllegalArgumentException("invalid authenticated snapshot page view");
        }
    }
    @Override public byte[] viewRoot() { return viewRoot.clone(); }
}
