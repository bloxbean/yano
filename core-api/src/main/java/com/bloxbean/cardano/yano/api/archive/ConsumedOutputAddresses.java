package com.bloxbean.cardano.yano.api.archive;

/**
 * Addresses of the outputs a block consumed, resolved while those outputs were still current.
 *
 * <p>The address-transaction dataset needs to know which address each spent input belonged to.
 * That is the one part of the dataset which is not a function of the block: the consumed output
 * was created by an earlier block and, by the time any sink sees the envelope, has been removed
 * from the UTXO set. It is therefore resolved at capture time — the same reasoning that puts
 * pointer-address resolution in the canonical apply rather than in the sink.
 *
 * <p>The UTXO subsystem already reads every consumed output during apply in order to delete it,
 * so supplying this costs a lookup that has already happened, not a new one. A contributor that
 * does not require the address-transaction section never asks for it and pays nothing.
 */
@FunctionalInterface
public interface ConsumedOutputAddresses {

    /**
     * @param txHash      hex transaction id of the consumed output
     * @param outputIndex its index within that transaction
     * @return the address exactly as the node stores it — the same encoded form the address
     *         parser already accepts — or null when the output was not retained (a storage
     *         filter may exclude it). Callers must treat null as "unknown", never as
     *         "no address".
     */
    String addressOf(String txHash, int outputIndex);

    /** Nothing was captured; used by contributors that do not need input addresses. */
    ConsumedOutputAddresses NONE = (txHash, outputIndex) -> null;
}
