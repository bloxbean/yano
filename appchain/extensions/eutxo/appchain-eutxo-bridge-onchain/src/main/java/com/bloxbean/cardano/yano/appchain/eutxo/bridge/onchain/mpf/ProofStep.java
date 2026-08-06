package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
// Explicit import for Plutus library resolver (same-package, but needed for transitive discovery)
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.Neighbor;

/**
 * A proof step in a Merkle Patricia Forestry proof.
 * <p>
 * Constr tags match Aiken declaration order:
 * Branch=0, Fork=1, Leaf=2
 */
@OnchainLibrary
public sealed interface ProofStep permits ProofStep.Branch, ProofStep.Fork, ProofStep.Leaf {

    record Branch(int skip, byte[] neighbors) implements ProofStep {}

    record Fork(int skip, Neighbor neighbor) implements ProofStep {}

    record Leaf(int skip, byte[] key, byte[] value) implements ProofStep {}
}
