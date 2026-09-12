package org.yanoproject.p2p.tx.diffusion;

/**
 * Runtime-owned transaction admission boundary used by tx diffusion.
 */
@FunctionalInterface
public interface TxAdmissionPort {
    String admitTransaction(byte[] txCbor, String origin);
}
