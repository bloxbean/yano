package org.yanoproject.archive.core.source;

import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.archive.core.dataset.BlockSourceContext;

@FunctionalInterface
public interface CanonicalBlockDecoder<B> {
    BlockSourceContext<B> decode(long blockNumber, CanonicalBlockReference reference, byte[] body);
}
