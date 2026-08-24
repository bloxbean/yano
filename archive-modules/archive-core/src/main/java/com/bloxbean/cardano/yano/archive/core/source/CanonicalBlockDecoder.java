package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;

@FunctionalInterface
public interface CanonicalBlockDecoder<B> {
    BlockSourceContext<B> decode(long blockNumber, CanonicalBlockReference reference, byte[] body);
}
