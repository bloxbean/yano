package com.bloxbean.cardano.yano.runtime.utxo.index;

/** A host lifecycle violation must abort apply, not be treated as an optional extraction failure. */
final class IndexLifecycleException extends IllegalStateException {
    IndexLifecycleException(String message) { super(message); }
}
