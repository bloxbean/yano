package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

/**
 * Pure deterministic optional commitment boundary for validity-proof modules.
 *
 * <p>Implementations must not perform proof generation, filesystem, network,
 * clock or randomness access from these methods. Proof generation consumes
 * the committed witness descriptor asynchronously.</p>
 */
public interface EutxoValidityCommitmentEngine {

    String id();

    /** Digest of the immutable validity circuit/profile semantics. */
    String profileDigest();

    EutxoValidityCommitment genesis();

    EutxoValidityCommitment commit(EutxoValidityTransition transition);
}
