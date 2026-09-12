package org.yanoproject.api.utxo.index;

/** An optional index cannot provide an authoritative answer at the applied point. */
public final class IndexUnavailableException extends IllegalStateException {
    public IndexUnavailableException(String message) { super(message); }
}
