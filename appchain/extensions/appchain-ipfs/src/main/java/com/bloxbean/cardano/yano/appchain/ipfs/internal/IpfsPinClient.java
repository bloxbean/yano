package com.bloxbean.cardano.yano.appchain.ipfs.internal;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;

/**
 * Minimal provider-neutral boundary for probing and creating explicit IPFS
 * pins. Implementations must normalize provider failures before they cross
 * this interface.
 */
public interface IpfsPinClient extends AutoCloseable {
    /**
     * Observes the current local pin state for exactly one CID.
     *
     * @param cid canonical CID committed by the effect
     * @param effectIdHash 32-byte effect identifier used only as a trace header
     * @return the exact observed pin state
     */
    PinState probe(CanonicalCid cid, byte[] effectIdHash);

    /**
     * Requests one explicit pin. A successful return acknowledges only that
     * the provider accepted the call; callers must re-probe before confirming.
     *
     * @param cid canonical CID committed by the effect
     * @param recursive whether the reachable DAG must be pinned
     * @param effectIdHash 32-byte effect identifier used only as a trace header
     */
    void add(CanonicalCid cid, boolean recursive, byte[] effectIdHash);

    /** Releases adapter resources using the implementation's bounded policy. */
    @Override
    void close();
}
